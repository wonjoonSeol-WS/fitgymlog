package io.fitgymlog.app.ui.workout

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.fitgymlog.app.R
import io.fitgymlog.app.container
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.model.WorkoutExercise
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.ui.ChoiceDialog
import io.fitgymlog.app.ui.ConfirmDialog
import io.fitgymlog.app.ui.NumberField
import io.fitgymlog.app.ui.ReadableWidth
import io.fitgymlog.app.ui.exercise.ExercisePickerDialog
import io.fitgymlog.app.ui.formatClock
import io.fitgymlog.app.ui.formatRest
import io.fitgymlog.app.ui.formatWeightNumber
import io.fitgymlog.app.ui.fromDisplayWeight
import io.fitgymlog.app.ui.localizedName
import io.fitgymlog.app.ui.rememberNow
import io.fitgymlog.app.workout.ActiveWorkout
import io.fitgymlog.app.workout.SetRef
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

private sealed interface PickerTarget {
    data object Add : PickerTarget
    data class Replace(val index: Int) : PickerTarget
}

val REST_CHOICES = listOf(0, 30, 60, 90, 120, 150, 180, 240, 300)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveWorkoutScreen(onBack: () -> Unit, onFinished: (Long?) -> Unit, onOpenExercise: (String) -> Unit) {
    val app = LocalContext.current.container
    val active by app.workouts.active.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val exercises by app.exercises.exercises.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val workout = active ?: run {
        // Only when this screen is in front: after Finish it is still composed during the exit transition.
        LaunchedEffect(Unit) { if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onBack() }
        return
    }
    val now = rememberNow()
    var picker by remember { mutableStateOf<PickerTarget?>(null) }
    var restEditor by remember { mutableStateOf<Int?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var confirmFinish by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    fun change(transform: (ActiveWorkout) -> ActiveWorkout) {
        app.scope.launch { app.workouts.update(transform) }
    }

    val exerciseIds = workout.exercises.map { it.exerciseId }.distinct()
    val previous by produceState(emptyMap<String, List<WorkoutSet>>(), exerciseIds) {
        value = exerciseIds.associateWith { app.workouts.previousSets(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(Modifier.clickable { renaming = true }) {
                        Text(workout.workout.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            formatClock(Duration.between(workout.workout.startedAt, now)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    Button(onClick = { confirmFinish = true }) { Text(stringResource(R.string.finish)) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.discard_workout)) },
                                onClick = { menuOpen = false; confirmDiscard = true },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            workout.restEndsAt?.let { endsAt ->
                RestBar(
                    remaining = Duration.between(now, endsAt),
                    onAdjust = { seconds -> change { it.adjustRest(Duration.ofSeconds(seconds), Instant.now()) } },
                    onSkip = { change { it.restOver() } },
                )
            }
        },
    ) { padding ->
        ReadableWidth(Modifier.padding(padding)) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                item { SummaryRow(workout, settings.unit) }
                itemsIndexed(workout.exercises, key = { _, exercise -> exercise.key }) { index, exercise ->
                    ExerciseCard(
                        index = index,
                        exercise = exercise,
                        info = exercises.firstOrNull { it.id == exercise.exerciseId } ?: app.exercises.find(exercise.exerciseId),
                        previous = previous[exercise.exerciseId].orEmpty(),
                        focused = index == workout.focus,
                        isLast = index == workout.exercises.lastIndex,
                        unit = settings.unit,
                        onChange = ::change,
                        onFocus = { change { it.focusExercise(index) } },
                        onReplace = { picker = PickerTarget.Replace(index) },
                        onEditRest = { restEditor = index },
                        onOpenExercise = { onOpenExercise(exercise.exerciseId) },
                    )
                }
                item {
                    OutlinedButton(onClick = { picker = PickerTarget.Add }, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.add_exercise))
                    }
                }
            }
        }
    }

    picker?.let { target ->
        ExercisePickerDialog(
            exercises = exercises,
            onPick = { id ->
                picker = null
                app.scope.launch {
                    when (target) {
                        PickerTarget.Add -> app.actions.addExercise(id)
                        is PickerTarget.Replace -> app.actions.replaceExercise(target.index, id)
                    }
                }
            },
            onCreate = { new ->
                picker = null
                app.scope.launch {
                    val created = app.exercises.createCustom(new.name, new.kind, listOfNotNull(new.link))
                    when (target) {
                        PickerTarget.Add -> app.actions.addExercise(created.id)
                        is PickerTarget.Replace -> app.actions.replaceExercise(target.index, created.id)
                    }
                }
            },
            onDismiss = { picker = null },
        )
    }

    restEditor?.let { index ->
        ChoiceDialog(
            title = stringResource(R.string.rest_timer),
            options = REST_CHOICES,
            selected = workout.exercises.getOrNull(index)?.restSeconds ?: 0,
            label = { if (it == 0) stringResource(R.string.rest_off) else formatRest(it) },
            onSelect = { seconds -> change { it.setRestSeconds(index, seconds) } },
            onDismiss = { restEditor = null },
        )
    }

    if (renaming) {
        RenameDialog(workout.workout.title, onRename = { title -> change { it.rename(title) } }, onDismiss = { renaming = false })
    }

    if (confirmFinish) {
        val open = workout.exercises.sumOf { exercise -> exercise.sets.count { !it.isCompleted } }
        ConfirmDialog(
            title = stringResource(R.string.finish_workout_title),
            text = if (open > 0) pluralStringResource(R.plurals.finish_drops_open_sets, open, open) else null,
            confirm = stringResource(R.string.finish),
            onConfirm = {
                app.scope.launch {
                    val saved = app.actions.finish()
                    withContext(Dispatchers.Main) { onFinished(saved?.id) }
                }
            },
            onDismiss = { confirmFinish = false },
        )
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.discard_workout_title),
            text = stringResource(R.string.discard_workout_text),
            confirm = stringResource(R.string.discard),
            onConfirm = {
                app.scope.launch {
                    app.actions.discard()
                    withContext(Dispatchers.Main) { onFinished(null) }
                }
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

@Composable
private fun SummaryRow(workout: ActiveWorkout, unit: WeightUnit) {
    val done = workout.exercises.flatMap { it.sets }.filter { it.isCompleted }
    val volumeKg = done.sumOf { (it.weightKg ?: 0.0) * (it.reps ?: 0) }
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)) {
        Stat(stringResource(R.string.volume), "${formatWeightNumber(volumeKg, unit)} ${stringResource(if (unit == WeightUnit.KG) R.string.unit_kg else R.string.unit_lb)}")
        Stat(stringResource(R.string.sets), done.size.toString())
    }
}

