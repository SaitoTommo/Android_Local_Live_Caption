package com.localcaption.app.export

import com.localcaption.app.data.db.SegmentEntity
import java.util.Locale

/**
 * Formats [segments] as a standard SubRip (`.srt`) document.
 *
 * The output follows the classic SubRip layout:
 *
 * ```
 * 1
 * 00:00:01,240 --> 00:00:03,880
 * [说话人 1] 大家好
 *
 * 2
 * ...
 * ```
 *
 * @param segments segments of one session, in any order (they are sorted by start time)
 * @param speakersMap maps [SegmentEntity.speakerId] to the display label of that speaker
 * @param includeSpeaker when `true`, a `[label]` prefix is added to every cue that has a
 * known speaker
 */
fun formatSrt(
    segments: List<SegmentEntity>,
    speakersMap: Map<Long, String> = emptyMap(),
    includeSpeaker: Boolean = true,
): String {
    val usable = segments.filter { it.text.isNotBlank() }
    if (usable.isEmpty()) return ""

    val builder = StringBuilder()
    usable
        .sortedWith(compareBy({ it.startMs }, { it.id }))
        .forEachIndexed { index, segment ->
            builder.append(index + 1).append('\n')
            builder.append(formatSrtTimestamp(segment.startMs))
                .append(" --> ")
                .append(formatSrtTimestamp(segment.endMs.coerceAtLeast(segment.startMs)))
                .append('\n')
            builder.append(cueText(segment, speakersMap, includeSpeaker)).append('\n')
            builder.append('\n')
        }
    return builder.toString()
}

/** Formats [millis] as `HH:MM:SS,mmm`, the timestamp format used by SubRip. */
fun formatSrtTimestamp(millis: Long): String {
    val safe = millis.coerceAtLeast(0L)
    val hours = safe / HOUR_MS
    val minutes = (safe % HOUR_MS) / MINUTE_MS
    val seconds = (safe % MINUTE_MS) / SECOND_MS
    val milliseconds = safe % SECOND_MS
    return String.format(
        Locale.US,
        "%02d:%02d:%02d,%03d",
        hours,
        minutes,
        seconds,
        milliseconds,
    )
}

private fun cueText(
    segment: SegmentEntity,
    speakersMap: Map<Long, String>,
    includeSpeaker: Boolean,
): String {
    val text = normalizeText(segment.text)
    if (!includeSpeaker) return text
    val label = segment.speakerId
        ?.let { speakerId -> speakersMap[speakerId] }
        ?.takeIf { it.isNotBlank() }
        ?: return text
    return "[$label] $text"
}

/**
 * Normalizes line endings and collapses empty lines so that a segment can never look
 * like the blank separator between two SubRip cues.
 */
private fun normalizeText(text: String): String =
    text.trim()
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("\n{2,}"), "\n")

private const val SECOND_MS = 1_000L
private const val MINUTE_MS = 60 * SECOND_MS
private const val HOUR_MS = 60 * MINUTE_MS
