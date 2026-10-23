package io.fitgymlog.app.ui.workout

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.fitgymlog.app.R
import io.fitgymlog.app.data.Settings
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.model.WorkoutExercise
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.model.isTimed
import io.fitgymlog.app.ui.localizedName
import io.fitgymlog.app.ui.setTypeName
import io.fitgymlog.app.workout.ActiveWorkout
import io.fitgymlog.app.workout.SetRef
import io.fitgymlog.app.workout.previousRows

internal sealed interface PickerTarget {
    data object Add : PickerTarget
    data class Replace(val index: Int) : PickerTarget
}

private const val ADD_TARGET = -1

internal val PickerTargetSaver = Saver<PickerTarget?, Int>(
    save = { target ->
        when (target) {
            null -> null
            PickerTarget.Add -> ADD_TARGET
            is PickerTarget.Replace -> target.index
        }
    },
    restore = { if (it == ADD_TARGET) PickerTarget.Add else PickerTarget.Replace(it) },
)

internal enum class SetField { WEIGHT, AMOUNT, LEVEL, SPEED, DISTANCE }

internal data class SetEditor(val field: SetField, val ref: SetRef)

internal val SetEditorSaver = Saver<SetEditor?, IntArray>(
    save = { editor -> editor?.let { intArrayOf(it.field.ordinal, it.ref.exerciseIndex, it.ref.setIndex) } },
    restore = { (field, exerciseIndex, setIndex) -> SetEditor(SetField.entries[field], SetRef(exerciseIndex, setIndex)) },
)

@Composable
internal fun SetEditorSheet(
    editor: SetEditor,
    exercise: WorkoutExercise,
    set: WorkoutSet,
    info: Exercise,
    previous: List<WorkoutSet>,
    settings: Settings,
    updateSettings: ((Settings) -> Settings) -> Unit,
    onChange: ((ActiveWorkout) -> ActiveWorkout) -> Unit,
    onDismiss: () -> Unit,
) {
    val ref = editor.ref
    val name = info.localizedName()
    val setTitle = when (val number = normalSetNumbers(exercise.sets)[ref.setIndex]) {
        null -> stringResource(R.string.set_editor_title_typed, name, setTypeName(set.type))
        else -> stringResource(R.string.set_editor_title, name, number)
    }
    when (editor.field) {
        SetField.WEIGHT -> {
            val later = exercise.sets.drop(ref.setIndex + 1).filter { !it.isCompleted }
            val lastTime = previousRows(exercise.sets, previous).getOrNull(ref.setIndex)
            WeightSheet(
                title = setTitle,
                initialKg = set.weightKg ?: lastTime?.weightKg,
                previousKg = lastTime?.weightKg,
                equipment = info.equipment,
                unit = settings.unit,
                bar = settings.bar,
                laterSetsMatch = later.isNotEmpty() && later.all { it.weightKg == set.weightKg },
                onBarChange = { bar ->
                    updateSettings { if (it.unit == WeightUnit.KG) it.copy(barKg = bar) else it.copy(barLb = bar) }
                },
                onSave = { kg, fromHere ->
                    onChange { if (fromHere) it.setWeightFromHere(ref, kg) else it.setWeight(ref, kg) }
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
        SetField.AMOUNT -> {
            val kind = info.kind
            NumberPadSheet(
                title = setTitle,
                options = kind.amountChoices(),
                selected = set.amount(kind),
                target = if (kind.isTimed) null else set.targetReps,
                onPick = { value ->
                    onChange { if (kind.isTimed) it.setDuration(ref, value?.let(kind::secondsOf)) else it.setRepsFromHere(ref, value) }
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
        SetField.SPEED -> SpeedSheet(
            title = setTitle,
            initialKmh = set.speedKmh ?: previousRows(exercise.sets, previous).getOrNull(ref.setIndex)?.speedKmh,
            unit = settings.unit,
            onSave = { kmh ->
                onChange { it.setSpeed(ref, kmh) }
                onDismiss()
            },
            onDismiss = onDismiss,
        )
        SetField.DISTANCE -> DistanceSheet(
            title = setTitle,
            initialMeters = set.distanceMeters ?: previousRows(exercise.sets, previous).getOrNull(ref.setIndex)?.distanceMeters,
            onSave = { meters ->
                onChange { it.setDistance(ref, meters) }
                onDismiss()
            },
            onDismiss = onDismiss,
        )
        SetField.LEVEL -> NumberPadSheet(
            title = setTitle,
            options = LEVEL_CHOICES,
            selected = set.level,
            target = null,
            onPick = { value ->
                onChange { it.setLevel(ref, value) }
                onDismiss()
            },
            onDismiss = onDismiss,
        )
    }
}

@Composable
internal fun WorkoutMenu(onRename: () -> Unit, onDiscard: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.rename_workout)) }, onClick = { open = false; onRename() })
            DropdownMenuItem(text = { Text(stringResource(R.string.discard_workout)) }, onClick = { open = false; onDiscard() })
        }
    }
}

@Composable
internal fun RenameDialog(title: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_workout)) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onRename(text.trim()); onDismiss() }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
