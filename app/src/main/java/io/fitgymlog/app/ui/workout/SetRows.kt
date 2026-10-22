package io.fitgymlog.app.ui.workout

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.fitgymlog.app.R
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.model.WorkoutExercise
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.ui.NumberStyles
import io.fitgymlog.app.ui.exercise.ExerciseAvatar
import io.fitgymlog.app.ui.formatRest
import io.fitgymlog.app.ui.formatWeightNumber
import io.fitgymlog.app.ui.lift
import io.fitgymlog.app.ui.localizedName
import io.fitgymlog.app.ui.setTypeName
import io.fitgymlog.app.ui.usesWeight
import io.fitgymlog.app.workout.ActiveWorkout
import io.fitgymlog.app.workout.SetRef
import java.time.Instant

@Composable
internal fun ExerciseSection(
    index: Int,
    exercise: WorkoutExercise,
    info: Exercise,
    previous: List<WorkoutSet>,
    focused: Boolean,
    bandHint: Boolean,
    isLast: Boolean,
    unit: WeightUnit,
    onChange: ((ActiveWorkout) -> ActiveWorkout) -> Unit,
    onEditSet: (SetEditor) -> Unit,
    onReplace: () -> Unit,
    onEditRest: () -> Unit,
    onGuide: () -> Unit,
    onOpenExercise: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 18.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
            ExerciseAvatar(info, onClick = onOpenExercise, onClickLabel = stringResource(R.string.open_exercise_details))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    info.localizedName(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClick = onOpenExercise),
                )
                if (bandHint) {
                    Text(stringResource(R.string.band_controls_this), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (!focused) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.use_on_band)) }, onClick = { menuOpen = false; onChange { it.focusExercise(index) } })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.replace_exercise)) }, onClick = { menuOpen = false; onReplace() })
                    if (index > 0) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.move_up)) }, onClick = { menuOpen = false; onChange { it.moveExercise(index, index - 1) } })
                    }
                    if (!isLast) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.move_down)) }, onClick = { menuOpen = false; onChange { it.moveExercise(index, index + 1) } })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.remove_exercise)) }, onClick = { menuOpen = false; onChange { it.removeExercise(index) } })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 6.dp)) {
            Pill(text = stringResource(R.string.form_guide), selected = false, leadingIcon = Icons.Default.PlayArrow, onClick = onGuide)
            Pill(
                text = stringResource(R.string.rest_label, if (exercise.restSeconds == 0) stringResource(R.string.rest_off) else formatRest(exercise.restSeconds)),
                selected = false,
                onClick = onEditRest,
            )
        }
        SetHeader(info.kind, unit)
        val numbers = normalSetNumbers(exercise.sets)
        exercise.sets.forEachIndexed { setIndex, set ->
            SetRow(
                label = setLabel(set.type, numbers[setIndex]),
                set = set,
                previous = previous.getOrNull(setIndex),
                kind = info.kind,
                unit = unit,
                ref = SetRef(index, setIndex),
                onChange = onChange,
                onEditSet = onEditSet,
            )
        }
        Surface(
            onClick = { onChange { it.addSet(index) } },
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.lift.field,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(40.dp),
        ) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.add_set), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun setLabel(type: SetType, normalNumber: Int?): String = when (type) {
    SetType.NORMAL -> normalNumber.toString()
    SetType.WARMUP -> stringResource(R.string.set_type_warmup_short)
    SetType.DROP -> stringResource(R.string.set_type_drop_short)
    SetType.FAILURE -> stringResource(R.string.set_type_failure_short)
}

