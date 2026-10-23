package io.fitgymlog.wear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import java.util.Locale
import kotlinx.coroutines.delay

/** A scrolling screen, with the button that hugs the bottom edge when there is one. */
@Composable
fun Screen(edge: (@Composable BoxScope.() -> Unit)? = null, content: ScalingLazyListScope.() -> Unit) {
    val listState = rememberScalingLazyListState()
    if (edge != null) {
        ScreenScaffold(scrollState = listState, edgeButton = edge) { padding -> ScreenList(listState, padding, content) }
    } else {
        ScreenScaffold(scrollState = listState) { padding -> ScreenList(listState, padding, content) }
    }
}

@Composable
private fun ScreenList(state: ScalingLazyListState, padding: PaddingValues, content: ScalingLazyListScope.() -> Unit) {
    ScalingLazyColumn(
        state = state,
        contentPadding = padding,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize(),
        content = content,
    )
}

@Composable
fun Hint(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

@Composable
fun Message(text: String) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(text, textAlign = TextAlign.Center)
    }
}

/** The phone's time, ticking while [tickMillis] is set: the phone only sends when something changes, so the watch counts on its own. */
@Composable
fun rememberNow(tickMillis: Long?): State<Long> {
    val now = remember { mutableLongStateOf(PhoneLink.phoneNow()) }
    LaunchedEffect(tickMillis) {
        now.longValue = PhoneLink.phoneNow()
        while (tickMillis != null) {
            delay(tickMillis)
            now.longValue = PhoneLink.phoneNow()
        }
    }
    return now
}

/** 1:05 for a rest, 1:02:30 for a long workout. */
fun clock(millis: Long, roundUp: Boolean = false): String {
    val seconds = (if (roundUp) millis + 999 else millis) / 1000
    val hours = seconds / 3600
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60)
    } else {
        String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }
}

/** 60 shows as "60", 61.25 as "61.25". */
fun number(value: Double): String = String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')

const val REST_TICK_MILLIS = 250L
const val CLOCK_TICK_MILLIS = 1_000L

/**
 * Sizes as a share of the screen, so one layout holds from a 192 dp watch to a 260 dp one. Touch targets keep
 * Wear's 40 dp floor, and the edge button keeps its fixed height whatever the screen.
 */
class ScreenSizes(width: Dp) {
    val margin: Dp = width * 0.08f
    val gap: Dp = width * 0.02f
    val smallButton: Dp = maxOf(40.dp, width * 0.2f)
    val edgeButtonSpace: Dp = 60.dp

    /** Room for the clock along the top edge, which every screen shows. */
    val timeTextSpace: Dp = maxOf(28.dp, width * 0.12f)
    val weightDial: Dp = width * 0.33f
    val repsDial: Dp = width * 0.22f
    val clock: TextStyle = WatchType.clock.copy(fontSize = (width.value * 0.19f).sp)
}

@Composable
fun rememberScreen(): ScreenSizes {
    val width = LocalConfiguration.current.screenWidthDp.dp
    return remember(width) { ScreenSizes(width) }
}
