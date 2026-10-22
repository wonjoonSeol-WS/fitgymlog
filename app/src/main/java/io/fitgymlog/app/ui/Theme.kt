package io.fitgymlog.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.fitgymlog.app.R

/** Colours Material has no slot for: completed sets, set types and the rest bar. */
@Immutable
data class LiftColors(
    val doneRow: Color,
    val doneCheck: Color,
    val warmup: Color,
    val drop: Color,
    val failure: Color,
    val restBar: Color,
    val onRestBar: Color,
    val field: Color,
)

private val Blue = Color(0xFF1F7AE0)
private val BlueDark = Color(0xFF4A9BFF)

private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6F0FC),
    onPrimaryContainer = Color(0xFF0D3F78),
    secondary = Color(0xFF5C6370),
    onSecondary = Color.White,
    tertiary = Color(0xFFE8A317),
    background = Color.White,
    onBackground = Color(0xFF15171A),
    surface = Color.White,
    onSurface = Color(0xFF15171A),
    surfaceVariant = Color(0xFFF3F4F6),
    onSurfaceVariant = Color(0xFF868B93),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F9FA),
    surfaceContainer = Color(0xFFF3F4F6),
    surfaceContainerHigh = Color(0xFFECEEF1),
    surfaceContainerHighest = Color(0xFFE4E7EB),
    outline = Color(0xFFDDE0E4),
    outlineVariant = Color(0xFFECEEF1),
    error = Color(0xFFE5484D),
)

private val DarkColors = darkColorScheme(
    primary = BlueDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF12304F),
    onPrimaryContainer = Color(0xFFD3E6FF),
    secondary = Color(0xFFA3A9B3),
    tertiary = Color(0xFFF2B53A),
    background = Color.Black,
    onBackground = Color(0xFFF1F2F4),
    surface = Color.Black,
    onSurface = Color(0xFFF1F2F4),
    surfaceVariant = Color(0xFF1C1D20),
    onSurfaceVariant = Color(0xFF959AA3),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF111214),
    surfaceContainer = Color(0xFF1C1D20),
    surfaceContainerHigh = Color(0xFF26272B),
    surfaceContainerHighest = Color(0xFF303136),
    outline = Color(0xFF34363B),
    outlineVariant = Color(0xFF232428),
    error = Color(0xFFFF6369),
)

private val LightLift = LiftColors(
    doneRow = Color(0xFFDDF5D4),
    doneCheck = Color(0xFF2DB24A),
    warmup = Color(0xFFE8A317),
    drop = Color(0xFF8B5CF6),
    failure = Color(0xFFE5484D),
    restBar = Color(0xFF17181B),
    onRestBar = Color.White,
    field = Color(0xFFF3F4F6),
)

private val DarkLift = LiftColors(
    doneRow = Color(0xFF13321B),
    doneCheck = Color(0xFF34C759),
    warmup = Color(0xFFF2B53A),
    drop = Color(0xFFA78BFA),
    failure = Color(0xFFFF6369),
    restBar = Color(0xFF26272B),
    onRestBar = Color.White,
    field = Color(0xFF1C1D20),
)

val LocalLiftColors = staticCompositionLocalOf { LightLift }

/**
 * Numbers are the one loud thing in the app: weights, reps, timers and totals use a condensed
 * gym-signage face with tabular figures so columns line up. Everything else stays on the
 * system font, which also keeps Korean text consistent.
 */
val Numerals = FontFamily(
    Font(R.font.barlow_semi_condensed_medium, FontWeight.Medium),
    Font(R.font.barlow_semi_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_semi_condensed_bold, FontWeight.Bold),
)

object NumberStyles {
    val cell = TextStyle(fontFamily = Numerals, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, fontFeatureSettings = "tnum")
    val stat = TextStyle(fontFamily = Numerals, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, fontFeatureSettings = "tnum")
    val timer = TextStyle(fontFamily = Numerals, fontWeight = FontWeight.Bold, fontSize = 40.sp, fontFeatureSettings = "tnum")
    val hero = TextStyle(fontFamily = Numerals, fontWeight = FontWeight.Bold, fontSize = 56.sp, fontFeatureSettings = "tnum")
}

private val AppTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 28.sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium),
        labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    )
}

@Composable
fun FitGymLogTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalLiftColors provides if (dark) DarkLift else LightLift) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = AppTypography, content = content)
    }
}

val MaterialTheme.lift: LiftColors
    @Composable get() = LocalLiftColors.current