@Composable
fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ExerciseCard(
    index: Int,
    exercise: WorkoutExercise,
    info: Exercise,
    previous: List<WorkoutSet>,
    focused: Boolean,
    isLast: Boolean,
    unit: WeightUnit,
    onChange: ((ActiveWorkout) -> ActiveWorkout) -> Unit,
    onFocus: () -> Unit,
    onReplace: () -> Unit,
    onEditRest: () -> Unit,
    onOpenExercise: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        border = if (focused) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(onClick = onFocus)) {
                    Text(info.localizedName(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = (if (focused) stringResource(R.string.band_focus) + "  " else "") +
                            stringResource(R.string.rest_label, if (exercise.restSeconds == 0) stringResource(R.string.rest_off) else formatRest(exercise.restSeconds)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable(onClick = onEditRest),
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.form_guide)) }, onClick = { menuOpen = false; onOpenExercise() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.replace_exercise)) }, onClick = { menuOpen = false; onReplace() })
                        if (index > 0) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.move_up)) }, onClick = { menuOpen = false; onChange { it.moveExercise(index, index - 1) } })
                        }
                        if (!isLast) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.move_down)) }, onClick = { menuOpen = false; onChange { it.moveExercise(index, index + 1) } })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.rest_timer)) }, onClick = { menuOpen = false; onEditRest() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.remove_exercise)) }, onClick = { menuOpen = false; onChange { it.removeExercise(index) } })
                    }
                }
            }
            SetHeader(info.kind, unit)
            var normalNumber = 0
            exercise.sets.forEachIndexed { setIndex, set ->
                if (set.type == SetType.NORMAL) normalNumber++
                SetRow(
                    label = setLabel(set.type, normalNumber),
                    set = set,
                    previous = previous.getOrNull(setIndex),
                    kind = info.kind,
                    unit = unit,
                    ref = SetRef(index, setIndex),
                    onChange = onChange,
                )
            }
            TextButton(onClick = { onChange { it.addSet(index) } }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.add_set))
            }
        }
    }
}

@Composable
private fun setLabel(type: SetType, normalNumber: Int): String = when (type) {
    SetType.NORMAL -> normalNumber.toString()
    SetType.WARMUP -> stringResource(R.string.set_type_warmup_short)
    SetType.DROP -> stringResource(R.string.set_type_drop_short)
    SetType.FAILURE -> stringResource(R.string.set_type_failure_short)
}

