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
 * Improvements for natural phrasing & consonant preservation:
 * - Maintains a rolling pre-roll audio ring buffer ([preRollMs], default 350 ms) and
 *   prepends it before speech onset on BOTH Silero VAD and energy-VAD paths so soft
 *   unvoiced leading consonants (`p/t/k/s/sh/f/th/ch`) are never clipped.
 * - When a continuous utterance reaches [maxSegmentDurationMs], searches the trailing
 *   window for a natural acoustic energy valley (breath / micro-pause) instead of slicing
 *   blindly mid-syllable, preserving natural word boundaries.
 */
class VadSegmenter(
    private val sampleRate: Int = AudioCaptureSource.SAMPLE_RATE,
    private val vadSilenceMs: Int = 600,
    private val maxSegmentDurationMs: Int = 10_000,
    private val minSpeechDurationMs: Int = 250,
    private val energyThreshold: Float = 0.012f,
    private val preRollSilenceRatio: Float = DEFAULT_PRE_ROLL_SILENCE_RATIO,
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

    /** Hard cap on pre-roll lookback (180 ms) so long silences never add dead air. */
    private val maxPreRollSamples: Int = ((sampleRate.toLong() * MAX_PRE_ROLL_MS) / 1000L).toInt()
    private val safePreRollRatio: Float = preRollSilenceRatio.coerceIn(0f, 1f)

    /** Amount of trailing silence kept after a segment ends (120 ms). */
    private val tailKeepSamples: Int = (sampleRate * 12) / 100

    private val frameSize: Int = max(sampleRate / 50, 80) // 20 ms
    private val frame = FloatArray(frameSize)
    private var frameFill = 0

    private val pending = FloatSampleBuffer(max(sampleRate, min(maxSamplesPerSegment, sampleRate * 2)))
    private val recentAudio = RecentAudioRingBuffer(sampleRate * 15)

    private var segmentStartSample = -1L
    private var silenceSamples = 0
    private var processedSamples = 0L
    private var lastEmittedEndSample = 0L

    private var sileroProcessedSamples = 0L
    private var sileroContinuousSpeechSamples = 0L
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
        recentAudio.append(samples)
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
            val tail = FloatArray(sampleRate)
            recentAudio.append(tail)
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
        recentAudio.clear()
        segmentStartSample = -1L
        silenceSamples = 0
        processedSamples = 0L
        lastEmittedEndSample = 0L
        sileroProcessedSamples = 0L
        sileroContinuousSpeechSamples = 0L
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
            if (handle.isSpeechDetected()) {
                sileroContinuousSpeechSamples += samples.size
            } else {
                sileroContinuousSpeechSamples = 0L
            }
        }
        val drained = drainSilero(handle, sileroProcessedSamples).toMutableList()
        if (drained.isNotEmpty()) {
            sileroContinuousSpeechSamples = 0L
        } else if (sileroContinuousSpeechSamples >= maxSamplesPerSegment) {
            // BGM watchdog: continuous background music can keep Silero VAD triggered
            // indefinitely; force-flush so subtitles always emit on time.
            runCatching { handle.flush() }.onFailure { logSileroFailure(it) }
            val forced = drainSilero(handle, sileroProcessedSamples)
            drained += forced
            sileroContinuousSpeechSamples = 0L
        }
        return drained
    }

    private fun drainSilero(
        handle: SherpaVadHandle,
        endSample: Long,
    ): List<SpeechSegment> {
        val out = mutableListOf<SpeechSegment>()
        var drained = 0
        while (drained < MAX_SEGMENTS_PER_DRAIN && handle.hasSegment()) {
            val vadSeg = handle.popSegmentWithStart() ?: break
            drained++
            appendSileroSegment(out, vadSeg.samples, vadSeg.startSample, endSample)
        }
        return out
    }

    private fun appendSileroSegment(
        out: MutableList<SpeechSegment>,
        samples: FloatArray,
        reportedStartSample: Long,
        fallbackEndSample: Long,
    ) {
        if (samples.isEmpty()) return

        // Use Silero VAD's exact segment.start sample index so we NEVER shift into the
        // first syllable and duplicate opening characters.
        val rawStartSample = if (reportedStartSample >= 0L) {
            reportedStartSample
        } else {
            (fallbackEndSample - silenceSamplesThreshold - samples.size).coerceAtLeast(0L)
        }
        val rawEndSample = rawStartSample + samples.size

        // Take only a configured percentage of the preceding silence gap (capped at 180 ms),
        // strictly after lastEmittedEndSample so previous words can never leak in.
        val availableGap = (rawStartSample - lastEmittedEndSample).coerceAtLeast(0L)
        val preRollCount = min(
            (availableGap * safePreRollRatio).toLong(),
            maxPreRollSamples.toLong(),
        )
        val preRoll = if (preRollCount > 0L) {
            recentAudio.copyRange(rawStartSample - preRollCount, rawStartSample)
        } else {
            null
        }

        val combined = if (preRoll != null && preRoll.isNotEmpty()) {
            FloatArray(preRoll.size + samples.size).also { merged ->
                System.arraycopy(preRoll, 0, merged, 0, preRoll.size)
                System.arraycopy(samples, 0, merged, preRoll.size, samples.size)
            }
        } else {
            samples
        }
        val combinedStartSample = (rawEndSample - combined.size).coerceAtLeast(0L)

        var offset = 0
        while (offset < combined.size) {
            val remaining = combined.size - offset
            val splitLength = if (remaining <= maxSamplesPerSegment) {
                remaining
            } else {
                findNaturalSplitLength(combined, offset, maxSamplesPerSegment)
            }

            val piece = if (offset == 0 && splitLength == combined.size) {
                combined
            } else {
                combined.copyOfRange(offset, offset + splitLength)
            }
            val pieceStart = combinedStartSample + offset
            val pieceEnd = pieceStart + splitLength
            val startMs = pieceStart * 1000L / sampleRate
            val endMs = pieceEnd * 1000L / sampleRate
            if (endMs - startMs >= minSpeechDurationMs) {
                out += SpeechSegment(piece, startMs, endMs)
                lastEmittedEndSample = max(lastEmittedEndSample, pieceEnd)
            }
            offset += splitLength
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
                pending.clear()
                // Prepend a configured percentage of the preceding silence gap (capped at 180 ms)
                // without overlapping the previous segment.
                val availableGap = (frameStartSample - lastEmittedEndSample).coerceAtLeast(0L)
                val preRollCount = min(
                    (availableGap * safePreRollRatio).toLong(),
                    maxPreRollSamples.toLong(),
                )
                val preRoll = if (preRollCount > 0L) {
                    recentAudio.copyRange(frameStartSample - preRollCount, frameStartSample)
                } else {
                    null
                }
                if (preRoll != null && preRoll.isNotEmpty()) {
                    pending.add(preRoll)
                    segmentStartSample = frameStartSample - preRoll.size
                } else {
                    segmentStartSample = frameStartSample
                }
                silenceSamples = 0
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

            if (reachedSilence) {
                val endSample = processedSamples + frameSize
                val trim = max(silenceSamples - tailKeepSamples, 0)
                emitSegment(out, segmentStartSample, endSample, trim)
            } else if (reachedMaxDuration) {
                val all = pending.toFloatArray()
                val splitLength = findNaturalSplitLength(all, 0, all.size)
                val actualEndSample = segmentStartSample + splitLength
                val durationMs = (actualEndSample - segmentStartSample) * 1000L / sampleRate
                if (splitLength > 0 && durationMs >= minSpeechDurationMs) {
                    out += SpeechSegment(
                        samples = all.copyOfRange(0, splitLength),
                        startMs = segmentStartSample * 1000L / sampleRate,
                        endMs = actualEndSample * 1000L / sampleRate,
                    )
                    lastEmittedEndSample = max(lastEmittedEndSample, actualEndSample)
                }
                pending.clear()
                val leftover = all.size - splitLength
                if (leftover > 0) {
                    pending.add(all, splitLength, all.size)
                    segmentStartSample = actualEndSample
                } else {
                    segmentStartSample = -1L
                }
                silenceSamples = 0
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
            lastEmittedEndSample = max(lastEmittedEndSample, actualEndSample)
        }

        pending.clear()
        segmentStartSample = -1L
        silenceSamples = 0
    }

    /**
     * Looks inside the trailing 1.2 s of `[offset, offset + maxLen]` for a natural
     * acoustic energy valley (breath or inter-word pause). If a clear valley exists
     * (RMS < 65% of local average), splits there instead of chopping mid-word.
     */
    private fun findNaturalSplitLength(
        samples: FloatArray,
        offset: Int,
        maxLen: Int,
    ): Int {
        val searchWindow = min((sampleRate * 12) / 10, maxLen / 3)
        val winStep = max(sampleRate / 50, 160) // 20 ms
        if (searchWindow < winStep * 3) return maxLen

        val searchStart = offset + maxLen - searchWindow
        val searchEnd = offset + maxLen - winStep
        var bestPos = offset + maxLen
        var minRms = Float.MAX_VALUE
        var sumRms = 0f
        var count = 0

        var pos = searchStart
        while (pos <= searchEnd) {
            var e = 0.0
            for (i in pos until pos + winStep) {
                val s = samples[i].toDouble()
                e += s * s
            }
            val rms = sqrt(e / winStep).toFloat()
            sumRms += rms
            count++
            if (rms < minRms) {
                minRms = rms
                bestPos = pos + winStep / 2
            }
            pos += winStep
        }

        val avgRms = if (count > 0) sumRms / count else 0f
        return if (avgRms > 1e-4f && minRms < avgRms * 0.65f) {
            (bestPos - offset).coerceIn(sampleRate / 2, maxLen)
        } else {
            maxLen
        }
    }

    // endregion

    private companion object {
        const val TAG = "VadSegmenter"
        const val MAX_SEGMENTS_PER_DRAIN = 32
        const val DEFAULT_PRE_ROLL_SILENCE_RATIO = 0.30f
        const val MAX_PRE_ROLL_MS = 180
    }
}

