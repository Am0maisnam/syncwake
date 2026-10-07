package app.syncwake.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object SyncWakeColors {
    val Background = Color(0xFF141414)
    val Surface = Color(0xFF1E1E1E)
    val SurfaceHigh = Color(0xFF2A2A2A)
    /** Borders and dividers; >= 3:1 against the background so cards stay distinguishable. */
    val Outline = Color(0xFF6A6A6A)

    // Text colours. Contrast on Background / Surface / SurfaceHigh (WCAG):
    val OnBackground = Color(0xFFFFFFFF) // 18.4 / 16.7 / 14.4
    val Muted = Color(0xFFCCCCCC) //        11.5 / 10.4 / 8.9  (secondary text)
    val Accent = Color(0xFFFFFFFF)
    val Success = Color(0xFF8FF0A4) //      13.3 / 12.0 / 10.4
    val Warning = Color(0xFFFFD180) //      12.9 / 11.7 / 10.0
    val Error = Color(0xFFFF8A80) //         8.1 /  7.3 /  6.3
}

private val colors = darkColorScheme(
    primary = SyncWakeColors.Accent,
    onPrimary = Color.Black,
    secondary = SyncWakeColors.SurfaceHigh,
    onSecondary = SyncWakeColors.OnBackground,
    background = SyncWakeColors.Background,
    onBackground = SyncWakeColors.OnBackground,
    surface = SyncWakeColors.Surface,
    onSurface = SyncWakeColors.OnBackground,
    surfaceVariant = SyncWakeColors.SurfaceHigh,
    onSurfaceVariant = SyncWakeColors.Muted,
    outline = SyncWakeColors.Outline,
    error = SyncWakeColors.Error,
)

/** Monospace "label" style used for technical captions, as in the design mock. */
val MonoLabel = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)

private val typography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 72.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 1.sp),
)

@Composable
fun SyncWakeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
