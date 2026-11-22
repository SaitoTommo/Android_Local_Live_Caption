package com.localcaption.app

import com.localcaption.app.asr.VadSegmenter
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the energy based VAD fallback of [VadSegmenter] (the Silero path needs the
 * native sherpa-onnx library and is covered by instrumented tests instead).
 */
class VadSegmenterTest {

    private val sampleRate = 16_000

    private fun segmenter(
        silenceMs: Int = 300,
        maxSegmentMs: Int = 10_000,
    ) = VadSegmenter(
        sampleRate = sampleRate,
        vadSilenceMs = silenceMs,
        maxSegmentDurationMs = maxSegmentMs,
        minSpeechDurationMs = 100,
        energyThreshold = 0.012f,
    )

    private fun tone(durationMs: Int, amplitude: Float = 0.2f): FloatArray {
        val count = sampleRate * durationMs / 1000
        return FloatArray(count) { index ->
            (amplitude * sin(2.0 * PI * 440.0 * index / sampleRate)).toFloat()
        }
    }

    private fun silence(durationMs: Int): FloatArray =
        FloatArray(sampleRate * durationMs / 1000)

    @Test
    fun `emits a segment when speech is followed by silence`() {
        val segmenter = segmenter()

        val first = segmenter.process(tone(500))
        val second = segmenter.process(silence(600))
        val segments = first + second

        assertEquals(1, segments.size)
        val segment = segments.single()
        assertTrue("duration was ${segment.durationMs}", segment.durationMs in 550L..700L)
        assertTrue(segment.samples.isNotEmpty())
        assertEquals(0L, segment.startMs)
        segmenter.close()
    }

    @Test
    fun `does not emit anything for pure silence`() {
        val segmenter = segmenter()

        val segments = segmenter.process(silence(1_500)) + segmenter.flush()

        assertTrue(segments.isEmpty())
        segmenter.close()
    }

    @Test
    fun `splits long speech at the maximum segment duration`() {
        val segmenter = segmenter(maxSegmentMs = 2_000)

        val duringStream = segmenter.process(tone(2_500))
        val flushed = segmenter.flush()

        assertEquals(1, duringStream.size)
        val first = duringStream.single()
        assertTrue("duration was ${first.durationMs}", first.durationMs in 1_900L..2_100L)

        assertEquals(1, flushed.size)
        assertTrue(flushed.single().durationMs in 400L..600L)
        segmenter.close()
    }

    @Test
    fun `emits the in-flight segment on flush`() {
        val segmenter = segmenter()

        val duringStream = segmenter.process(tone(400))
        val flushed = segmenter.flush()

        assertTrue(duringStream.isEmpty())
        assertEquals(1, flushed.size)
        assertTrue(flushed.single().durationMs >= 300L)
        segmenter.close()
    }

    @Test
    fun `separates two utterances split by a pause`() {
        val segmenter = segmenter(silenceMs = 300)

        val segments = buildList {
            addAll(segmenter.process(tone(300)))
            addAll(segmenter.process(silence(400)))
            addAll(segmenter.process(tone(300)))
            addAll(segmenter.process(silence(400)))
            addAll(segmenter.flush())
        }

        assertEquals(2, segments.size)
        assertTrue(segments[0].startMs < segments[1].startMs)
        segmenter.close()
    }

    @Test
    fun `reset discards buffered audio`() {
        val segmenter = segmenter()

        segmenter.process(tone(500))
        segmenter.reset()

        assertTrue(segmenter.flush().isEmpty())
        assertEquals(0, segmenter.process(tone(10)).size)
        segmenter.close()
    }
}