/** Fixed-capacity rolling ring buffer indexed by absolute session sample offset. */
private class RecentAudioRingBuffer(capacity: Int) {

    private val buffer = FloatArray(capacity.coerceAtLeast(1))
    private var totalWritten: Long = 0L

    fun clear() {
        totalWritten = 0L
    }

    fun append(samples: FloatArray) {
        val cap = buffer.size
        if (samples.size >= cap) {
            System.arraycopy(samples, samples.size - cap, buffer, 0, cap)
            totalWritten += samples.size
            return
        }
        val startIdx = (totalWritten % cap).toInt()
        val firstPart = min(samples.size, cap - startIdx)
        System.arraycopy(samples, 0, buffer, startIdx, firstPart)
        val secondPart = samples.size - firstPart
        if (secondPart > 0) {
            System.arraycopy(samples, firstPart, buffer, 0, secondPart)
        }
        totalWritten += samples.size
    }

    /** Copies `[fromSample, toSample)` if it is still within the retained ring window. */
    fun copyRange(fromSample: Long, toSample: Long): FloatArray? {
        if (toSample <= fromSample) return null
        val earliestAvailable = (totalWritten - buffer.size).coerceAtLeast(0L)
        val safeFrom = fromSample.coerceAtLeast(earliestAvailable)
        val safeTo = toSample.coerceAtMost(totalWritten)
        val count = (safeTo - safeFrom).toInt()
        if (count <= 0) return null

        val out = FloatArray(count)
        val cap = buffer.size
        val startIdx = (safeFrom % cap).toInt()
        val firstPart = min(count, cap - startIdx)
        System.arraycopy(buffer, startIdx, out, 0, firstPart)
        val secondPart = count - firstPart
        if (secondPart > 0) {
            System.arraycopy(buffer, 0, out, firstPart, secondPart)
        }
        return out
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
