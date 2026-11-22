package com.localcaption.app.export

import com.localcaption.app.data.db.SegmentEntity
import java.util.Locale

/**
 * Formats [segments] as a plain text (`.txt`) transcript, one caption per line.
 *
 * The line layout is `[HH:MM:SS] [Speaker] text`, where the timestamp and the speaker
 * parts are optional:
 *
 * ```
 * [00:00:01] [说话人 1] 大家好
 * [00:00:04] 今天聊一下离线字幕
 * ```
 *
 * @param segments segments of one session, in any order (they are sorted by start time)
 * @param speakersMap maps [SegmentEntity.speakerId] to the display label of that speaker
 * @param includeTimestamps when `true`, every line starts with its start timestamp
 * @param includeSpeaker when `true`, a `[label]` part is added for captions with a speaker
 */
fun formatTxt(
    segments: List<SegmentEntity>,
    speakersMap: Map<Long, String> = emptyMap(),
    includeTimestamps: Boolean = true,
    includeSpeaker: Boolean = true,
): String {
    val usable = segments.filter { it.text.isNotBlank() }
    if (usable.isEmpty()) return ""

    val builder = StringBuilder()
    usable
        .sortedWith(compareBy({ it.startMs }, { it.id }))
        .forEach { segment ->
            if (includeTimestamps) {
                builder.append('[').append(formatTxtTimestamp(segment.startMs)).append(']').append(' ')
            }
            if (includeSpeaker) {
                val label = segment.speakerId
                    ?.let { speakerId -> speakersMap[speakerId] }
                    ?.takeIf { it.isNotBlank() }
                if (label != null) {
                    builder.append('[').append(label).append(']').append(' ')
                }
            }
            builder.append(segment.text.trim().replace(Regex("\\s*\\n\\s*"), " "))
            builder.append('\n')
        }
    return builder.toString()
}

/** Formats [millis] as `HH:MM:SS`, the timestamp format used by plain text exports. */
fun formatTxtTimestamp(millis: Long): String {
    val safe = millis.coerceAtLeast(0L)
    val hours = safe / 3_600_000L
    val minutes = (safe % 3_600_000L) / 60_000L
    val seconds = (safe % 60_000L) / 1_000L
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}
