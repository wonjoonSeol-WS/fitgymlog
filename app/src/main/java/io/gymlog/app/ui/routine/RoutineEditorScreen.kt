package io.gymlog.app.ui.routine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gymlog.app.R
import io.gymlog.app.container
import io.gymlog.app.model.Exercise
import io.gymlog.app.model.Routine
import io.gymlog.app.model.RoutineExercise
import io.gymlog.app.model.WeightUnit
import io.gymlog.app.ui.ChoiceDialog
import io.gymlog.app.ui.ConfirmDialog
import io.gymlog.app.ui.NumberField
import io.gymlog.app.ui.ReadableWidth
import io.gymlog.app.ui.exercise.ExercisePickerDialog
import io.gymlog.app.ui.formatRest
import io.gymlog.app.ui.formatWeightNumber
import io.gymlog.app.ui.fromDisplayWeight
import io.gymlog.app.ui.localizedName
import io.gymlog.app.ui.workout.REST_CHOICES
import io.gymlog.app.ui.workout.usesWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineEditorScreen(routineId: Long, onDone: () -> Unit) {
    val app = LocalContext.current.container
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val exercises by app.exercises.exercises.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    val planned = remember { mutableStateListOf<RoutineExercise>() }
    var loaded by remember { mutableStateOf(routineId == 0L) }
    var picking by remember { mutableStateOf(false) }
    var restEditor by remember { mutableStateOf<Int?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(routineId) {
        if (routineId != 0L) {
            app.routines.find(routineId)?.let { routine ->
                name = routine.name
                planned.clear()
                planned.addAll(routine.exercises)
            }
            loaded = true
        }
    }

    fun save() {
        val routine = Routine(id = routineId, name = name.trim(), exercises = planned.toList())
        app.scope.launch {
            app.routines.save(routine)
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (routineId == 0L) R.string.new_routine else R.string.edit_routine)) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    TextButton(onClick = ::save, enabled = loaded && name.isNotBlank() && planned.isNotEmpty()) { Text(stringResource(R.string.save)) }
                    if (routineId != 0L) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.delete_routine)) }, onClick = { menuOpen = false; confirmDelete = true })
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        ReadableWidth(Modifier.padding(padding)) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.routine_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                itemsIndexed(planned) { index, item ->
                    PlannedExerciseCard(
                        item = item,
                        exercise = exercises.firstOrNull { it.id == item.exerciseId } ?: app.exercises.find(item.exerciseId),
                        unit = settings.unit,
                        canMoveUp = index > 0,
                        canMoveDown = index < planned.lastIndex,
                        onChange = { planned[index] = it },
                        onMove = { offset -> planned.add(index + offset, planned.removeAt(index)) },
                        onRemove = { planned.removeAt(index) },
                        onEditRest = { restEditor = index },
                    )
                }
                item {
                    OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.add_exercise))
                    }
                }
            }
        }
    }

    if (picking) {
        ExercisePickerDialog(
            exercises = exercises,
            onPick = { id ->
                planned += RoutineExercise(id, sets = 3, reps = null, weightKg = null, restSeconds = settings.defaultRestSeconds)
                picking = false
            },
            onCreate = { new ->
                picking = false
                app.scope.launch {
                    val created = app.exercises.createCustom(new.name, new.kind, listOfNotNull(new.link))
                    withContext(Dispatchers.Main) {
                        planned += RoutineExercise(created.id, sets = 3, reps = null, weightKg = null, restSeconds = settings.defaultRestSeconds)
                    }
                }
            },
            onDismiss = { picking = false },
        )
    }

    restEditor?.let { index ->
        ChoiceDialog(
            title = stringResource(R.string.rest_timer),
            options = REST_CHOICES,
            selected = planned.getOrNull(index)?.restSeconds ?: 0,
            label = { if (it == 0) stringResource(R.string.rest_off) else formatRest(it) },
            onSelect = { seconds -> planned.getOrNull(index)?.let { planned[index] = it.copy(restSeconds = seconds) } },
            onDismiss = { restEditor = null },
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_routine),
            text = null,
            confirm = stringResource(R.string.delete),
            onConfirm = {
                app.scope.launch {
                    app.routines.delete(routineId)
                    withContext(Dispatchers.Main) { onDone() }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun PlannedExerciseCard(
    item: RoutineExercise,
    exercise: Exercise,
    unit: WeightUnit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onChange: (RoutineExercise) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    onEditRest: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(exercise.localizedName(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (canMoveUp) DropdownMenuItem(text = { Text(stringResource(R.string.move_up)) }, onClick = { menuOpen = false; onMove(-1) })
                        if (canMoveDown) DropdownMenuItem(text = { Text(stringResource(R.string.move_down)) }, onClick = { menuOpen = false; onMove(1) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.remove_exercise)) }, onClick = { menuOpen = false; onRemove() })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Labeled(stringResource(R.string.sets), Modifier.weight(1f)) {
                    NumberField(item.sets.toString(), { text -> text.toIntOrNull()?.takeIf { it in 1..20 }?.let { onChange(item.copy(sets = it)) } }, decimal = false)
                }
                Labeled(stringResource(R.string.col_reps), Modifier.weight(1f)) {
                    NumberField(item.reps?.toString().orEmpty(), { onChange(item.copy(reps = it.toIntOrNull())) }, decimal = false, placeholder = "-")
                }
                if (exercise.kind.usesWeight()) {
                    Labeled(stringResource(if (unit == WeightUnit.KG) R.string.unit_kg else R.string.unit_lb), Modifier.weight(1f)) {
                        NumberField(
                            item.weightKg?.let { formatWeightNumber(it, unit) }.orEmpty(),
                            { text -> onChange(item.copy(weightKg = text.toDoubleOrNull()?.let { fromDisplayWeight(it, unit) })) },
                            placeholder = stringResource(R.string.previous_short),
                        )
                    }
                }
                Labeled(stringResource(R.string.rest), Modifier.weight(1f)) {
                    Text(
                        if (item.restSeconds == 0) stringResource(R.string.rest_off) else formatRest(item.restSeconds),
                        modifier = Modifier.clickable(onClick = onEditRest).padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Labeled(label: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}