@Composable
private fun SetHeader(kind: ExerciseKind, unit: WeightUnit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        HeaderCell(stringResource(R.string.col_set), Modifier.width(40.dp))
        HeaderCell(stringResource(R.string.col_previous), Modifier.weight(1.3f))
        if (kind.usesWeight()) {
            HeaderCell(weightHeader(kind, unit), Modifier.weight(1f))
        }
        HeaderCell(stringResource(if (kind == ExerciseKind.DURATION) R.string.col_seconds else R.string.col_reps), Modifier.weight(1f))
        Spacer(Modifier.width(48.dp))
    }
}

@Composable
private fun weightHeader(kind: ExerciseKind, unit: WeightUnit): String {
    val unitText = stringResource(if (unit == WeightUnit.KG) R.string.unit_kg else R.string.unit_lb)
    return when (kind) {
        ExerciseKind.WEIGHTED_BODYWEIGHT -> "+$unitText"
        ExerciseKind.ASSISTED_BODYWEIGHT -> "-$unitText"
        else -> unitText
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
        maxLines = 1,
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
) {
    var typeMenu by remember { mutableStateOf(false) }
    val background = if (set.isCompleted) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerLow
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().background(background, RoundedCornerShape(8.dp)).padding(vertical = 2.dp),
    ) {
        Box(Modifier.width(34.dp)) {
            Text(
                label,
                fontWeight = FontWeight.Bold,
                color = if (set.type == SetType.NORMAL) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.clickable { typeMenu = true }.padding(8.dp),
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
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1.3f),
        )
        if (kind.usesWeight()) {
            NumberField(
                value = set.weightKg?.let { formatWeightNumber(it, unit) }.orEmpty(),
                onValueChange = { text -> onChange { it.setWeight(ref, text.toDoubleOrNull()?.let { value -> fromDisplayWeight(value, unit) }) } },
                placeholder = previous?.weightKg?.let { formatWeightNumber(it, unit) } ?: "-",
                modifier = Modifier.weight(1f),
            )
        }
        if (kind == ExerciseKind.DURATION) {
            NumberField(
                value = set.durationSeconds?.toString().orEmpty(),
                onValueChange = { text -> onChange { it.setDuration(ref, text.toIntOrNull()) } },
                decimal = false,
                placeholder = previous?.durationSeconds?.toString() ?: "-",
                modifier = Modifier.weight(1f),
            )
        } else {
            NumberField(
                value = set.reps?.toString().orEmpty(),
                onValueChange = { text -> onChange { it.setReps(ref, text.toIntOrNull()) } },
                decimal = false,
                placeholder = previous?.reps?.toString() ?: "-",
                modifier = Modifier.weight(1f),
            )
        }
        FilledIconToggleButton(
            checked = set.isCompleted,
            onCheckedChange = { onChange { it.toggleSet(ref, Instant.now()) } },
            modifier = Modifier.size(40.dp),
        ) {
            Icon(Icons.Default.Check, stringResource(R.string.complete_set))
        }
    }
}

@Composable
fun setTypeName(type: SetType): String = stringResource(
    when (type) {
        SetType.NORMAL -> R.string.set_type_normal
        SetType.WARMUP -> R.string.set_type_warmup
        SetType.DROP -> R.string.set_type_drop
        SetType.FAILURE -> R.string.set_type_failure
    },
)

@Composable
private fun previousText(previous: WorkoutSet?, kind: ExerciseKind, unit: WeightUnit): String {
    if (previous == null) return "-"
    val weight = previous.weightKg?.let { formatWeightNumber(it, unit) }
    val amount = if (kind == ExerciseKind.DURATION) previous.durationSeconds?.let { "${it}s" } else previous.reps?.toString()
    return listOfNotNull(weight, amount).joinToString(" x ").ifEmpty { "-" }
}

fun ExerciseKind.usesWeight(): Boolean = this != ExerciseKind.BODYWEIGHT_REPS && this != ExerciseKind.DURATION

@Composable
private fun RestBar(remaining: Duration, onAdjust: (Long) -> Unit, onSkip: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 3.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.resting), style = MaterialTheme.typography.labelMedium)
                Text(formatClock(remaining), style = MaterialTheme.typography.headlineSmall)
            }
            OutlinedButton(onClick = { onAdjust(-15) }) { Text("-15") }
            OutlinedButton(onClick = { onAdjust(15) }) { Text("+15") }
            Button(onClick = onSkip) { Text(stringResource(R.string.skip)) }
        }
    }
}

@Composable
private fun RenameDialog(title: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(title) }
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
