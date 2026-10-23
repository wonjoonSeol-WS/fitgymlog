package io.fitgymlog.wear

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/** The phone app's blue and its Barlow numerals, on the watch's black. */
val Numerals = FontFamily(
    Font(R.font.barlow_semi_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_semi_condensed_bold, FontWeight.Bold),
)

object WatchType {
    val clock = TextStyle(fontFamily = Numerals, fontWeight = FontWeight.Bold, fontSize = 44.sp)
    val set = TextStyle(fontFamily = Numerals, fontWeight = FontWeight.SemiBold, fontSize = 26.sp)
    val step = TextStyle(fontFamily = Numerals, fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
    val row = TextStyle(fontFamily = Numerals, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
    val dial = TextStyle(fontFamily = Numerals, fontWeight = FontWeight.SemiBold, fontSize = 30.sp)
}

private val Colors = ColorScheme(
    primary = Color(0xFF4A9BFF),
    onPrimary = Color(0xFF0A1A2E),
    primaryContainer = Color(0xFF0D3F78),
    onPrimaryContainer = Color(0xFFE6F0FC),
    tertiary = Color(0xFFE8A317),
)

@Composable
fun WatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
