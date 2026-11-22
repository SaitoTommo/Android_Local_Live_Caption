package com.localcaption.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Captures microphone audio as mono PCM-16 at 16 kHz.
 *
 * [MediaRecorder.AudioSource.VOICE_RECOGNITION] is preferred because it applies
 * noise suppression and disables AGC, which improves ASR accuracy. Recorders that
 * cannot open the preferred source fall back to [MediaRecorder.AudioSource.MIC].
 */
class MicAudioCapture(
    override val sampleRate: Int = AudioCaptureSource.SAMPLE_RATE,
    private val preferredSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
) : AudioCaptureSource {

    private val lock = Any()

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    @Volatile
    private var capturing = false

    override val isCapturing: Boolean
        get() = capturing

    /** Audio source that was actually opened, or `null` before the first start. */
    @Volatile
    var activeAudioSource: Int? = null
        private set

    override fun start(onSamples: (FloatArray) -> Unit): Boolean {
        synchronized(lock) {
            if (capturing) return true

            val opened = openRecorder() ?: return false
            val (recorder, source) = opened
            activeAudioSource = source
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
                        Log.w(TAG, "Microphone capture loop stopped", t)
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
                "MicAudioCapture",
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

        runCatching { recorder?.stop() }
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(STOP_JOIN_TIMEOUT_MS) }
        }
        runCatching { recorder?.release() }
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(): Pair<AudioRecord, Int>? {
        val bufferSize = Pcm16Capture.bufferSizeBytes(sampleRate)
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val sources = if (preferredSource == MediaRecorder.AudioSource.MIC) {
            listOf(MediaRecorder.AudioSource.MIC)
        } else {
            listOf(preferredSource, MediaRecorder.AudioSource.MIC)
        }

        for (source in sources) {
            val recorder = try {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            } catch (t: Throwable) {
                Log.w(TAG, "Unable to create AudioRecord for source $source", t)
                null
            } ?: continue

            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                return recorder to source
            }
            recorder.release()
        }
        return null
    }

    private companion object {
        const val TAG = "MicAudioCapture"
        const val STOP_JOIN_TIMEOUT_MS = 500L
    }
}
