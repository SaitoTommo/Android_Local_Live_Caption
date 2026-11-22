package com.localcaption.app.data.prefs

import com.localcaption.app.data.model.AsrModelType
import com.localcaption.app.data.model.AudioSourceType

/**
 * User-facing settings persisted in DataStore.
 *
 * @property audioSource where subtitles audio comes from (playback capture by default)
 * @property asrModel offline ASR model used for recognition
 * @property language language hint passed to the ASR model, `"auto"` for detection
 * @property showTimestamps whether recorded segments display their time range
 * @property fontSizeSp caption font size in scaled pixels
 * @property numThreads number of inference threads handed to sherpa-onnx
 * @property enableDiarization whether speaker clustering runs on recognized segments
 * @property diarizationThreshold cosine similarity above which a segment joins an
 * existing speaker cluster
 * @property vadSilenceMs trailing silence that closes a speech segment
 * @property maxSegmentDurationMs hard upper bound for a single speech segment
 * @property overlayBgAlpha background opacity of the floating subtitle overlay
 * @property overlayMaxLines maximum number of caption lines shown in the overlay
 * @property exportIncludeSpeaker include the speaker label when exporting
 * @property exportIncludeTimestamps include timestamps when exporting
 */
data class AppSettings(
    val audioSource: AudioSourceType = AudioSourceType.PLAYBACK_CAPTURE,
    val asrModel: AsrModelType = AsrModelType.QWEN3_ASR_0_6B_INT8,
    val language: String = "auto",
    val showTimestamps: Boolean = false,
    val fontSizeSp: Float = 20f,
    val numThreads: Int = 2,
    val executionProvider: String = PROVIDER_CPU,
    val enableDiarization: Boolean = true,
    val diarizationThreshold: Float = 0.58f,
    val vadSilenceMs: Int = 600,
    val preRollSilenceRatio: Float = 0.30f,
    val maxSegmentDurationMs: Int = 10_000,
    val overlayBgAlpha: Float = 0.78f,
    val overlayMaxLines: Int = 3,
    val exportIncludeSpeaker: Boolean = true,
    val exportIncludeTimestamps: Boolean = true,
) {

    /** Trailing silence (in seconds) as expected by Silero VAD. */
    val vadSilenceSeconds: Float
        get() = vadSilenceMs / 1000f

    /** Maximum segment length (in seconds). */
    val maxSegmentSeconds: Float
        get() = maxSegmentDurationMs / 1000f

    companion object {
        const val LANGUAGE_AUTO = "auto"
        const val PROVIDER_CPU = "cpu"
        const val PROVIDER_NNAPI = "nnapi"
        const val MIN_NUM_THREADS = 1
        const val MAX_NUM_THREADS = 8
        const val MIN_VAD_SILENCE_MS = 100
        const val MAX_VAD_SILENCE_MS = 5_000
        const val MIN_SEGMENT_DURATION_MS = 1_000
        const val MAX_SEGMENT_DURATION_MS = 30_000
        const val MIN_OVERLAY_ALPHA = 0.1f
        const val MAX_OVERLAY_ALPHA = 1f
        const val MIN_OVERLAY_LINES = 1
        const val MAX_OVERLAY_LINES = 8

        /** Defaults used when a preference has not been stored yet. */
        val DEFAULTS: AppSettings = AppSettings()
    }
}

/** Clamps [AppSettings] values into their supported ranges. */
fun AppSettings.sanitized(): AppSettings = copy(
    fontSizeSp = fontSizeSp.coerceIn(10f, 96f),
    numThreads = numThreads.coerceIn(
        AppSettings.MIN_NUM_THREADS,
        AppSettings.MAX_NUM_THREADS,
    ),
    executionProvider = if (executionProvider == AppSettings.PROVIDER_NNAPI) {
        AppSettings.PROVIDER_NNAPI
    } else {
        AppSettings.PROVIDER_CPU
    },
    diarizationThreshold = diarizationThreshold.coerceIn(0f, 1f),
    vadSilenceMs = vadSilenceMs.coerceIn(
        AppSettings.MIN_VAD_SILENCE_MS,
        AppSettings.MAX_VAD_SILENCE_MS,
    ),
    preRollSilenceRatio = preRollSilenceRatio.coerceIn(0f, 1f),
    maxSegmentDurationMs = maxSegmentDurationMs.coerceIn(
        AppSettings.MIN_SEGMENT_DURATION_MS,
        AppSettings.MAX_SEGMENT_DURATION_MS,
    ),
    overlayBgAlpha = overlayBgAlpha.coerceIn(
        AppSettings.MIN_OVERLAY_ALPHA,
        AppSettings.MAX_OVERLAY_ALPHA,
    ),
    overlayMaxLines = overlayMaxLines.coerceIn(
        AppSettings.MIN_OVERLAY_LINES,
        AppSettings.MAX_OVERLAY_LINES,
    ),
)
