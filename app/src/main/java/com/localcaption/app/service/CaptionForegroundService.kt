package com.localcaption.app.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.localcaption.app.LocalCaptionApp
import com.localcaption.app.R
import com.localcaption.app.asr.LiveCaption
import com.localcaption.app.asr.TranscriptionPipeline
import com.localcaption.app.asr.TranscriptionState
import com.localcaption.app.audio.AudioCaptureSource
import com.localcaption.app.audio.MicAudioCapture
import com.localcaption.app.audio.PlaybackAudioCapture
import com.localcaption.app.audio.SessionWavRecorder
import com.localcaption.app.data.model.AudioSourceType
import com.localcaption.app.data.prefs.AppSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Snapshot of everything the UI needs to know about the caption service.
 *
 * @property isRunning `true` while a session exists and audio is being processed
 * @property isPaused `true` while capture is suspended through the overlay/UI
 * @property audioSource source of the current/last session, `null` when idle
 * @property activeSessionId database id of the current session, `0` when idle
 * @property recentCaptions newest-last captions of the current session
 * @property statusMessage short human readable status line
 * @property lastError last error reported by the pipeline, if any
 * @property isOverlayVisible whether the floating overlay is currently attached
 */
/**
 * Summary of a freshly finished session so the UI can prompt the user to name & save it.
 */
data class CompletedSessionPrompt(
    val sessionId: Long,
    val defaultTitle: String,
    val segmentCount: Int,
    val durationMs: Long,
)

data class CaptionServiceState(
    val isRunning: Boolean = false,
    val isInitializing: Boolean = false,
    val isStopping: Boolean = false,
    val isPaused: Boolean = false,
    val audioSource: AudioSourceType? = null,
    val activeSessionId: Long = 0L,
    val audioLevel: Float = 0f,
    val recentCaptions: List<LiveCaption> = emptyList(),
    val statusMessage: String = "",
    val lastError: String? = null,
    val isOverlayVisible: Boolean = false,
    val completedSession: CompletedSessionPrompt? = null,
)

/**
 * Foreground service that owns one caption session end to end.
 *
 * It creates the session row, wires [AudioCaptureSource] -> [TranscriptionPipeline]
 * (VAD, sherpa-onnx ASR and optional diarization), mirrors settings and captions into
 * the [FloatingOverlayManager] and persists `endedAt` when the session stops.
 *
 * Use the companion helpers ([startMicrophone], [startPlaybackCapture], [stop],
 * [togglePause], [toggleOverlay]) instead of building intents by hand. UI code can
 * observe [ServiceStateHolder.state].
 */
class CaptionForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Scope for the transcription pipeline. It inherits the service job (so it is
     * cancelled with the service) but runs on [Dispatchers.Default], keeping VAD and
     * segment persistence off the main thread.
     */
    private val pipelineScope = CoroutineScope(
        SupervisorJob(serviceScope.coroutineContext[Job]) + Dispatchers.Default,
    )

    private val sessionJobs = mutableListOf<Job>()

    private lateinit var app: LocalCaptionApp

    private var pipeline: TranscriptionPipeline? = null
    private var captureSource: ControllableAudioCaptureSource? = null
    private var overlay: FloatingOverlayManager? = null
    private var mediaProjection: MediaProjection? = null
    private var startJob: Job? = null

    private var settings: AppSettings = AppSettings.DEFAULTS
    private var runningSource: AudioSourceType? = null
    private var activeSessionId: Long = 0L
    private var activeSessionTitle: String = ""
    private var activeSessionStartedAt: Long = 0L

    private var starting = false
    private var stopping = false
    private var stopRequested = false
    private var foregroundStarted = false
    private var autoShowOverlayRequested = false

    override fun onCreate() {
        super.onCreate()
        app = LocalCaptionApp.from(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        when (intent.action) {
            ACTION_START_MICROPHONE -> {
                autoShowOverlayRequested = intent.getBooleanExtra(EXTRA_AUTO_SHOW_OVERLAY, false)
                handleStart(
                    AudioSourceType.MICROPHONE,
                    Activity.RESULT_CANCELED,
                    null,
                )
            }

            ACTION_START_PLAYBACK_CAPTURE -> {
                autoShowOverlayRequested = intent.getBooleanExtra(EXTRA_AUTO_SHOW_OVERLAY, false)
                handleStart(
                    AudioSourceType.PLAYBACK_CAPTURE,
                    intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED),
                    intent.projectionData(),
                )
            }

            ACTION_STOP -> requestStop(
                promptSaveDialog = intent.getBooleanExtra(EXTRA_PROMPT_SAVE_DIALOG, false),
            )
            ACTION_TOGGLE_PAUSE -> togglePauseInternal()
            ACTION_TOGGLE_OVERLAY -> toggleOverlayInternal()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()

        overlay?.release()
        overlay = null

        val pendingPipeline = pipeline
        val pendingCapture = captureSource
        val pendingSession = activeSessionId
        val pendingProjection = mediaProjection
        pipeline = null
        captureSource = null
        activeSessionId = 0L
        mediaProjection = null
        runningSource = null
        starting = false

        if (pendingPipeline != null || pendingCapture != null || pendingSession != 0L) {
            // The system can destroy the service at any moment; finish the session and
            // release native/audio resources without blocking the main thread.
            Thread(
                {
                    runCatching { pendingPipeline?.stop() }
                    runCatching { pendingPipeline?.close() }
                    runCatching { pendingCapture?.stop() }
                    if (pendingSession != 0L) {
                        runCatching {
                            runBlocking {
                                app.transcriptionRepository.endSession(pendingSession)
                            }
                        }
                    }
                    pendingProjection?.let { runCatching { it.stop() } }
                },
                "CaptionServiceCleanup",
            ).apply { isDaemon = true }.start()
        }

        ServiceStateHolder.reset(
            completedSession = ServiceStateHolder.state.value.completedSession,
        )
        requestTileListeningState()
        serviceScope.cancel()
        super.onDestroy()
    }

    // region Session lifecycle

    private fun handleStart(
        source: AudioSourceType,
        resultCode: Int,
        projectionData: Intent?,
    ) {
        if (isSessionActive()) {
            startInForeground(runningSource ?: source)
            return
        }

        // MediaProjection foreground services must be promoted before the projection
        // is created from the consent token (Android 14 requirement).
        startInForeground(source)

        if (source == AudioSourceType.PLAYBACK_CAPTURE &&
            (resultCode != Activity.RESULT_OK || projectionData == null)
        ) {
            failStart(getString(R.string.error_missing_projection_permission))
            return
        }
        beginSession(source, resultCode, projectionData)
    }

    private fun isSessionActive(): Boolean =
        pipeline != null || starting || stopping || startJob?.isActive == true

    private fun beginSession(
        source: AudioSourceType,
        resultCode: Int,
        projectionData: Intent?,
    ) {
        if (starting || stopping) return
        starting = true
        stopRequested = false
        runningSource = source
        publishState {
            CaptionServiceState(
                isInitializing = true,
                audioSource = source,
                statusMessage = "正在加载模型，请稍候…",
            )
        }

        startJob = serviceScope.launch {
            var sessionId = 0L
            var createdPipeline: TranscriptionPipeline? = null
            var createdCapture: ControllableAudioCaptureSource? = null
            var createdProjection: MediaProjection? = null
            var success = false
            try {
                val currentSettings = app.settingsRepository.settings.first()
                settings = currentSettings
                if (stopRequested) return@launch

                if (source == AudioSourceType.PLAYBACK_CAPTURE) {
                    createdProjection = obtainProjection(resultCode, projectionData)
                    if (createdProjection == null) {
                        throw IllegalStateException(
                            getString(R.string.error_missing_projection_permission),
                        )
                    }
                    mediaProjection = createdProjection
                }

                val sessionTitle = buildSessionTitle(source)
                val startedAt = System.currentTimeMillis()
                sessionId = app.transcriptionRepository.createSession(
                    title = sessionTitle,
                    audioSource = source,
                    asrModel = currentSettings.asrModel,
                    language = currentSettings.language,
                    startedAt = startedAt,
                )
                activeSessionId = sessionId
                activeSessionTitle = sessionTitle
                activeSessionStartedAt = startedAt
                publishState { copy(activeSessionId = sessionId) }

                val capture = ControllableAudioCaptureSource(
                    if (createdProjection != null) {
                        PlaybackAudioCapture(createdProjection)
                    } else {
                        MicAudioCapture()
                    },
                )
                createdCapture = capture
                captureSource = capture

                if (stopRequested) return@launch

                val wavFile = SessionWavRecorder.fileForSession(
                    this@CaptionForegroundService,
                    sessionId,
                )
                app.transcriptionRepository.updateSessionAudioPath(sessionId, wavFile.absolutePath)

                val pipelineSettings = currentSettings.copy(audioSource = source)
                val created = withContext(Dispatchers.Default) {
                    // Builds AudioCaptureSource -> VadSegmenter -> SherpaAsrEngine
                    // (+ optional SpeakerDiarizer) wired into a TranscriptionPipeline.
                    TranscriptionPipeline.create(
                        repository = app.transcriptionRepository,
                        settings = pipelineSettings,
                        audioCaptureSource = capture,
                        modelManager = app.modelManager,
                        wavOutputFile = wavFile,
                        parentScope = pipelineScope,
                    )
                }
                createdPipeline = created
                if (stopRequested) return@launch

                pipeline = created
                observePipeline(created)
                observeSettings()
                if (!autoShowOverlayRequested ||
                    Settings.canDrawOverlays(this@CaptionForegroundService)
                ) {
                    showOverlay()
                    overlay?.updateTitle("加载模型中…")
                }

                // Pre-load the offline ASR model into memory before starting capture so
                // the user gets a clear "loading" signal and the first sentence does not stall.
                publishState { copy(isInitializing = true, statusMessage = "正在加载模型，请稍候…") }
                withContext(Dispatchers.Default) { created.warmUp() }
                if (stopRequested) return@launch

                val started = withContext(Dispatchers.Default) { created.start(sessionId) }
                if (!started || stopRequested) {
                    success = false
                    publishState {
                        copy(
                            isRunning = false,
                            isInitializing = false,
                            statusMessage = created.lastError.value
                                ?: "无法启动音频采集，请检查权限",
                            lastError = created.lastError.value,
                        )
                    }
                    return@launch
                }

                success = true
                overlay?.updateTitle("实时字幕")
                publishState {
                    copy(
                        isRunning = true,
                        isInitializing = false,
                        isPaused = false,
                        statusMessage = runningStatus(source),
                    )
                }
                updateNotification()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Log.e(TAG, "Unable to start the caption session", throwable)
                val message = throwable.message ?: throwable.javaClass.simpleName
                publishState {
                    copy(
                        isRunning = false,
                        isInitializing = false,
                        lastError = message,
                        statusMessage = "启动失败：$message",
                    )
                }
            } finally {
                starting = false
                startJob = null
                if (!success) {
                    withContext(NonCancellable) {
                        cleanupSession(
                            createdPipeline,
                            createdCapture,
                            sessionId,
                            createdProjection,
                        )
                    }
                    if (pipeline === createdPipeline) {
                        pipeline = null
                        sessionJobs.forEach { it.cancel() }
                        sessionJobs.clear()
                    }
                    if (captureSource === createdCapture) captureSource = null
                    if (mediaProjection === createdProjection) mediaProjection = null
                    if (activeSessionId == sessionId) activeSessionId = 0L
                    runningSource = null
                    removeOverlay()
                    stopRequested = false
                    publishState {
                        copy(
                            isRunning = false,
                            isInitializing = false,
                            isStopping = false,
                            isPaused = false,
                            audioLevel = 0f,
                            activeSessionId = 0L,
                        )
                    }
                    stopForegroundAndSelf()
                }
            }
        }
    }

    private fun requestStop(promptSaveDialog: Boolean = false) {
        if (stopping) return
        if (pipeline == null && !starting) {
            sessionJobs.forEach { it.cancel() }
            sessionJobs.clear()
            removeOverlay()
            ServiceStateHolder.reset(
                completedSession = ServiceStateHolder.state.value.completedSession,
            )
            stopForegroundAndSelf()
            return
        }

        stopRequested = true
        if (starting) {
            publishState { copy(isStopping = true, audioLevel = 0f, statusMessage = "正在取消启动…") }
            return
        }

        stopping = true
        val currentPipeline = pipeline
        val currentCapture = captureSource
        val currentSessionId = activeSessionId
        val currentSessionTitle = activeSessionTitle
        val currentStartedAt = activeSessionStartedAt
        val currentProjection = mediaProjection

        // Keep overlay & pipeline observers alive while buffered speech finishes transcribing!
        overlay?.updateTitle("正在识别剩余语音…")
        overlay?.updateAudioLevel(0f)
        publishState {
            copy(
                isStopping = true,
                isInitializing = false,
                audioLevel = 0f,
                statusMessage = "正在识别剩余语音，请稍候…",
            )
        }

        serviceScope.launch {
            cleanupSession(currentPipeline, currentCapture, currentSessionId, currentProjection)

            val segmentCount = if (promptSaveDialog && currentSessionId != 0L) {
                runCatching { app.transcriptionRepository.segmentCount(currentSessionId) }
                    .getOrDefault(0)
            } else {
                0
            }
            val durationMs = if (currentStartedAt > 0L) {
                (System.currentTimeMillis() - currentStartedAt).coerceAtLeast(0L)
            } else {
                0L
            }

            pipeline = null
            captureSource = null
            activeSessionId = 0L
            activeSessionTitle = ""
            activeSessionStartedAt = 0L
            mediaProjection = null
            runningSource = null
            stopping = false
            stopRequested = false

            sessionJobs.forEach { it.cancel() }
            sessionJobs.clear()
            removeOverlay()

            val prompt = if (promptSaveDialog && currentSessionId != 0L) {
                CompletedSessionPrompt(
                    sessionId = currentSessionId,
                    defaultTitle = currentSessionTitle.ifBlank { "实时字幕记录" },
                    segmentCount = segmentCount,
                    durationMs = durationMs,
                )
            } else {
                null
            }
            ServiceStateHolder.reset(completedSession = prompt)
            stopForegroundAndSelf()
        }
    }

    /**
     * Stops capture, lets the pipeline flush its final segment, closes native models
     * and marks the session as ended.
     */
    private suspend fun cleanupSession(
        pipelineToClose: TranscriptionPipeline?,
        capture: AudioCaptureSource?,
        sessionId: Long,
        projection: MediaProjection?,
    ) {
        withContext(Dispatchers.Default) {
            runCatching { pipelineToClose?.stop() }
            runCatching { pipelineToClose?.awaitStopped() }
            runCatching { pipelineToClose?.close() }
            runCatching { capture?.stop() }
            if (sessionId != 0L) {
                runCatching { app.transcriptionRepository.endSession(sessionId) }
                    .onFailure { Log.w(TAG, "Unable to persist session end", it) }
            }
        }
        projection?.let { runCatching { it.stop() } }
    }

    private fun failStart(message: String) {
        Log.w(TAG, "Caption session could not start: $message")
        publishState {
            CaptionServiceState(
                lastError = message,
                statusMessage = message,
            )
        }
        removeOverlay()
        stopForegroundAndSelf()
    }

    // endregion

    // region Observation

    private fun observePipeline(pipeline: TranscriptionPipeline) {
        sessionJobs += serviceScope.launch {
            pipeline.recentCaptions.collect { captions ->
                overlay?.updateCaptions(captions)
                if (isSessionActive()) {
                    publishState { copy(recentCaptions = captions) }
                    updateNotification()
                }
            }
        }
        sessionJobs += serviceScope.launch {
            pipeline.lastError.collect { error ->
                if (error != null) {
                    publishState { copy(lastError = error, statusMessage = error) }
                }
            }
        }
        sessionJobs += serviceScope.launch {
            pipeline.audioLevel.collect { level ->
                val effective = if (stopping || ServiceStateHolder.state.value.isPaused) 0f else level
                overlay?.updateAudioLevel(effective)
                if (isSessionActive() && !stopping) {
                    publishState { copy(audioLevel = effective) }
                }
            }
        }
        sessionJobs += serviceScope.launch {
            pipeline.state.collect { state ->
                when (state) {
                    TranscriptionState.STARTING ->
                        publishState { copy(isRunning = false, statusMessage = "正在启动采集…") }

                    TranscriptionState.RUNNING ->
                        publishState {
                            copy(
                                isRunning = true,
                                isInitializing = false,
                                statusMessage = runningStatus(runningSource ?: AudioSourceType.MICROPHONE),
                            )
                        }

                    TranscriptionState.STOPPING ->
                        publishState {
                            copy(
                                isStopping = true,
                                audioLevel = 0f,
                                statusMessage = "正在识别剩余语音，请稍候…",
                            )
                        }

                    TranscriptionState.STOPPED ->
                        publishState { copy(isRunning = false, audioLevel = 0f) }

                    TranscriptionState.ERROR ->
                        publishState { copy(isRunning = false, isInitializing = false, audioLevel = 0f) }

                    TranscriptionState.IDLE -> Unit
                }
            }
        }
    }

    private fun observeSettings() {
        sessionJobs += serviceScope.launch {
            app.settingsRepository.settings.collect { latest ->
                settings = latest
                overlay?.updateSettings(latest)
            }
        }
    }

    // endregion

    // region Pause / overlay

    private fun togglePauseInternal() {
        if (pipeline == null && !starting) {
            stopSelf()
            return
        }
        val activePipeline = pipeline ?: return
        val state = activePipeline.state.value
        if (state != TranscriptionState.RUNNING && state != TranscriptionState.STARTING) return
        val capture = captureSource ?: return

        val currentlyPaused = ServiceStateHolder.state.value.isPaused
        serviceScope.launch {
            val changed = withContext(Dispatchers.Default) {
                if (currentlyPaused) capture.resume() else capture.pause()
            }
            if (!changed) {
                publishState {
                    copy(lastError = "无法切换采集状态", statusMessage = "无法切换采集状态")
                }
                return@launch
            }
            val nowPaused = !currentlyPaused
            overlay?.updatePlaybackState(nowPaused)
            publishState {
                copy(
                    isPaused = nowPaused,
                    statusMessage = if (nowPaused) "已暂停" else "正在识别",
                )
            }
            updateNotification()
        }
    }

    private fun toggleOverlayInternal() {
        if (pipeline == null && !starting) {
            stopSelf()
            return
        }
        val manager = overlay
        if (manager != null && manager.isShowing) {
            manager.hide()
            publishState { copy(isOverlayVisible = false) }
            return
        }
        showOverlay()
    }

    private fun showOverlay() {
        val manager = overlay ?: FloatingOverlayManager(
            context = this,
            onTogglePause = { togglePauseInternal() },
            onStop = { requestStop() },
        ).also { overlay = it }

        val captions = pipeline?.recentCaptions?.value.orEmpty()
        manager.updateSettings(settings)
        manager.updatePlaybackState(ServiceStateHolder.state.value.isPaused)
        manager.updateCaptions(captions)

        val visible = manager.show()
        publishState {
            copy(
                isOverlayVisible = visible,
                statusMessage = if (visible) {
                    statusMessage.ifBlank { runningStatus(runningSource ?: AudioSourceType.MICROPHONE) }
                } else {
                    "未授予悬浮窗权限，字幕不会悬浮显示"
                },
            )
        }
    }

    private fun removeOverlay() {
        overlay?.hide()
        publishState { copy(isOverlayVisible = false) }
    }

    // endregion

    // region Foreground notification

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_caption),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_caption_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun startInForeground(
        source: AudioSourceType,
        statusText: String = startingStatus(source),
    ) {
        val serviceType = if (source == AudioSourceType.PLAYBACK_CAPTURE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(statusText),
                serviceType,
            )
            foregroundStarted = true
            requestTileListeningState()
        }.onFailure { throwable ->
            Log.e(TAG, "Unable to enter the foreground", throwable)
            publishState {
                copy(
                    lastError = throwable.message ?: throwable.javaClass.simpleName,
                    statusMessage = "无法启动前台服务：${throwable.message ?: "未知错误"}",
                )
            }
        }
    }

    private fun updateNotification() {
        if (!foregroundStarted) return
        val snapshot = ServiceStateHolder.state.value
        val source = snapshot.audioSource ?: return
        val text = when {
            snapshot.isPaused -> "${sourceLabel(source)} · 已暂停"
            snapshot.recentCaptions.isNotEmpty() ->
                "${sourceLabel(source)} · ${snapshot.recentCaptions.size} 条字幕"

            else -> runningStatus(source)
        }
        runCatching {
            NotificationManagerCompat.from(this).notify(
                NOTIFICATION_ID,
                buildNotification(text),
            )
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notification_title_caption))
            .setContentText(statusText)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0,
                getString(R.string.notification_action_stop),
                stopPendingIntent(),
            )
        contentPendingIntent()?.let(builder::setContentIntent)
        return builder.build()
    }

    private fun contentPendingIntent(): PendingIntent? = runCatching {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val resolved = packageManager.resolveActivity(
            launchIntent,
            PackageManager.MATCH_DEFAULT_ONLY,
        ) ?: return null
        // Keep the notification clickable only while the launcher activity actually
        // exists on the classpath (the UI is added in a later stage).
        val activityName = resolved.activityInfo?.name ?: return null
        runCatching { Class.forName(activityName, false, classLoader) }
            .getOrNull() ?: return null
        PendingIntent.getActivity(
            this,
            REQUEST_CONTENT,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }.getOrNull()

    private fun stopPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        REQUEST_STOP,
        Intent(this, CaptionForegroundService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopForegroundAndSelf() {
        if (foregroundStarted) {
            runCatching {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
            foregroundStarted = false
        }
        requestTileListeningState()
        stopSelf()
    }

    /**
     * Asks the Quick Settings tile to re-bind so it immediately reflects the new
     * ACTIVE/INACTIVE state and subtitle after this service starts or stops.
     */
    private fun requestTileListeningState() {
        runCatching {
            TileService.requestListeningState(
                this,
                ComponentName(this, CaptionTileService::class.java),
            )
        }
    }

    // endregion

    // region Helpers

    private fun obtainProjection(resultCode: Int, data: Intent?): MediaProjection? {
        if (resultCode != Activity.RESULT_OK || data == null) return null
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return null
        return runCatching { manager.getMediaProjection(resultCode, data) }
            .onFailure { Log.e(TAG, "Unable to create the MediaProjection", it) }
            .getOrNull()
    }

    private fun buildSessionTitle(source: AudioSourceType): String {
        val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        return "${sourceLabel(source)} · $stamp"
    }

    private fun sourceLabel(source: AudioSourceType): String =
        if (source == AudioSourceType.PLAYBACK_CAPTURE) "播放声音" else "麦克风"

    private fun startingStatus(source: AudioSourceType): String =
        "${sourceLabel(source)} · 正在启动…"

    private fun runningStatus(source: AudioSourceType): String =
        "${sourceLabel(source)} · 正在识别"

    private fun publishState(transform: CaptionServiceState.() -> CaptionServiceState) {
        ServiceStateHolder.update { current -> current.transform() }
    }

    @Suppress("DEPRECATION")
    private fun Intent.projectionData(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            getParcelableExtra(EXTRA_PROJECTION_DATA)
        }

    // endregion

    companion object {
        private const val TAG = "CaptionService"

        private const val CHANNEL_ID = "caption_service"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_CONTENT = 2001
        private const val REQUEST_STOP = 2002

        const val ACTION_START_MICROPHONE = "com.localcaption.app.action.START_MICROPHONE"
        const val ACTION_START_PLAYBACK_CAPTURE =
            "com.localcaption.app.action.START_PLAYBACK_CAPTURE"
        const val ACTION_STOP = "com.localcaption.app.action.STOP"
        const val ACTION_TOGGLE_PAUSE = "com.localcaption.app.action.TOGGLE_PAUSE"
        const val ACTION_TOGGLE_OVERLAY = "com.localcaption.app.action.TOGGLE_OVERLAY"

        private const val EXTRA_RESULT_CODE = "com.localcaption.app.extra.RESULT_CODE"
        private const val EXTRA_PROJECTION_DATA = "com.localcaption.app.extra.PROJECTION_DATA"
        private const val EXTRA_AUTO_SHOW_OVERLAY =
            "com.localcaption.app.extra.AUTO_SHOW_OVERLAY"
        private const val EXTRA_PROMPT_SAVE_DIALOG =
            "com.localcaption.app.extra.PROMPT_SAVE_DIALOG"

        /**
         * Process-wide observable state of the caption service.
         *
         * The singleton lives in the process, so it survives service destruction and
         * can be collected by the UI even before the service starts.
         */
        object ServiceStateHolder {

            private val _state = MutableStateFlow(CaptionServiceState())

            /** Current service snapshot. */
            val state: StateFlow<CaptionServiceState> = _state.asStateFlow()

            /** Applies [transform] to the current snapshot. */
            fun update(transform: (CaptionServiceState) -> CaptionServiceState) {
                _state.update(transform)
            }

            /** Restores the idle snapshot, optionally carrying a completed-session save prompt. */
            fun reset(completedSession: CompletedSessionPrompt? = null) {
                _state.value = CaptionServiceState(completedSession = completedSession)
            }

            /** Clears the post-stop save/rename dialog prompt once the user has handled it. */
            fun dismissCompletedPrompt() {
                _state.update { it.copy(completedSession = null) }
            }
        }

        /**
         * Starts a microphone caption session (requires `RECORD_AUDIO`).
         *
         * @param autoShowOverlay when `true`, the floating overlay is only attached if
         * the overlay permission is already granted (used by the QS tile/shortcut)
         */
        fun startMicrophone(context: Context, autoShowOverlay: Boolean = false) {
            val intent = Intent(context, CaptionForegroundService::class.java)
                .setAction(ACTION_START_MICROPHONE)
                .putExtra(EXTRA_AUTO_SHOW_OVERLAY, autoShowOverlay)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.e(TAG, "Unable to start the microphone service", it) }
        }

        /**
         * Starts a playback capture session from the consent result of
         * `MediaProjectionManager.createScreenCaptureIntent()`.
         */
        fun startPlaybackCapture(
            context: Context,
            resultCode: Int,
            data: Intent,
            autoShowOverlay: Boolean = false,
        ) {
            val intent = Intent(context, CaptionForegroundService::class.java)
                .setAction(ACTION_START_PLAYBACK_CAPTURE)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_PROJECTION_DATA, data)
                .putExtra(EXTRA_AUTO_SHOW_OVERLAY, autoShowOverlay)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.e(TAG, "Unable to start the playback capture service", it) }
        }

        /**
         * Stops the running session and the service.
         *
         * @param promptSaveDialog only `true` when stopped from the main LiveScreen button;
         * stops from the floating overlay, notification or Quick Settings tile save silently
         * with the default title.
         */
        fun stop(context: Context, promptSaveDialog: Boolean = false) {
            val intent = Intent(context, CaptionForegroundService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_PROMPT_SAVE_DIALOG, promptSaveDialog)
            runCatching { context.startService(intent) }
                .onFailure {
                    Log.w(TAG, "Unable to send $ACTION_STOP", it)
                    runCatching {
                        context.stopService(Intent(context, CaptionForegroundService::class.java))
                    }
                }
        }

        /** Pauses or resumes audio capture without ending the session. */
        fun togglePause(context: Context) {
            sendCommand(context, ACTION_TOGGLE_PAUSE)
        }

        /** Shows or hides the floating caption overlay. */
        fun toggleOverlay(context: Context) {
            sendCommand(context, ACTION_TOGGLE_OVERLAY)
        }

        private fun sendCommand(
            context: Context,
            action: String,
            stopServiceOnFailure: Boolean = false,
        ) {
            val intent = Intent(context, CaptionForegroundService::class.java).setAction(action)
            val delivered = runCatching { context.startService(intent) }.isSuccess
            if (!delivered && stopServiceOnFailure) {
                runCatching {
                    context.stopService(Intent(context, CaptionForegroundService::class.java))
                }
            }
        }
    }
}

