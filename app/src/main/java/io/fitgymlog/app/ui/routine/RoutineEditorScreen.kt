package io.fitgymlog.app.ui.routine

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.fitgymlog.app.AppContainer
import io.fitgymlog.app.R
import io.fitgymlog.app.container
import io.fitgymlog.app.data.byId
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.NewExercise
import io.fitgymlog.app.model.Routine
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.ui.ChoiceDialog
import io.fitgymlog.app.ui.ConfirmDialog
import io.fitgymlog.app.ui.NumberField
import io.fitgymlog.app.ui.ReadableWidth
import io.fitgymlog.app.ui.exercise.ExercisePickerDialog
import io.fitgymlog.app.ui.formatRest
import io.fitgymlog.app.ui.formatWeightNumber
import io.fitgymlog.app.ui.fromDisplayWeight
import io.fitgymlog.app.ui.localizedName
import io.fitgymlog.app.ui.REST_CHOICES
import io.fitgymlog.app.ui.unitLabel
import io.fitgymlog.app.ui.usesWeight
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineEditorScreen(routineId: Long, onDone: () -> Unit) {
    val app = LocalContext.current.container
    val editor = viewModel { RoutineEditorViewModel(routineId, AppRoutineStore(app), createSavedStateHandle()) }
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val exercises by app.exercises.exercises.collectAsStateWithLifecycle()
    val draft = editor.draft
    val phase = editor.phase
    var picking by rememberSaveable { mutableStateOf(false) }
    var restEditor by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val failedText = stringResource(R.string.routine_save_failed)
    val currentOnDone by rememberUpdatedState(onDone)

    LaunchedEffect(phase) {
        if (phase == EditorPhase.DONE) currentOnDone()
    }
    LaunchedEffect(editor.failed) {
        if (editor.failed) {
            snackbar.showSnackbar(failedText)
            editor.failureShown()
        }
    }

    fun leave() {
        if (phase == EditorPhase.EDITING && draft.hasChanges) confirmDiscard = true else editor.close()
    }
    BackHandler(onBack = ::leave)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (routineId == 0L) R.string.new_routine else R.string.edit_routine)) },
                navigationIcon = { IconButton(onClick = ::leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    TextButton(onClick = editor::save, enabled = phase == EditorPhase.EDITING && draft.canSave) { Text(stringResource(R.string.save)) }
                    if (routineId != 0L) {
                        Box {
                            IconButton(onClick = { menuOpen = true }, enabled = phase == EditorPhase.EDITING) {
                                Icon(Icons.Default.MoreVert, stringResource(R.string.more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.delete_routine)) }, onClick = { menuOpen = false; confirmDelete = true })
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (phase == EditorPhase.LOADING || phase == EditorPhase.MISSING) return@Scaffold
        ReadableWidth(Modifier.padding(padding)) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                item {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { name -> editor.edit { it.rename(name) } },
                        label = { Text(stringResource(R.string.routine_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                itemsIndexed(draft.planned, key = { _, item -> item.key }) { index, item ->
                    PlannedExerciseCard(
                        item = item,
                        exercise = exercises.byId(item.exerciseId),
                        unit = settings.unit,
                        canMoveUp = index > 0,
                        canMoveDown = index < draft.planned.lastIndex,
                        onChange = { transform -> editor.edit { it.update(item.key, transform) } },
                        onMove = { offset -> editor.edit { it.move(item.key, offset) } },
                        onRemove = { editor.edit { it.remove(item.key) } },
                        onEditRest = { restEditor = item.key },
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
                editor.add(id, settings.defaultRestSeconds)
                picking = false
            },
            onCreate = { new ->
                picking = false
                editor.createAndAdd(new, settings.defaultRestSeconds)
            },
            onDismiss = { picking = false },
        )
    }

    restEditor?.let { key ->
        ChoiceDialog(
            title = stringResource(R.string.rest_timer),
            options = REST_CHOICES,
            selected = draft.planned.firstOrNull { it.key == key }?.restSeconds ?: 0,
            label = { if (it == 0) stringResource(R.string.rest_off) else formatRest(it) },
            onSelect = { seconds -> editor.edit { it.update(key) { planned -> planned.copy(restSeconds = seconds) } } },
            onDismiss = { restEditor = null },
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_routine),
            text = stringResource(R.string.delete_routine_text, draft.savedName),
            confirm = stringResource(R.string.delete),
            onConfirm = editor::delete,
            onDismiss = { confirmDelete = false },
        )
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.routine_discard_title),
            text = stringResource(R.string.routine_discard_text),
            confirm = stringResource(R.string.discard),
            onConfirm = editor::close,
            onDismiss = { confirmDiscard = false },
        )
    }

    if (phase == EditorPhase.MISSING) {
        AlertDialog(
            onDismissRequest = editor::close,
            text = { Text(stringResource(R.string.routine_not_found)) },
            confirmButton = {
                if (editor.draft.canSave) {
                    TextButton(onClick = editor::saveAsNew) { Text(stringResource(R.string.routine_save_as_new)) }
                } else {
                    TextButton(onClick = editor::close) { Text(stringResource(R.string.close)) }
                }
            },
            dismissButton = {
                if (editor.draft.canSave) TextButton(onClick = editor::close) { Text(stringResource(R.string.discard)) }
            },
        )
    }
}

private class AppRoutineStore(private val app: AppContainer) : RoutineEditorStore {
    override suspend fun find(id: Long): Routine? = app.routines.find(id)

    override fun exists(id: Long): Flow<Boolean> = app.routines.routines.map { all -> all.any { it.id == id } }.distinctUntilChanged()

    override suspend fun save(routine: Routine) {
        app.inAppScope { app.routines.save(routine) }
    }

    override suspend fun delete(id: Long) = app.inAppScope { app.routines.delete(id) }

    override suspend fun createExercise(exercise: NewExercise): String =
        app.inAppScope { app.exercises.createCustom(exercise).id }
}

@Composable
private fun PlannedExerciseCard(
    item: PlannedExercise,
    exercise: Exercise,
    unit: WeightUnit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onChange: ((PlannedExercise) -> PlannedExercise) -> Unit,
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
                    NumberField(item.sets.toString(), { text -> plannedSets(text)?.let { sets -> onChange { it.copy(sets = sets) } } }, decimal = false)
                }
                Labeled(stringResource(R.string.col_reps), Modifier.weight(1f)) {
                    NumberField(item.reps?.toString().orEmpty(), { text -> onChange { it.copy(reps = text.toIntOrNull()) } }, decimal = false, placeholder = "-")
                }
                if (exercise.kind.usesWeight()) {
                    Labeled(unitLabel(unit), Modifier.weight(1f)) {
                        NumberField(
                            item.weightKg?.let { formatWeightNumber(it, unit) }.orEmpty(),
                            { text -> onChange { it.copy(weightKg = text.toDoubleOrNull()?.let { value -> fromDisplayWeight(value, unit) }) } },
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
