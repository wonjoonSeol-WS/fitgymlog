package io.fitgymlog.wear

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import io.fitgymlog.watchlink.WatchCommand
import io.fitgymlog.watchlink.WatchSetRef
import io.fitgymlog.watchlink.WatchSetRow
import io.fitgymlog.watchlink.WatchWorkout

/**
 * One exercise. The button under the name logs the next set as planned, so a set takes one tap with no scrolling;
 * tapping a set in the list opens the dials to log what was actually lifted.
 */
@Composable
fun ExercisePage(
    workout: WatchWorkout,
    index: Int,
    restLeft: Long?,
    unreachable: Boolean,
    send: (WatchCommand) -> Unit,
    onEdit: (WatchSetRef) -> Unit,
    onShowRest: () -> Unit,
) {
    val exercise = workout.exercises[index]
    val next = workout.next?.takeIf { it.exercise == index }
    Screen {
        item {
            ListHeader {
                Text(exercise.name, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
        if (unreachable) item { Unreachable() }
        restLeft?.let { left ->
            item {
                FilledTonalButton(onClick = onShowRest, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.rest_left, clock(left, roundUp = true)), style = WatchType.step)
                }
            }
        }
        next?.let { ref ->
            val set = workout.setAt(ref)
            item(key = "log") {
                Button(
                    onClick = { send(WatchCommand.Log(ref, complete = true)) },
                    modifier = Modifier.fillMaxWidth(),
                    secondaryLabel = { Text(set?.line.orEmpty(), style = WatchType.row, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                ) { Text(stringResource(R.string.log_set, ref.set + 1), maxLines = 1) }
            }
        }
        exercise.sets.forEachIndexed { setIndex, set ->
            val ref = WatchSetRef(index, setIndex)
            item(key = "$index/$setIndex") { SetRow(setIndex, set, isNext = ref == next) { onEdit(ref) } }
        }
        if (next == null && exercise.sets.all { it.done }) item { Hint(stringResource(R.string.exercise_done)) }
    }
}

@Composable
private fun SetRow(setIndex: Int, set: WatchSetRow, isNext: Boolean, onClick: () -> Unit) {
    val label = if (set.warmup) stringResource(R.string.warmup_short) else "${setIndex + 1}"
    // Done sets are tonal; the rest are outlined, the next one in the accent colour so it reads as the one the button above logs.
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (set.done) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.outlinedButtonColors(),
        border = when {
            set.done -> null
            isNext -> ButtonDefaults.outlinedButtonBorder(enabled = true, borderColor = MaterialTheme.colorScheme.primary)
            else -> ButtonDefaults.outlinedButtonBorder(enabled = true)
        },
        secondaryLabel = { Text(if (set.done) "✓ $label" else label, style = MaterialTheme.typography.labelSmall) },
    ) { Text(set.line, style = WatchType.row, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}
