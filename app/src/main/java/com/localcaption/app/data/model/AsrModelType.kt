package com.localcaption.app.data.model

/**
 * The bundled offline ASR model used for a transcription session.
 *
 * @property displayName human readable name shown in the UI
 * @property modelDirName directory name of the model inside the app's model folder
 * @property sampleRate audio sample rate (in Hz) the model expects
 */
enum class AsrModelType(
    val displayName: String,
    val modelDirName: String,
    val sampleRate: Int,
) {
    QWEN3_ASR_0_6B_INT8(
        displayName = "Qwen3-ASR 0.6B (INT8)",
        modelDirName = "sherpa-onnx-qwen3-asr-0.6b-int8",
        sampleRate = 16_000,
    ),

    SENSE_VOICE_SMALL_INT8(
        displayName = "SenseVoice Small (INT8 · 极速多语)",
        modelDirName = "sherpa-onnx-sense-voice-small-int8",
        sampleRate = 16_000,
    ),

    PARAFORMER_ZH_SMALL_INT8(
        displayName = "Paraformer-zh Small (超轻中英)",
        modelDirName = "sherpa-onnx-paraformer-zh-small-int8",
        sampleRate = 16_000,
    ),

    WHISPER_TINY_INT8(
        displayName = "Whisper Tiny (轻量多语)",
        modelDirName = "sherpa-onnx-whisper-tiny-int8",
        sampleRate = 16_000,
    ),
}
