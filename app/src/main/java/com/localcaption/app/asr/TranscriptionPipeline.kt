package com.localcaption.app.asr

import android.util.Log
import com.localcaption.app.audio.AudioCaptureSource
import com.localcaption.app.audio.SessionWavRecorder
import com.localcaption.app.data.model.AudioSourceType
import com.localcaption.app.data.prefs.AppSettings
import com.localcaption.app.data.repository.TranscriptionRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Lifecycle of a [TranscriptionPipeline]. */
enum class TranscriptionState {
    IDLE,
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    ERROR,
}

/** Runtime inference backend & latency snapshot for verifying CPU vs NNAPI execution. */
data class InferenceTelemetry(
    val activeProvider: String = "cpu",
    val fellBackFromNnapi: Boolean = false,
    val lastInferenceMs: Long = 0L,
    val lastAudioMs: Long = 0L,
) {
    val lastRtf: Float
        get() = if (lastAudioMs > 0L && lastInferenceMs > 0L) {
            lastInferenceMs.toFloat() / lastAudioMs.toFloat()
        } else {
            0f
        }
}

/**
 * A finalized caption emitted for the floating overlay / live view.
 *
 * @property segmentId database id of the persisted segment
 * @property speakerLabel detected speaker (`说话人 1`, ...) or `null`
 */
