package app.daybook.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Always dark: near-black ground, grey surfaces, white text, purple accent.
private val Scheme = darkColorScheme(
    primary = Color(0xFFA78BFA),
    onPrimary = Color(0xFF1A1033),
    primaryContainer = Color(0xFF2E2346),
    onPrimaryContainer = Color(0xFFDDD1FF),
    secondary = Color(0xFFC4B5FD),
    secondaryContainer = Color(0xFF26222F),
    onSecondaryContainer = Color(0xFFCFC6E6),
    background = Color(0xFF0E0E11),
    onBackground = Color(0xFFECECF1),
    surface = Color(0xFF17171C),
    onSurface = Color(0xFFECECF1),
    surfaceVariant = Color(0xFF222229),
    onSurfaceVariant = Color(0xFF9A9AA6),
    surfaceContainer = Color(0xFF17171C),
    surfaceContainerHigh = Color(0xFF1E1E24),
    surfaceContainerHighest = Color(0xFF26262D),
    outline = Color(0xFF2E2E36),
    outlineVariant = Color(0xFF2E2E36),
)

/** Good / warning colors for status chips, separate from the accent. */
@Immutable
data class StatusColors(val good: Color, val goodBg: Color, val warn: Color, val warnBg: Color)

private val Status = StatusColors(Color(0xFF7DD3A0), Color(0xFF15261C), Color(0xFFF0A868), Color(0xFF2D2016))

val LocalStatusColors = staticCompositionLocalOf { Status }

@Composable
fun DiurnaTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalStatusColors provides Status) {
        MaterialTheme(colorScheme = Scheme, content = content)
    }
}
