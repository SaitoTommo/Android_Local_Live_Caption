package com.localcaption.app.asr

import android.util.Log
import com.localcaption.app.audio.AudioCaptureSource
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One detected stretch of speech, in session-relative time.
 *
 * @property samples mono PCM float samples at 16 kHz
 * @property startMs segment start relative to the start of the capture session
 * @property endMs segment end relative to the start of the capture session
 */
class SpeechSegment(
    val samples: FloatArray,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long
        get() = endMs - startMs

    val isEmpty: Boolean
        get() = samples.isEmpty() || durationMs <= 0L

    override fun toString(): String =
        "SpeechSegment(samples=${samples.size}, startMs=$startMs, endMs=$endMs)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpeechSegment) return false
        return startMs == other.startMs &&
            endMs == other.endMs &&
            samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + startMs.hashCode()
        result = 31 * result + endMs.hashCode()
        return result
    }
}

/**
 * Splits a continuous 16 kHz audio stream into [SpeechSegment]s.
 *
 * Silero VAD (through [SherpaOnnxBridge], i.e. [SherpaVadHandle]) is used when the
 * model is installed; otherwise a small energy/RMS VAD keeps the pipeline functional
 * on a fresh install. Both paths honour [vadSilenceMs] (trailing silence that closes
 * a segment) and [maxSegmentDurationMs] (hard segment limit).
 */