@Composable
private fun SetHeader(kind: ExerciseKind, unit: WeightUnit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        HeaderCell(stringResource(R.string.col_set), Modifier.width(32.dp), TextAlign.Center)
        HeaderCell(stringResource(R.string.col_previous), Modifier.weight(1.3f))
        if (kind.usesWeight()) HeaderCell(weightHeader(kind, unit), Modifier.weight(1f), TextAlign.Center)
        HeaderCell(stringResource(if (kind == ExerciseKind.DURATION) R.string.col_seconds else R.string.col_reps), Modifier.weight(1f), TextAlign.Center)
        Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun weightHeader(kind: ExerciseKind, unit: WeightUnit): String {
    val unitText = stringResource(if (unit == WeightUnit.KG) R.string.unit_kg else R.string.unit_lb)
    return when (kind) {
        ExerciseKind.WEIGHTED_BODYWEIGHT -> "+$unitText"
        ExerciseKind.ASSISTED_BODYWEIGHT -> "−$unitText"
        else -> unitText
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier, align: TextAlign = TextAlign.Start) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun SetRow(
    label: String,
    set: WorkoutSet,
    previous: WorkoutSet?,
    kind: ExerciseKind,
    unit: WeightUnit,
    ref: SetRef,
    onChange: ((ActiveWorkout) -> ActiveWorkout) -> Unit,
    onEditSet: (SetEditor) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var typeMenu by remember { mutableStateOf(false) }
    val rowColor by animateColorAsState(if (set.isCompleted) MaterialTheme.lift.doneRow else Color.Transparent, label = "row")
    val labelColor = when (set.type) {
        SetType.NORMAL -> MaterialTheme.colorScheme.onSurface
        SetType.WARMUP -> MaterialTheme.lift.warmup
        SetType.DROP -> MaterialTheme.lift.drop
        SetType.FAILURE -> MaterialTheme.lift.failure
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(rowColor)
            .padding(horizontal = 16.dp, vertical = 5.dp),
    ) {
        Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = NumberStyles.cell,
                color = labelColor,
                modifier = Modifier.clickable { typeMenu = true }.padding(vertical = 6.dp, horizontal = 4.dp),
            )
            DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                SetType.entries.forEach { type ->
                    DropdownMenuItem(text = { Text(setTypeName(type)) }, onClick = { typeMenu = false; onChange { it.setType(ref, type) } })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.delete_set)) }, onClick = { typeMenu = false; onChange { it.removeSet(ref) } })
            }
        }
        Text(
            previousText(previous, kind, unit),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1.3f),
        )
        if (kind.usesWeight()) {
            ValueCell(
                text = set.weightKg?.let { formatWeightNumber(it, unit) },
                placeholder = previous?.weightKg?.let { formatWeightNumber(it, unit) },
                completed = set.isCompleted,
                modifier = Modifier.weight(1f),
            ) { onEditSet(SetEditor(SetField.WEIGHT, ref)) }
        }
        ValueCell(
            text = set.amount(kind)?.toString(),
            placeholder = previous?.amount(kind)?.toString(),
            completed = set.isCompleted,
            modifier = Modifier.weight(1f),
        ) { onEditSet(SetEditor(SetField.AMOUNT, ref)) }
        DoneToggle(set.isCompleted) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onChange { it.toggleSet(ref, Instant.now()) }
        }
    }
}

@Composable
private fun ValueCell(text: String?, placeholder: String?, completed: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (completed) Color.Transparent else MaterialTheme.lift.field)
            .clickable(onClick = onClick),
    ) {
        Text(
            text ?: placeholder ?: "–",
            style = NumberStyles.cell,
            color = if (text != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun DoneToggle(done: Boolean, onToggle: () -> Unit) {
    val scale by animateFloatAsState(if (done) 1f else 0.92f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "check")
    val color by animateColorAsState(if (done) MaterialTheme.lift.doneCheck else MaterialTheme.lift.field, label = "checkColor")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .scale(scale)
            .clip(RoundedCornerShape(10.dp))
            .background(color)
            .toggleable(value = done, role = Role.Checkbox, onValueChange = { onToggle() }),
    ) {
        Icon(
            Icons.Default.Check,
            stringResource(R.string.complete_set),
            tint = if (done) MaterialTheme.lift.onDoneCheck else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun previousText(previous: WorkoutSet?, kind: ExerciseKind, unit: WeightUnit): String {
    val weight = previous?.weightKg?.let { formatWeightNumber(it, unit) }
    val amount = previous?.amount(kind)?.let { if (kind == ExerciseKind.DURATION) stringResource(R.string.seconds_short, it) else it.toString() }
    return when {
        weight != null && amount != null -> stringResource(R.string.previous_weight_amount, weight, amount)
        else -> weight ?: amount ?: "–"
    }
}
