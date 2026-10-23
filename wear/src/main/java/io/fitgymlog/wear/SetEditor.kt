package io.fitgymlog.wear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.PickerGroup
import androidx.wear.compose.material3.PickerState
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.rememberPickerState
import io.fitgymlog.watchlink.WatchCommand
import io.fitgymlog.watchlink.WatchSetRef
import io.fitgymlog.watchlink.WatchWorkout
import io.fitgymlog.watchlink.roundStop
import io.fitgymlog.watchlink.weightStops

/**
 * Dials for weight and reps, turned by touch or the crown, then a check. A set not done yet is logged and its rest
 * starts; a set already done is only corrected. A dial left where it started changes nothing.
 */
@Composable
fun SetEditor(workout: WatchWorkout, ref: WatchSetRef, onLog: (WatchCommand.Log) -> Unit) {
    val exercise = workout.exerciseAt(ref) ?: return
    val set = workout.setAt(ref) ?: return
    val unit = workout.unit

    // The dials keep where they started even if the phone changes the set meanwhile, so only a turned dial sends a value.
    val opened = remember(ref) { set }
    val shownWeight = opened.weightKg?.let { roundStop(unit.toDisplay(it)) }
    val weights = remember(ref) { weightStops(shownWeight, exercise.weightStep, ceiling = if (unit.kgPerUnit == 1.0) KG_CEILING else LB_CEILING) }
    val startWeight = remember(ref) { weights.indexOf(shownWeight).coerceAtLeast(0) }
    val startReps = opened.reps ?: 0
    val weightState = rememberPickerState(initialNumberOfOptions = weights.size, initiallySelectedIndex = startWeight, shouldRepeatOptions = false)
    val repsState = rememberPickerState(initialNumberOfOptions = maxOf(MAX_REPS, startReps + 1) + 1, initiallySelectedIndex = startReps, shouldRepeatOptions = false)

    fun log() {
        val weight = if (exercise.hasWeight && weightState.selectedOptionIndex != startWeight) unit.toKg(weights[weightState.selectedOptionIndex]) else null
        val reps = if (exercise.hasReps && repsState.selectedOptionIndex != startReps) repsState.selectedOptionIndex else null
        onLog(WatchCommand.Log(ref, weightKg = weight, reps = reps, complete = !set.done))
    }

    val screen = rememberScreen()
    Box(Modifier.fillMaxSize()) {
        // The dials take whatever height is left between the title and the check, so they fit a small watch and fill a large one.
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().padding(top = screen.timeTextSpace, bottom = screen.edgeButtonSpace),
        ) {
            Text(
                stringResource(R.string.set_title, ref.set + 1),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = screen.margin * 1.5f),
            )
            val dials = Modifier.weight(1f).fillMaxWidth()
            when {
                exercise.hasWeight && exercise.hasReps -> WeightAndReps(weights, weightState, repsState, unit.label, screen, dials)
                exercise.hasReps -> PickerGroup(selectedPickerState = repsState, autoCenter = false, modifier = dials) {
                    Dial(stringResource(R.string.reps), screen.repsDial) { RepsDial(repsState, selected = true, Modifier.weight(1f)) {} }
                }
                else -> Box(contentAlignment = Alignment.Center, modifier = dials) { Text(set.line, style = WatchType.set) }
            }
        }
        EdgeButton(onClick = ::log, modifier = Modifier.align(Alignment.BottomCenter)) {
            Text(if (set.done) stringResource(R.string.save) else "✓", style = WatchType.step)
        }
    }
}

@Composable
private fun WeightAndReps(weights: List<Double>, weightState: PickerState, repsState: PickerState, unitLabel: String, screen: ScreenSizes, modifier: Modifier) {
    var selected by remember { mutableIntStateOf(0) }
    PickerGroup(
        selectedPickerState = if (selected == 0) weightState else repsState,
        autoCenter = false,
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(screen.gap), verticalAlignment = Alignment.CenterVertically) {
            Dial(unitLabel, screen.weightDial) {
                PickerGroupItem(
                    pickerState = weightState,
                    selected = selected == 0,
                    onSelected = { selected = 0 },
                    modifier = Modifier.weight(1f),
                    contentDescription = { "${number(weights[weightState.selectedOptionIndex])} $unitLabel" },
                ) { index, _ -> Option(number(weights[index])) }
            }
            Text("×", style = WatchType.step, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Dial(stringResource(R.string.reps), screen.repsDial) { RepsDial(repsState, selected == 1, Modifier.weight(1f)) { selected = 1 } }
        }
    }
}

/** A dial with its unit underneath, so the label always sits under its own dial whatever the width. */
@Composable
private fun Dial(label: String, width: Dp, dial: @Composable ColumnScope.() -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(width).fillMaxHeight()) {
        dial()
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun androidx.wear.compose.material3.PickerGroupScope.RepsDial(state: PickerState, selected: Boolean, modifier: Modifier, onSelected: () -> Unit) {
    PickerGroupItem(
        pickerState = state,
        selected = selected,
        onSelected = onSelected,
        modifier = modifier.fillMaxWidth(),
        contentDescription = { "${state.selectedOptionIndex}" },
    ) { index, _ -> Option("$index") }
}

@Composable
private fun Option(text: String) {
    Text(text, style = WatchType.dial, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

private const val MAX_REPS = 50
private const val KG_CEILING = 300.0
private const val LB_CEILING = 660.0
