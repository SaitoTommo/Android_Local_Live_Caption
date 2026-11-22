package com.localcaption.app.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Captures the audio that other apps play back (media, games, unknown usage) through
 * an [AudioPlaybackCaptureConfiguration] backed by a [MediaProjection].
 *
 * Requires API 29+ and an active media projection consent (see
 * `MediaProjectionManager.createScreenCaptureIntent()`). The captured stream is
 * downmixed by the platform to mono PCM-16 at 16 kHz.
 */
class PlaybackAudioCapture(
    private val mediaProjection: MediaProjection,
    override val sampleRate: Int = AudioCaptureSource.SAMPLE_RATE,
    private val bufferSizeBytes: Int = Pcm16Capture.bufferSizeBytes(sampleRate),
) : AudioCaptureSource {

    private val lock = Any()

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var projectionCallback: MediaProjection.Callback? = null

    @Volatile
    private var capturing = false

    override val isCapturing: Boolean
        get() = capturing

    override fun start(onSamples: (FloatArray) -> Unit): Boolean {
        synchronized(lock) {
            if (capturing) return true

            val recorder = createRecorder() ?: return false
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "Playback AudioRecord was not initialized")
                recorder.release()
                return false
            }

            registerProjectionCallback()
            audioRecord = recorder
            capturing = true

            val thread = Thread(
                {
                    try {
                        recorder.startRecording()
                        if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            Pcm16Capture.readLoop(
                                record = recorder,
                                sampleRate = sampleRate,
                                isRunning = { capturing },
                                onSamples = onSamples,
                            )
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Playback capture loop stopped", t)
                    } finally {
                        capturing = false
                        runCatching { recorder.stop() }
                        runCatching { recorder.release() }
                        synchronized(lock) {
                            if (audioRecord === recorder) {
                                audioRecord = null
                            }
                        }
                    }
                },
                "PlaybackAudioCapture",
            )
            captureThread = thread
            thread.isDaemon = true
            thread.start()
            return true
        }
    }

    override fun stop() {
        val thread: Thread?
        val recorder: AudioRecord?
        synchronized(lock) {
            capturing = false
            thread = captureThread
            recorder = audioRecord
            captureThread = null
            audioRecord = null
        }

        // Stopping the recorder unblocks [AudioRecord.read] in the capture thread.
        runCatching { recorder?.stop() }
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(STOP_JOIN_TIMEOUT_MS) }
        }
        runCatching { recorder?.release() }
        unregisterProjectionCallback()
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(): AudioRecord? = try {
        val configuration = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSizeBytes)
            .setAudioPlaybackCaptureConfig(configuration)
            .build()
    } catch (t: Throwable) {
        Log.w(TAG, "Unable to create playback AudioRecord", t)
        null
    }

    private fun registerProjectionCallback() {
        if (projectionCallback != null) return
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                stop()
            }
        }
        projectionCallback = callback
        runCatching {
            mediaProjection.registerCallback(callback, Handler(Looper.getMainLooper()))
        }.onFailure { throwable ->
            Log.w(TAG, "Unable to register MediaProjection callback", throwable)
            projectionCallback = null
        }
    }

    private fun unregisterProjectionCallback() {
        val callback = synchronized(lock) {
            projectionCallback.also { projectionCallback = null }
        } ?: return
        runCatching { mediaProjection.unregisterCallback(callback) }
    }

    private companion object {
        const val TAG = "PlaybackAudioCapture"
        const val STOP_JOIN_TIMEOUT_MS = 500L
    }
}