data class LiveCaption(
    val segmentId: Long,
    val sessionId: Long,
    val speakerLabel: String? = null,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val isFinal: Boolean = true,
) {
    /** `mm:ss` style start timestamp. */
    val timestamp: String
        get() {
            val totalSeconds = (startMs / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
        }
}

/**
 * End-to-end offline transcription pipeline:
 *
 * ```
 * AudioCaptureSource -> VadSegmenter -> SherpaAsrEngine + SpeakerDiarizer
 *                    -> TranscriptionRepository -> StateFlow/SharedFlow
 * ```
 *
 * Audio chunks are handed off through a bounded channel so the capture thread never
 * blocks on recognition. Recognition, diarization and database writes all happen on
 * the pipeline's worker coroutine, which keeps segments strictly ordered.
 */
class TranscriptionPipeline(
    private val repository: TranscriptionRepository,
    private val audioCaptureSource: AudioCaptureSource,
    private val vadSegmenter: VadSegmenter,
    private val asrEngine: AsrEngine,
    private val speakerDiarizer: SpeakerDiarizer? = null,
    private val audioSourceType: AudioSourceType,
    private val sampleRate: Int = AudioCaptureSource.SAMPLE_RATE,
    private val wavOutputFile: File? = null,
    parentScope: CoroutineScope? = null,
    private val maxRecentCaptions: Int = DEFAULT_RECENT_CAPTIONS,
) : AutoCloseable {

    private val lifecycleLock = Any()

    private val pipelineJob = SupervisorJob(parentScope?.coroutineContext?.get(Job))
    private val scope = CoroutineScope(
        (parentScope?.coroutineContext ?: Dispatchers.Default) + pipelineJob,
    )

    private val _state = MutableStateFlow(TranscriptionState.IDLE)

    /** Current pipeline state. */
    val state: StateFlow<TranscriptionState> = _state.asStateFlow()

    private val _recentCaptions = MutableStateFlow<List<LiveCaption>>(emptyList())

    /** Rolling list of the most recent captions (newest last). */
    val recentCaptions: StateFlow<List<LiveCaption>> = _recentCaptions.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)

    /** Last pipeline error, if any. */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)

    /** Smoothed live input level in `[0f, 1f]` for waveform UI indicators. */
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _telemetry = MutableStateFlow(InferenceTelemetry())

    /** Live execution provider (CPU / NNAPI) and per-segment latency telemetry. */
    val telemetry: StateFlow<InferenceTelemetry> = _telemetry.asStateFlow()

    @Volatile
    private var lastLevelUpdateMs: Long = 0L

    private val _captions = MutableSharedFlow<LiveCaption>(
        replay = 0,
        extraBufferCapacity = CAPTION_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Live caption stream; each emission is a finalized segment. */
    val captions: SharedFlow<LiveCaption> = _captions.asSharedFlow()

    @Volatile
    private var sessionId: Long = 0L

    @Volatile
    private var audioChannel: Channel<FloatArray>? = null

    @Volatile
    private var wavRecorder: SessionWavRecorder? = null

    @Volatile
    private var worker: Job? = null

    @Volatile
    private var closing = false

    /** `true` while audio is being captured and processed. */
    val isRunning: Boolean
        get() = _state.value == TranscriptionState.RUNNING

    /** Session id of the current/last run, `0` before the first start. */
    val currentSessionId: Long
        get() = sessionId

    /** Pre-loads the ASR engine before starting audio capture. */
    suspend fun warmUp(): Boolean {
        val ready = asrEngine.warmUp()
        _telemetry.update {
            it.copy(
                activeProvider = asrEngine.activeProvider,
                fellBackFromNnapi = asrEngine.fellBackFromNnapi,
            )
        }
        return ready
    }

    /**
     * Starts capturing audio and transcribing it into [sessionId].
     *
     * @return `true` when capture started; check [lastError] otherwise
     */
    fun start(sessionId: Long): Boolean {
        synchronized(lifecycleLock) {
            val current = _state.value
            val canStart = current == TranscriptionState.IDLE ||
                current == TranscriptionState.STOPPED ||
                current == TranscriptionState.ERROR
            if (closing || !canStart) {
                return false
            }

            _state.value = TranscriptionState.STARTING
            _lastError.value = null
            _recentCaptions.value = emptyList()
            _audioLevel.value = 0f
            this.sessionId = sessionId

            vadSegmenter.reset()
            speakerDiarizer?.reset()

            val channel = Channel<FloatArray>(
                capacity = AUDIO_CHANNEL_CAPACITY,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
            audioChannel = channel

            val recorder = wavOutputFile?.let { file ->
                runCatching { SessionWavRecorder(file, sampleRate) }
                    .onFailure { Log.w(TAG, "Unable to open session WAV recorder at $file", it) }
                    .getOrNull()
            }
            wavRecorder = recorder

            val started = audioCaptureSource.start { samples ->
                updateAudioLevel(samples)
                recorder?.append(samples)
                channel.trySend(samples)
            }
            if (!started) {
                audioChannel = null
                channel.close()
                runCatching { recorder?.close() }
                wavRecorder = null
                _audioLevel.value = 0f
                _lastError.value = "无法启动音频采集，请检查权限或录屏授权"
                _state.value = TranscriptionState.ERROR
                return false
            }

            worker = scope.launch { runPipeline(channel) }
            return true
        }
    }

    /** Stops capture first, then closes the channel so every buffered segment finishes. */
    fun stop() {
        val channelToClose: Channel<FloatArray>?
        synchronized(lifecycleLock) {
            val current = _state.value
            if (current != TranscriptionState.RUNNING && current != TranscriptionState.STARTING) {
                return
            }
            _state.value = TranscriptionState.STOPPING
            channelToClose = audioChannel
            audioChannel = null
        }
        audioCaptureSource.stop()
        _audioLevel.value = 0f
        channelToClose?.close()
        runCatching { wavRecorder?.close() }
        wavRecorder = null
    }

    private fun updateAudioLevel(samples: FloatArray) {
        if (samples.isEmpty()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLevelUpdateMs < AUDIO_LEVEL_INTERVAL_MS) return
        lastLevelUpdateMs = now

        var sumSquares = 0.0
        for (sample in samples) {
            sumSquares += (sample * sample).toDouble()
        }
        val rms = kotlin.math.sqrt(sumSquares / samples.size).toFloat()
        val normalized = (rms * 6.5f).coerceIn(0f, 1f)
        val previous = _audioLevel.value
        val smoothed = if (normalized >= previous) {
            previous * 0.35f + normalized * 0.65f
        } else {
            previous * 0.7f + normalized * 0.3f
        }
        _audioLevel.value = if (smoothed < 0.01f) 0f else smoothed
    }

    /** Suspends until the worker coroutine processed its final segment. */
    suspend fun awaitStopped() {
        worker?.join()
    }

    /** Releases native resources and cancels any in-flight work. */
    override fun close() {
        synchronized(lifecycleLock) {
            closing = true
        }
        stop()
        runCatching { asrEngine.close() }
        runCatching { speakerDiarizer?.close() }
        runCatching { vadSegmenter.close() }
        pipelineJob.cancel()
    }

    private suspend fun runPipeline(channel: Channel<FloatArray>) {
        _state.compareAndSet(TranscriptionState.STARTING, TranscriptionState.RUNNING)
        try {
            for (chunk in channel) {
                val segments = vadSegmenter.process(chunk)
                for (segment in segments) {
                    processSegment(segment)
                }
            }
            // Channel closed by stop(): emit whatever VAD still holds.
            for (segment in vadSegmenter.flush()) {
                processSegment(segment)
            }
            _state.value = TranscriptionState.STOPPED
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Log.e(TAG, "Transcription pipeline failed", throwable)
            _lastError.value = throwable.message ?: throwable.javaClass.simpleName
            _state.value = TranscriptionState.ERROR
        } finally {
            synchronized(lifecycleLock) {
                if (audioChannel === channel) {
                    audioChannel = null
                }
            }
        }
    }

    private suspend fun processSegment(segment: SpeechSegment) {
        if (segment.isEmpty) return

        val speaker = runCatching { speakerDiarizer?.identify(segment) }
            .onFailure { Log.w(TAG, "Speaker diarization failed", it) }
            .getOrNull()

        val result = try {
            asrEngine.transcribe(segment.samples, sampleRate)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Log.w(TAG, "Recognition failed for segment", throwable)
            _lastError.value = throwable.message ?: throwable.javaClass.simpleName
            AsrResult(text = "")
        }
        val text = result.text.trim()
        _telemetry.value = InferenceTelemetry(
            activeProvider = asrEngine.activeProvider,
            fellBackFromNnapi = asrEngine.fellBackFromNnapi,
            lastInferenceMs = result.durationMs,
            lastAudioMs = segment.durationMs,
        )
        if (text.isEmpty()) return

        val speakerId = speaker?.let { identity ->
            runCatching { repository.getOrCreateSpeaker(sessionId, identity.label) }
                .onFailure { Log.w(TAG, "Unable to persist speaker ${identity.label}", it) }
                .getOrNull()
        }

        val segmentId = repository.addSegment(
            sessionId = sessionId,
            text = text,
            startMs = segment.startMs,
            endMs = segment.endMs,
            audioSource = audioSourceType,
            speakerId = speakerId,
        )

        val caption = LiveCaption(
            segmentId = segmentId,
            sessionId = sessionId,
            speakerLabel = speaker?.label,
            text = text,
            startMs = segment.startMs,
            endMs = segment.endMs,
        )
        _captions.emit(caption)
        _recentCaptions.update { previous ->
            (previous + caption).takeLast(maxRecentCaptions)
        }
    }

    companion object {
        private const val TAG = "TranscriptionPipeline"
        private const val AUDIO_CHANNEL_CAPACITY = 512
        private const val CAPTION_BUFFER_CAPACITY = 64
        private const val AUDIO_LEVEL_INTERVAL_MS = 55L
        const val DEFAULT_RECENT_CAPTIONS = 60

        /**
         * Builds a pipeline from user [settings] and the installed models. Missing
         * models do not fail construction: the pipeline falls back to the energy VAD
         * and simply yields no captions until the model is installed.
         */
        fun create(
            repository: TranscriptionRepository,
            settings: AppSettings,
            audioCaptureSource: AudioCaptureSource,
            modelManager: ModelManager,
            wavOutputFile: File? = null,
            parentScope: CoroutineScope? = null,
        ): TranscriptionPipeline {
            val asrEngine = SherpaAsrEngine(
                modelType = settings.asrModel,
                modelDir = modelManager.modelDir(settings.asrModel),
                numThreads = settings.numThreads,
                defaultLanguage = settings.language,
                provider = settings.executionProvider,
                debug = settings.executionProvider == AppSettings.PROVIDER_NNAPI,
            )

            val vadHandle = modelManager.sileroVadFile()?.let { modelFile ->
                SherpaOnnxBridge.createVad(
                    modelFile = modelFile,
                    sampleRate = AudioCaptureSource.SAMPLE_RATE,
                    silenceMs = settings.vadSilenceMs,
                    maxSpeechMs = settings.maxSegmentDurationMs,
                    threshold = 0.38f,
                    numThreads = 1,
                )
            }
            val vadSegmenter = VadSegmenter(
                sampleRate = AudioCaptureSource.SAMPLE_RATE,
                vadSilenceMs = settings.vadSilenceMs,
                maxSegmentDurationMs = settings.maxSegmentDurationMs,
                preRollSilenceRatio = settings.preRollSilenceRatio,
                vadHandle = vadHandle,
            )

            val speakerDiarizer = if (settings.enableDiarization) {
                SpeakerDiarizer(
                    extractor = modelManager.speakerEmbeddingFile()?.let { modelFile ->
                        SherpaOnnxBridge.createSpeakerEmbeddingExtractor(
                            modelFile = modelFile,
                            numThreads = 1,
                        )
                    },
                    tracker = SpeakerClusterTracker(
                        threshold = settings.diarizationThreshold,
                    ),
                )
            } else {
                null
            }

            return TranscriptionPipeline(
                repository = repository,
                audioCaptureSource = audioCaptureSource,
                vadSegmenter = vadSegmenter,
                asrEngine = asrEngine,
                speakerDiarizer = speakerDiarizer,
                audioSourceType = settings.audioSource,
                wavOutputFile = wavOutputFile,
                parentScope = parentScope,
            )
        }
    }
}