package com.localcaption.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F5BD8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE1FF),
    onPrimaryContainer = Color(0xFF00174B),
    secondary = Color(0xFF00696B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF9CF1F0),
    onSecondaryContainer = Color(0xFF002020),
    tertiary = Color(0xFF7B4E00),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDB3),
    onTertiaryContainer = Color(0xFF271900),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1A1B21),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFE2E1EC),
    onSurfaceVariant = Color(0xFF45464F),
    outline = Color(0xFF767680),
    outlineVariant = Color(0xFFC6C5D0),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB7C4FF),
    onPrimary = Color(0xFF00277A),
    primaryContainer = Color(0xFF103CAE),
    onPrimaryContainer = Color(0xFFDCE1FF),
    secondary = Color(0xFF80D5D4),
    onSecondary = Color(0xFF003737),
    secondaryContainer = Color(0xFF004F50),
    onSecondaryContainer = Color(0xFF9CF1F0),
    tertiary = Color(0xFFF7BC71),
    onTertiary = Color(0xFF412D00),
    tertiaryContainer = Color(0xFF5E4200),
    onTertiaryContainer = Color(0xFFFFDDB3),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE3E1E9),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE3E1E9),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC6C5D0),
    outline = Color(0xFF90909A),
    outlineVariant = Color(0xFF45464F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * Material 3 theme of LocalLiveCaption.
 *
 * The palette is a calm blue/teal pair that keeps captions readable in bright and dark
 * environments; dynamic color is intentionally not used so speaker colors stay stable.
 */
@Composable
fun LocalLiveCaptionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}

/**
 * Stable palette used for speaker pills in the live view, the history detail list and
 * the floating overlay. Colors are paired with white text and are chosen to stay
 * distinguishable on both light and dark surfaces.
 */
object SpeakerPalette {

    /** Colors assigned to speakers, in a fixed order. */
    val colors: List<Color> = listOf(
        Color(0xFF4C8DFF),
        Color(0xFF2FBF8F),
        Color(0xFFF2A33A),
        Color(0xFFB075FF),
        Color(0xFFF2617A),
        Color(0xFF25B9D6),
        Color(0xFF8D9E3F),
        Color(0xFFE0703F),
    )

    /**
     * Returns the color that represents [label].
     *
     * The mapping is deterministic (a hash of the label), so the same speaker keeps the
     * same color across screens and app restarts.
     */
    fun colorFor(label: String?): Color {
        val key = label?.takeIf { it.isNotBlank() } ?: return colors.first()
        val index = (key.hashCode() and Int.MAX_VALUE) % colors.size
        return colors[index]
    }

    /** Content color used on top of [colorFor]. */
    fun onColorFor(@Suppress("UNUSED_PARAMETER") label: String?): Color = Color.White
}
