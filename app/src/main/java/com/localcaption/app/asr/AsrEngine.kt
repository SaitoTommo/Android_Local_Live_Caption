package com.localcaption.app.asr

import android.os.SystemClock
import android.util.Log
import com.localcaption.app.data.model.AsrModelType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Result of one offline transcription.
 *
 * @property text recognized text, already trimmed
 * @property language language reported/requested for the segment
 * @property durationMs time spent inside the recognizer
 */
data class AsrResult(
    val text: String,
    val language: String = "",
    val durationMs: Long = 0L,
) {
    val isEmpty: Boolean
        get() = text.isBlank()
}

/**
 * Offline speech recognizer used by [TranscriptionPipeline]. Implementations must be
 * safe to call from a single background pipeline thread.
 */
interface AsrEngine : AutoCloseable {

    /** Model backing this engine. */
    val modelType: AsrModelType

    /** `true` when the native recognizer could be initialized. */
    val isReady: Boolean

    /** Actual execution provider active in the engine (`"nnapi"` or `"cpu"`). */
    val activeProvider: String
        get() = "cpu"

    /** `true` when NNAPI was requested but failed to initialize and fell back to CPU. */
    val fellBackFromNnapi: Boolean
        get() = false

    /** Pre-loads the native recognizer into memory so the first segment does not stall. */
    suspend fun warmUp(): Boolean = isReady

    /**
     * Transcribes one speech segment.
     *
     * @param samples mono PCM float samples
     * @param sampleRate sample rate of [samples]
     * @param language optional language override, `"auto"` to detect
     */
    suspend fun transcribe(
        samples: FloatArray,
        sampleRate: Int = 16_000,
        language: String? = null,
    ): AsrResult

    override fun close()
}

/**
 * sherpa-onnx backed [AsrEngine] for Qwen3-ASR (INT8) and SenseVoice Small (INT8).
 *
 * Initialization is lazy and retried at most every [INIT_RETRY_INTERVAL_MS] so that a
 * model downloaded while the app is running (or a missing AAR) never crashes a
 * session; recognition then simply yields empty results until the model is ready.
 */
class SherpaAsrEngine(
    override val modelType: AsrModelType,
    private val modelDir: File,
    private val numThreads: Int = 2,
    private val defaultLanguage: String = "auto",
    private val provider: String = "cpu",
    private val debug: Boolean = false,
) : AsrEngine {

    private val mutex = Mutex()
    private val initLock = Any()

    @Volatile
    private var recognizer: SherpaOfflineRecognizer? = null

    @Volatile
    private var lastInitAttemptAtMs = 0L

    @Volatile
    private var initError: String? = null

    @Volatile
    private var closed = false

    override val isReady: Boolean
        get() = ensureRecognizer() != null

    override val activeProvider: String
        get() = recognizer?.activeProvider ?: provider

    override val fellBackFromNnapi: Boolean
        get() = recognizer?.fellBackFromNnapi ?: false

    override suspend fun warmUp(): Boolean = withContext(Dispatchers.Default) {
        ensureRecognizer() != null
    }

    /** Human readable reason why the recognizer is not available, if any. */
    val initializationError: String?
        get() = initError

    override suspend fun transcribe(
        samples: FloatArray,
        sampleRate: Int,
        language: String?,
    ): AsrResult = withContext(Dispatchers.Default) {
        if (samples.isEmpty()) return@withContext AsrResult("")

        val engine = ensureRecognizer() ?: return@withContext AsrResult("")
        mutex.withLock {
            val startedAt = SystemClock.elapsedRealtime()
            val text = runCatching { engine.recognize(samples, sampleRate) }
                .onFailure { Log.w(TAG, "Recognition failed", it) }
                .getOrDefault("")
                .trim()
            AsrResult(
                text = text,
                language = language ?: defaultLanguage,
                durationMs = SystemClock.elapsedRealtime() - startedAt,
            )
        }
    }

    /** Drops the native recognizer so the next call re-reads the model from disk. */
    fun reload() {
        synchronized(initLock) {
            closed = false
            recognizer?.let { runCatching { it.close() } }
            recognizer = null
            lastInitAttemptAtMs = 0L
            initError = null
        }
    }

    override fun close() {
        synchronized(initLock) {
            closed = true
            recognizer?.let { runCatching { it.close() } }
            recognizer = null
        }
    }

    private fun ensureRecognizer(): SherpaOfflineRecognizer? {
        if (closed) return null
        recognizer?.let { return it }

        synchronized(initLock) {
            if (closed) return null
            recognizer?.let { return it }

            val now = SystemClock.elapsedRealtime()
            if (now - lastInitAttemptAtMs < INIT_RETRY_INTERVAL_MS) return null
            lastInitAttemptAtMs = now

            if (!SherpaOnnxBridge.isAsrAvailable) {
                initError = "sherpa-onnx 未集成（将 sherpa-onnx-*.aar 放入 app/libs/）"
                return null
            }

            val created = SherpaOnnxBridge.createOfflineRecognizer(
                modelType = modelType,
                modelDir = modelDir,
                numThreads = numThreads,
                language = defaultLanguage,
                provider = provider,
                debug = debug,
            )
            recognizer = created
            initError = if (created == null) {
                "未安装模型 ${modelType.displayName}"
            } else {
                null
            }
            return created
        }
    }

    private companion object {
        const val TAG = "SherpaAsrEngine"
        const val INIT_RETRY_INTERVAL_MS = 5_000L
    }
}
