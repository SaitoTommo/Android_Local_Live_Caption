package com.localcaption.app.audio

import android.media.AudioFormat
import android.media.AudioRecord
import kotlin.math.max

/**
 * A source of mono 16 kHz PCM audio used by the transcription pipeline.
 *
 * Implementations deliver little-endian PCM-16 audio converted to normalized
 * [FloatArray] samples in the range `[-1f, 1f]`.
 */
interface AudioCaptureSource {

    /** Sample rate of the delivered [FloatArray] chunks. */
    val sampleRate: Int

    /** `true` while audio is being captured. */
    val isCapturing: Boolean

    /**
     * Starts capturing audio and delivers converted samples on a capture thread.
     *
     * @return `true` when the underlying recorder was started successfully.
     */
    fun start(onSamples: (FloatArray) -> Unit): Boolean

    /** Stops capturing and releases the underlying recorder. Safe to call twice. */
    fun stop()

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_COUNT = 1
        const val BYTES_PER_SAMPLE = 2

        /** Size of one delivered chunk (~100 ms). */
        fun chunkSamples(sampleRate: Int): Int = max(sampleRate / 10, 160)
    }
}

/**
 * Shared PCM-16 capture plumbing for [PlaybackAudioCapture] and [MicAudioCapture].
 */
internal object Pcm16Capture {

    /**
     * Returns a byte buffer that comfortably holds a few chunks of mono PCM-16 audio.
     */
    fun bufferSizeBytes(sampleRate: Int): Int {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val chunkBytes = AudioCaptureSource.chunkSamples(sampleRate) * AudioCaptureSource.BYTES_PER_SAMPLE
        return if (minBuffer > 0) {
            max(minBuffer * 2, chunkBytes * 4)
        } else {
            chunkBytes * 4
        }
    }

    /**
     * Blocking read loop. Stops when [isRunning] returns `false`, the recorder is
     * stopped, or a read error occurs.
     */
    fun readLoop(
        record: AudioRecord,
        sampleRate: Int,
        isRunning: () -> Boolean,
        onSamples: (FloatArray) -> Unit,
    ) {
        val chunk = ShortArray(AudioCaptureSource.chunkSamples(sampleRate))
        val floats = FloatArray(chunk.size)
        while (isRunning()) {
            val read = record.read(chunk, 0, chunk.size)
            if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                break
            }
            if (read <= 0) {
                continue
            }
            for (index in 0 until read) {
                floats[index] = chunk[index] / 32768f
            }
            onSamples(if (read == floats.size) floats.copyOf() else floats.copyOf(read))
        }
    }
}
