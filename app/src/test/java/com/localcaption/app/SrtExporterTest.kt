package com.localcaption.app

import com.localcaption.app.data.db.SegmentEntity
import com.localcaption.app.data.model.AudioSourceType
import com.localcaption.app.export.formatSrt
import com.localcaption.app.export.formatSrtTimestamp
import org.junit.Assert.assertEquals
import org.junit.Test

class SrtExporterTest {

    private fun segment(
        id: Long,
        startMs: Long,
        endMs: Long,
        text: String,
        speakerId: Long? = null,
    ) = SegmentEntity(
        id = id,
        sessionId = 1L,
        speakerId = speakerId,
        startMs = startMs,
        endMs = endMs,
        text = text,
        audioSource = AudioSourceType.MICROPHONE,
    )

    @Test
    fun `formats cues with index and timestamps`() {
        val srt = formatSrt(
            listOf(
                segment(1, 1_240, 3_880, "第一句"),
                segment(2, 61_500, 63_000, "第二句"),
            ),
            includeSpeaker = false,
        )

        val expected = "1\n" +
            "00:00:01,240 --> 00:00:03,880\n" +
            "第一句\n" +
            "\n" +
            "2\n" +
            "00:01:01,500 --> 00:01:03,000\n" +
            "第二句\n" +
            "\n"
        assertEquals(expected, srt)
    }

    @Test
    fun `sorts segments by start time regardless of input order`() {
        val srt = formatSrt(
            listOf(
                segment(9, 5_000, 6_000, "later"),
                segment(3, 1_000, 2_000, "earlier"),
            ),
            includeSpeaker = false,
        )

        val lines = srt.lines()
        assertEquals("1", lines[0])
        assertEquals("00:00:01,000 --> 00:00:02,000", lines[1])
        assertEquals("earlier", lines[2])
        assertEquals("2", lines[4])
        assertEquals("00:00:05,000 --> 00:00:06,000", lines[5])
        assertEquals("later", lines[6])
    }

    @Test
    fun `adds speaker prefix when speaker mapping is available`() {
        val srt = formatSrt(
            segments = listOf(segment(1, 0, 1_500, "大家好", speakerId = 42L)),
            speakersMap = mapOf(42L to "说话人 1"),
            includeSpeaker = true,
        )

        assertEquals(
            "1\n00:00:00,000 --> 00:00:01,500\n[说话人 1] 大家好\n\n",
            srt,
        )
    }

    @Test
    fun `omits speaker prefix when disabled`() {
        val srt = formatSrt(
            segments = listOf(segment(1, 0, 1_500, "大家好", speakerId = 42L)),
            speakersMap = mapOf(42L to "说话人 1"),
            includeSpeaker = false,
        )

        assertEquals("1\n00:00:00,000 --> 00:00:01,500\n大家好\n\n", srt)
    }

    @Test
    fun `keeps unknown speakers unprefixed`() {
        val srt = formatSrt(
            segments = listOf(segment(1, 0, 1_000, "你好", speakerId = 7L)),
            speakersMap = emptyMap(),
            includeSpeaker = true,
        )

        assertEquals("1\n00:00:00,000 --> 00:00:01,000\n你好\n\n", srt)
    }

    @Test
    fun `skips blank segments`() {
        val srt = formatSrt(
            listOf(
                segment(1, 0, 1_000, "有效"),
                segment(2, 1_000, 2_000, "   \n  "),
            ),
            includeSpeaker = false,
        )

        assertEquals(1, srt.trim().lines().count { it == "有效" })
        assertEquals(false, srt.contains("2\n00:00:01,000"))
    }

    @Test
    fun `returns empty output for empty input`() {
        assertEquals("", formatSrt(emptyList()))
    }

    @Test
    fun `formats timestamps with hours and clamps negatives`() {
        assertEquals("00:00:00,000", formatSrtTimestamp(-10L))
        assertEquals("00:00:01,240", formatSrtTimestamp(1_240L))
        assertEquals("01:02:03,004", formatSrtTimestamp(3_723_004L))
    }
}
