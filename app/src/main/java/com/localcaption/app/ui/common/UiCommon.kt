package com.localcaption.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.localcaption.app.LocalCaptionApp
import com.localcaption.app.data.model.AudioSourceType
import com.localcaption.app.ui.theme.SpeakerPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Resolves the application container of the current context. */
@Composable
fun rememberAppContainer(): LocalCaptionApp {
    val context = LocalContext.current
    return remember(context) { LocalCaptionApp.from(context) }
}

/** `true` when [type] records audio played back by other apps. */
val AudioSourceType.isPlayback: Boolean
    get() = this == AudioSourceType.PLAYBACK_CAPTURE

/** Short Chinese label of an audio source. */
fun audioSourceLabel(type: AudioSourceType): String = when (type) {
    AudioSourceType.PLAYBACK_CAPTURE -> "手机内录"
    AudioSourceType.MICROPHONE -> "麦克风收音"
}

/** Formats [millis] as `yyyy-MM-dd HH:mm`. */
fun formatSessionDate(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

/** Formats [millis] as `mm:ss`, or `h:mm:ss` once an hour is exceeded. */
fun formatClock(millis: Long): String {
    val safe = millis.coerceAtLeast(0L)
    val totalSeconds = safe / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

/** Formats a session length, falling back to `--:--` for unknown durations. */
fun formatDuration(durationMs: Long?): String =
    if (durationMs == null || durationMs <= 0L) "--:--" else formatClock(durationMs)

/** Formats a byte count such as `880 MB`. */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "未知大小"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
        mb >= 1.0 -> String.format(Locale.US, "%.0f MB", mb)
        else -> String.format(Locale.US, "%.0f KB", kb)
    }
}

/** Small colored pill that identifies a speaker. */
@Composable
fun SpeakerBadge(
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = SpeakerPalette.colorFor(label),
        contentColor = SpeakerPalette.onColorFor(label),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Rounded metadata chip used for model / source / duration information. */
@Composable
fun MetaChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(14.dp))
            }
            Text(text = text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}
