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
    val Outline = Color(0xFF3A3A3A)
    val OnBackground = Color(0xFFF2F2F2)
    val Muted = Color(0xFF9E9E9E)
    val Accent = Color(0xFFFFFFFF)
    val Success = Color(0xFF7BD88F)
    val Warning = Color(0xFFFFC66D)
    val Error = Color(0xFFFF6B6B)
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
val MonoLabel = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Medium)

private val typography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 72.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    bodyMedium = TextStyle(fontSize = 15.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 1.sp),
)

@Composable
fun SyncWakeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