class VadSegmenter(
    private val sampleRate: Int = AudioCaptureSource.SAMPLE_RATE,
    private val vadSilenceMs: Int = 600,
    private val maxSegmentDurationMs: Int = 10_000,
    private val minSpeechDurationMs: Int = 250,
    private val energyThreshold: Float = 0.012f,
    private val vadHandle: SherpaVadHandle? = null,
) : AutoCloseable {

    private val maxSamplesPerSegment: Int =
        ((sampleRate.toLong() * maxSegmentDurationMs) / 1000L)
            .toInt()
            .coerceAtLeast(sampleRate / 2)

    private val silenceSamplesThreshold: Int =
        ((sampleRate.toLong() * vadSilenceMs) / 1000L)
            .toInt()
            .coerceAtLeast(1)

    /** Amount of trailing silence kept after a segment ends (100 ms). */
    private val tailKeepSamples: Int = sampleRate / 10

    private val frameSize: Int = max(sampleRate / 50, 80) // 20 ms
    private val frame = FloatArray(frameSize)
    private var frameFill = 0

    private val pending = FloatSampleBuffer(max(sampleRate, min(maxSamplesPerSegment, sampleRate * 2)))
    private var segmentStartSample = -1L
    private var silenceSamples = 0
    private var processedSamples = 0L

    private var sileroProcessedSamples = 0L
    private var sileroFailureLogged = false

    /** `true` when the Silero model drove segmentation for this instance. */
    val usingSilero: Boolean
        get() = vadHandle != null

    /**
     * Feeds [samples] into the segmenter and returns every segment that finished
     * during this call.
     */
    fun process(samples: FloatArray): List<SpeechSegment> {
        if (samples.isEmpty()) return emptyList()
        val handle = vadHandle
        return if (handle != null) {
            processSilero(handle, samples)
        } else {
            processEnergy(samples)
        }
    }

    /**
     * Flushes the in-flight segment (and any buffered VAD result) at the end of a
     * capture session.
     */
    fun flush(): List<SpeechSegment> {
        val out = mutableListOf<SpeechSegment>()
        val handle = vadHandle
        if (handle != null) {
            // A one second silence tail makes Silero VAD close an in-flight segment.
            val tail = FloatArray(sampleRate)
            runCatching { handle.accept(tail) }
                .onFailure { logSileroFailure(it) }
            sileroProcessedSamples += tail.size
            out += drainSilero(handle, sileroProcessedSamples)
            return out
        }

        if (segmentStartSample >= 0L) {
            if (frameFill > 0) {
                pending.add(frame, 0, frameFill)
                processedSamples += frameFill
            }
            emitSegment(out, segmentStartSample, processedSamples, 0)
        }
        frameFill = 0
        return out
    }

    /** Clears all buffered state. Call before starting a new capture session. */
    fun reset() {
        frameFill = 0
        pending.clear()
        segmentStartSample = -1L
        silenceSamples = 0
        processedSamples = 0L
        sileroProcessedSamples = 0L
        sileroFailureLogged = false
        runCatching { vadHandle?.reset() }
    }

    override fun close() {
        runCatching { vadHandle?.close() }
    }

    // region Silero VAD

    private fun processSilero(handle: SherpaVadHandle, samples: FloatArray): List<SpeechSegment> {
        val accepted = runCatching { handle.accept(samples) }
            .onFailure { logSileroFailure(it) }
            .isSuccess
        if (accepted) {
            sileroProcessedSamples += samples.size
        }
        return drainSilero(handle, sileroProcessedSamples)
    }

    private fun drainSilero(
        handle: SherpaVadHandle,
        endSample: Long,
    ): List<SpeechSegment> {
        val out = mutableListOf<SpeechSegment>()
        var drained = 0
        while (drained < MAX_SEGMENTS_PER_DRAIN && handle.hasSegment()) {
            val samples = handle.popSegment() ?: break
            drained++
            appendSileroSegment(out, samples, endSample)
        }
        return out
    }

    private fun appendSileroSegment(
        out: MutableList<SpeechSegment>,
        samples: FloatArray,
        endSample: Long,
    ) {
        if (samples.isEmpty()) return
        var offset = 0
        while (offset < samples.size) {
            val length = min(maxSamplesPerSegment, samples.size - offset)
            val piece = if (offset == 0 && length == samples.size) {
                samples
            } else {
                samples.copyOfRange(offset, offset + length)
            }
            val pieceEnd = endSample - (samples.size - (offset + length))
            val pieceStart = (pieceEnd - length).coerceAtLeast(0L)
            val startMs = pieceStart * 1000L / sampleRate
            val endMs = pieceEnd * 1000L / sampleRate
            if (endMs - startMs >= minSpeechDurationMs) {
                out += SpeechSegment(piece, startMs, endMs)
            }
            offset += length
        }
    }

    private fun logSileroFailure(throwable: Throwable) {
        if (sileroFailureLogged) return
        sileroFailureLogged = true
        Log.w(TAG, "Silero VAD failed; continuing with buffered VAD output", throwable)
    }

    // endregion

    // region Energy VAD fallback

    private fun processEnergy(samples: FloatArray): List<SpeechSegment> {
        val out = mutableListOf<SpeechSegment>()
        var index = 0
        while (index < samples.size) {
            val take = min(frameSize - frameFill, samples.size - index)
            System.arraycopy(samples, index, frame, frameFill, take)
            frameFill += take
            index += take
            if (frameFill == frameSize) {
                processEnergyFrame(out)
                frameFill = 0
            }
        }
        return out
    }

    private fun processEnergyFrame(out: MutableList<SpeechSegment>) {
        val frameStartSample = processedSamples
        var energy = 0.0
        for (value in frame) {
            energy += value.toDouble() * value.toDouble()
        }
        val rms = sqrt(energy / frame.size).toFloat()
        val voiced = rms >= energyThreshold

        if (segmentStartSample < 0L) {
            if (voiced) {
                segmentStartSample = frameStartSample
                silenceSamples = 0
                pending.clear()
                pending.add(frame)
            }
        } else {
            pending.add(frame)
            if (voiced) {
                silenceSamples = 0
            } else {
                silenceSamples += frameSize
            }

            val durationSamples = processedSamples + frameSize - segmentStartSample
            val reachedMaxDuration = durationSamples >= maxSamplesPerSegment
            val reachedSilence = silenceSamples >= silenceSamplesThreshold

            if (reachedSilence || reachedMaxDuration) {
                val endSample = processedSamples + frameSize
                val trim = if (reachedSilence) {
                    max(silenceSamples - tailKeepSamples, 0)
                } else {
                    0
                }
                emitSegment(out, segmentStartSample, endSample, trim)
            }
        }

        processedSamples += frameSize
    }

    private fun emitSegment(
        out: MutableList<SpeechSegment>,
        startSample: Long,
        endSample: Long,
        trimSamples: Int,
    ) {
        val actualEndSample = (endSample - trimSamples).coerceAtLeast(startSample)
        val sampleCount = (pending.size - trimSamples).coerceIn(0, pending.size)
        val durationMs = (actualEndSample - startSample) * 1000L / sampleRate

        if (sampleCount > 0 && durationMs >= minSpeechDurationMs) {
            out += SpeechSegment(
                samples = pending.toFloatArray(sampleCount),
                startMs = startSample * 1000L / sampleRate,
                endMs = actualEndSample * 1000L / sampleRate,
            )
        }

        pending.clear()
        segmentStartSample = -1L
        silenceSamples = 0
    }

    // endregion

    private companion object {
        const val TAG = "VadSegmenter"
        const val MAX_SEGMENTS_PER_DRAIN = 32
    }
}

/** Minimal growable float buffer that avoids boxing while accumulating a segment. */
private class FloatSampleBuffer(initialCapacity: Int) {

    private var data = FloatArray(initialCapacity.coerceAtLeast(1))
    var size: Int = 0
        private set

    fun clear() {
        size = 0
    }

    fun add(values: FloatArray) {
        add(values, 0, values.size)
    }

    fun add(values: FloatArray, from: Int, to: Int) {
        val count = to - from
        if (count <= 0) return
        ensureCapacity(size + count)
        System.arraycopy(values, from, data, size, count)
        size += count
    }

    fun toFloatArray(count: Int = size): FloatArray {
        val safeCount = count.coerceIn(0, size)
        return data.copyOf(safeCount)
    }

    private fun ensureCapacity(capacity: Int) {
        if (capacity <= data.size) return
        var newSize = data.size
        while (newSize < capacity) {
            newSize *= 2
        }
        data = data.copyOf(newSize)
    }
}
