package com.localcaption.app.data.model

/**
 * Where the audio that is being transcribed originates from.
 */
enum class AudioSourceType {
    /** Audio played back by other apps, captured through MediaProjection. */
    PLAYBACK_CAPTURE,

    /** Audio captured from the device microphone. */
    MICROPHONE,
}