/**
 * [AudioCaptureSource] wrapper that lets the service suspend and resume capture while
 * keeping the pipeline (and its VAD state) open, so timestamps continue seamlessly
 * across a pause instead of restarting at zero.
 */
private class ControllableAudioCaptureSource(
    private val delegate: AudioCaptureSource,
) : AudioCaptureSource {

    private val lock = Any()

    private var callback: ((FloatArray) -> Unit)? = null
    private var paused = false

    override val sampleRate: Int
        get() = delegate.sampleRate

    override val isCapturing: Boolean
        get() = synchronized(lock) { !paused && callback != null } && delegate.isCapturing

    override fun start(onSamples: (FloatArray) -> Unit): Boolean {
        synchronized(lock) {
            callback = onSamples
            paused = false
        }
        return delegate.start(onSamples)
    }

    override fun stop() {
        synchronized(lock) {
            callback = null
            paused = false
        }
        delegate.stop()
    }

    /** Suspends capture, keeping the pipeline's channel and VAD state untouched. */
    fun pause(): Boolean {
        synchronized(lock) {
            if (callback == null || paused) return false
            paused = true
        }
        delegate.stop()
        return true
    }

    /** Resumes capture with the callback captured by the last [start]. */
    fun resume(): Boolean {
        val onSamples = synchronized(lock) {
            val stored = callback
            if (stored == null || !paused) {
                null
            } else {
                paused = false
                stored
            }
        } ?: return false
        val started = delegate.start(onSamples)
        if (!started) {
            synchronized(lock) { paused = true }
        }
        return started
    }
}
