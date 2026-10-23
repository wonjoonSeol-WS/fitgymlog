package io.fitgymlog.wear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import io.fitgymlog.watchlink.WatchCommand
import io.fitgymlog.watchlink.WatchWorkout

/**
 * The whole face is the timer: a ring that fills as the rest goes by, the time left with 15-second steps either side
 * where a round screen is widest, and the set that comes next. Swiping back puts it aside to look at the workout,
 * where tapping the set just done corrects it.
 */
@Composable
fun RestScreen(
    workout: WatchWorkout,
    leftMillis: Long,
    unreachable: Boolean,
    send: (WatchCommand) -> Unit,
    onHide: () -> Unit,
) {
    BackHandler(onBack = onHide)
    val screen = rememberScreen()
    val length = workout.restLengthMillis
    val next = workout.next?.let { ref -> workout.exerciseAt(ref)?.let { it to workout.setAt(ref) } }
    Box(Modifier.fillMaxSize()) {
        if (length != null && length > 0) {
            CircularProgressIndicator(progress = { 1f - leftMillis.toFloat() / length }, modifier = Modifier.fillMaxSize().padding(2.dp))
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize().padding(horizontal = screen.margin).padding(top = screen.margin, bottom = screen.edgeButtonSpace),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(screen.gap)) {
                FilledTonalIconButton(onClick = { send(WatchCommand.AdjustRest(-REST_STEP_SECONDS)) }, modifier = Modifier.size(screen.smallButton)) {
                    Text("−15", style = MaterialTheme.typography.labelSmall)
                }
                Text(clock(leftMillis, roundUp = true), style = screen.clock)
                FilledTonalIconButton(onClick = { send(WatchCommand.AdjustRest(REST_STEP_SECONDS)) }, modifier = Modifier.size(screen.smallButton)) {
                    Text("+15", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (unreachable) Unreachable()
            next?.let { (exercise, set) ->
                Text(
                    stringResource(R.string.up_next, exercise.name),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                set?.let { Text(it.line, style = WatchType.row, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        EdgeButton(onClick = { send(WatchCommand.SkipRest) }, modifier = Modifier.align(Alignment.BottomCenter)) {
            Text(stringResource(R.string.skip_rest))
        }
    }
}

private const val REST_STEP_SECONDS = 15
