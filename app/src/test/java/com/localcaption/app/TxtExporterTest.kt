package com.localcaption.app

import com.localcaption.app.data.db.SegmentEntity
import com.localcaption.app.data.model.AudioSourceType
import com.localcaption.app.export.formatTxt
import com.localcaption.app.export.formatTxtTimestamp
import org.junit.Assert.assertEquals
import org.junit.Test

class TxtExporterTest {

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
        audioSource = AudioSourceType.PLAYBACK_CAPTURE,
    )

    @Test
    fun `writes one line per segment with timestamp and speaker`() {
        val txt = formatTxt(
            segments = listOf(
                segment(1, 1_000, 2_000, "大家好", speakerId = 5L),
                segment(2, 4_000, 5_000, "今天聊一下离线字幕"),
            ),
            speakersMap = mapOf(5L to "说话人 1"),
        )

        assertEquals(
            "[00:00:01] [说话人 1] 大家好\n" +
                "[00:00:04] 今天聊一下离线字幕\n",
            txt,
        )
    }

    @Test
    fun `omits timestamps when disabled`() {
        val txt = formatTxt(
            segments = listOf(segment(1, 1_000, 2_000, "第一行")),
            includeTimestamps = false,
            includeSpeaker = false,
        )

        assertEquals("第一行\n", txt)
    }

    @Test
    fun `omits speaker when disabled`() {
        val txt = formatTxt(
            segments = listOf(segment(1, 1_000, 2_000, "第一行", speakerId = 3L)),
            speakersMap = mapOf(3L to "小明"),
            includeSpeaker = false,
        )

        assertEquals("[00:00:01] 第一行\n", txt)
    }

    @Test
    fun `sorts by start time and collapses multi line text`() {
        val txt = formatTxt(
            segments = listOf(
                segment(2, 9_000, 10_000, "后面的\n句子"),
                segment(1, 1_000, 2_000, " 前面的  句子 "),
            ),
            includeSpeaker = false,
        )

        assertEquals("[00:00:01] 前面的  句子\n[00:00:09] 后面的 句子\n", txt)
    }

    @Test
    fun `skips blank text`() {
        val txt = formatTxt(
            listOf(
                segment(1, 0, 1_000, ""),
                segment(2, 1_000, 2_000, "保留"),
            ),
            includeSpeaker = false,
        )

        assertEquals("[00:00:01] 保留\n", txt)
    }

    @Test
    fun `returns empty output for empty input`() {
        assertEquals("", formatTxt(emptyList()))
    }

    @Test
    fun `formats txt timestamps`() {
        assertEquals("00:00:00", formatTxtTimestamp(-5L))
        assertEquals("00:00:59", formatTxtTimestamp(59_999L))
        assertEquals("02:03:04", formatTxtTimestamp(7_384_000L))
    }
}
