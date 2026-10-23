package io.fitgymlog.app.ui.history

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.fitgymlog.app.R
import io.fitgymlog.app.catching
import io.fitgymlog.app.container
import io.fitgymlog.app.data.byId
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.Routine
import io.fitgymlog.app.model.RoutineExercise
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.model.WorkoutSource
import io.fitgymlog.app.model.defaultRestSeconds
import io.fitgymlog.app.ui.ConfirmDialog
import io.fitgymlog.app.ui.NumberStyles
import io.fitgymlog.app.ui.ReadableWidth
import io.fitgymlog.app.ui.Stat
import io.fitgymlog.app.ui.TwoPaneMinWidth
import io.fitgymlog.app.ui.coach.ReviewCard
import io.fitgymlog.app.ui.exercise.metricName
import io.fitgymlog.app.ui.formatClock
import io.fitgymlog.app.ui.formatWeightNumber
import io.fitgymlog.app.ui.lift
import io.fitgymlog.app.ui.localizedName
import io.fitgymlog.app.ui.setTypeName
import io.fitgymlog.app.ui.unitLabel
import io.fitgymlog.app.ui.workout.rpeText
import io.fitgymlog.app.workout.setLine
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** On wide screens the list stays on the left and the chosen workout opens beside it. */
@Composable
fun HistoryScreen(onOpenWorkout: (Long) -> Unit, onOpenExercise: (String) -> Unit) {
    val app = LocalContext.current.container
    val history by app.workouts.history.collectAsStateWithLifecycle(emptyList())
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= TwoPaneMinWidth) {
            val shown = selected?.takeIf { id -> history.any { it.id == id } } ?: history.firstOrNull()?.id
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { HistoryList(history, shown) { selected = it } }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1.2f)) {
                    if (shown != null) {
                        WorkoutDetailScreen(shown, onBack = { selected = null }, onOpenExercise = onOpenExercise, showBack = false)
                    }
                }
            }
        } else {
            HistoryList(history, selectedId = null, onSelect = onOpenWorkout)
        }
    }
}

@Composable
private fun HistoryList(history: List<Workout>, selectedId: Long?, onSelect: (Long) -> Unit) {
    val app = LocalContext.current.container
    val exercises by app.exercises.exercises.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val records by app.records.collectAsStateWithLifecycle()
    ReadableWidth(Modifier.statusBarsPadding()) { gutter ->
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp + gutter, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    stringResource(R.string.tab_history),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            if (history.isEmpty()) {
                item { Text(stringResource(R.string.no_history), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(history, key = { it.id }) { workout ->
                val isSelected = workout.id == selectedId
                Surface(
                    onClick = { onSelect(workout.id) },
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column {
                            Text(workout.title, style = MaterialTheme.typography.titleMedium)
                            Text(dateText(workout), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        WorkoutStats(workout, settings.unit)
                        records[workout.id]?.let { beaten ->
                            NewRecord(beaten.keys.map { exercises.byId(it).localizedName() }.joinToString(", "))
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        workout.exercises.forEach { exercise ->
                            val info = exercises.byId(exercise.exerciseId)
                            Text(
                                pluralStringResource(R.plurals.sets_of_exercise, exercise.sets.size, exercise.sets.size, info.localizedName()),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.padding(bottom = 24.dp)) }
        }
    }
}

private sealed interface StoredWorkout {
    data object Loading : StoredWorkout
    data object Missing : StoredWorkout
    data class Found(val workout: Workout) : StoredWorkout
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutDetailScreen(workoutId: Long, onBack: () -> Unit, onOpenExercise: (String) -> Unit, showBack: Boolean = true) {
    val context = LocalContext.current
    val app = context.container
    val stored by remember(workoutId) {
        app.workouts.observe(workoutId).map { it?.let(StoredWorkout::Found) ?: StoredWorkout.Missing }
    }.collectAsStateWithLifecycle(StoredWorkout.Loading)
    val workout = (stored as? StoredWorkout.Found)?.workout
    val exercises by app.exercises.exercises.collectAsStateWithLifecycle()
    val records by app.records.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var menuOpen by rememberSaveable(workoutId) { mutableStateOf(false) }
    var confirmDelete by rememberSaveable(workoutId) { mutableStateOf(false) }
    var deleting by remember(workoutId) { mutableStateOf(false) }
    var routineSave by rememberSaveable(workoutId) { mutableStateOf(RoutineSave.IDLE) }
    var editingTimes by rememberSaveable(workoutId) { mutableStateOf(false) }
    var ratingEffort by rememberSaveable(workoutId) { mutableStateOf(false) }
    val effortPrompt by app.actions.effortPrompt.collectAsStateWithLifecycle()

    fun saveAsRoutine(current: Workout) {
        if (routineSave == RoutineSave.SAVING || routineSave == RoutineSave.SAVED) return
        routineSave = RoutineSave.SAVING
        val routine = current.toRoutine(settings.defaultRestSeconds) { exercises.byId(it).kind }
        scope.launch {
            val saved = catching { app.inAppScope { app.routines.save(routine) } }.isSuccess
            routineSave = if (saved) RoutineSave.SAVED else RoutineSave.FAILED
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(workout?.title.orEmpty(), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    if (workout != null) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.save_as_routine)) },
                                    enabled = routineSave == RoutineSave.IDLE || routineSave == RoutineSave.FAILED,
                                    onClick = { menuOpen = false; saveAsRoutine(workout) },
                                )
                                DropdownMenuItem(text = { Text(stringResource(R.string.edit_times)) }, onClick = { menuOpen = false; editingTimes = true })
                                DropdownMenuItem(text = { Text(stringResource(R.string.delete_workout)) }, onClick = { menuOpen = false; confirmDelete = true })
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (stored == StoredWorkout.Missing && !deleting) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.workout_not_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val current = workout ?: return@Scaffold
        ReadableWidth(Modifier.padding(padding)) { gutter ->
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 16.dp + gutter)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(dateText(current), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        sourceName(current.source)?.let { source ->
                            Text(stringResource(R.string.imported_from, stringResource(source)), style = MaterialTheme.typography.bodySmall)
                        }
                        when (routineSave) {
                            RoutineSave.SAVED -> Text(stringResource(R.string.saved_as_routine), color = MaterialTheme.colorScheme.primary)
                            RoutineSave.FAILED -> Text(stringResource(R.string.routine_save_failed), color = MaterialTheme.colorScheme.error)
                            RoutineSave.IDLE, RoutineSave.SAVING -> Unit
                        }
                        WorkoutStats(current, settings.unit, onEffort = { ratingEffort = true })
                        if (current.notes.isNotBlank()) Text(current.notes)
                    }
                }
                item { ReviewCard(current) }
                val firstBlocks = current.exercises.distinctBy { it.exerciseId }.map { it.key }.toSet()
                items(current.exercises, key = { it.key }) { exercise ->
                    val info = exercises.byId(exercise.exerciseId)
                    val beaten = records[current.id]?.get(exercise.exerciseId)?.takeIf { exercise.key in firstBlocks }
                    Surface(
                        onClick = { onOpenExercise(exercise.exerciseId) },
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(info.localizedName(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            beaten?.let { metrics -> NewRecord(metrics.map { stringResource(metricName(it)) }.joinToString(", ")) }
                            var normal = 0
                            exercise.sets.forEach { set ->
                                if (set.type == SetType.NORMAL) normal++
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (set.type == SetType.NORMAL) normal.toString() else setTypeName(set.type),
                                        style = NumberStyles.cell,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.width(72.dp),
                                    )
                                    Text(setLine(context, info, set.weightKg, set.reps, settings.unit, set.durationSeconds, set.level, set.speedKmh, set.distanceMeters) + rpeSuffix(set.rpe), style = NumberStyles.cell)
                                }
                            }
                            if (exercise.notes.isNotBlank()) Text(exercise.notes, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item { Spacer(Modifier.padding(bottom = 24.dp)) }
            }
        }
    }

    if (workout != null && (ratingEffort || effortPrompt == workout.id)) {
        val done = {
            ratingEffort = false
            if (app.actions.effortPrompt.value == workout.id) app.actions.effortPrompt.value = null
        }
        EffortSheet(
            initial = workout.effort,
            onSave = { effort ->
                app.scope.launch { app.actions.rateEffort(workout.id, effort) }
                done()
            },
            onSkip = done,
            onClear = workout.effort?.let {
                {
                    app.scope.launch { app.actions.rateEffort(workout.id, null) }
                    done()
                }
            },
        )
    }

    if (editingTimes && workout != null) {
        EditTimesDialog(
            workout = workout,
            onSave = { start, end -> app.inAppScope { app.actions.retime(workout.id, start, end) } },
            onDismiss = { editingTimes = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_workout),
            text = stringResource(R.string.delete_workout_text),
            confirm = stringResource(R.string.delete),
            onConfirm = {
                deleting = true
                app.scope.launch {
                    app.actions.deleteWorkout(workoutId)
                    withContext(Dispatchers.Main) { onBack() }
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun NewRecord(what: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.lift.doneCheck, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.record_new, what), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WorkoutStats(workout: Workout, unit: WeightUnit, onEffort: (() -> Unit)? = null) {
    val sets = workout.exercises.flatMap { it.sets }
    val volumeKg = sets.sumOf { (it.weightKg ?: 0.0) * (it.reps ?: 0) }
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        workout.endedAt?.let { Stat(stringResource(R.string.duration), formatClock(Duration.between(workout.startedAt, it))) }
        Stat(stringResource(R.string.volume), "${formatWeightNumber(volumeKg, unit)} ${unitLabel(unit)}")
        Stat(stringResource(R.string.sets), sets.size.toString())
        // On the workout itself, tap to rate how hard it felt or change it, whenever the user likes; lists only show a rating.
        if (onEffort != null) {
            Box(Modifier.clickable(onClickLabel = stringResource(R.string.effort_rate), onClick = onEffort)) {
                Stat(stringResource(R.string.effort), workout.effort?.let { "$it/10" } ?: stringResource(R.string.effort_unrated), highlight = workout.effort == null)
            }
        } else {
            workout.effort?.let { Stat(stringResource(R.string.effort), "$it/10") }
        }
    }
}

@StringRes
private fun sourceName(source: WorkoutSource): Int? = when (source) {
    WorkoutSource.APP -> null
    WorkoutSource.HEVY -> R.string.source_hevy
    WorkoutSource.STRONG -> R.string.source_strong
    WorkoutSource.BACKUP -> R.string.source_backup
}

private fun dateText(workout: Workout): String =
    workout.startedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))

private fun Workout.toRoutine(restSeconds: Int, kindOf: (String) -> ExerciseKind) = Routine(
    name = title,
    exercises = exercises.map { exercise ->
        val working = exercise.sets.filter { it.type == SetType.NORMAL }.ifEmpty { exercise.sets }
        RoutineExercise(
            exerciseId = exercise.exerciseId,
            sets = working.size,
            reps = working.mapNotNull { it.reps }.maxOrNull(),
            weightKg = working.mapNotNull { it.weightKg }.maxOrNull(),
            restSeconds = if (exercise.restSeconds > 0) exercise.restSeconds else kindOf(exercise.exerciseId).defaultRestSeconds(restSeconds),
            durationSeconds = working.mapNotNull { it.durationSeconds }.maxOrNull(),
            level = working.mapNotNull { it.level }.maxOrNull(),
            speedKmh = working.mapNotNull { it.speedKmh }.maxOrNull(),
        )
    },
)

fun exerciseSessions(history: List<Workout>, exerciseId: String): List<Pair<Workout, List<WorkoutSet>>> =
    history.mapNotNull { workout ->
        val sets = workout.exercises.filter { it.exerciseId == exerciseId }.flatMap { it.sets }
        if (sets.isEmpty()) null else workout to sets
    }

@Composable
fun SessionLines(context: Context, exercise: Exercise, sessions: List<Pair<Workout, List<WorkoutSet>>>, unit: WeightUnit) {
    sessions.forEach { (workout, sets) ->
        Column(Modifier.padding(vertical = 6.dp)) {
            Text(dateText(workout), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(sets.joinToString(", ") { setLine(context, exercise, it.weightKg, it.reps, unit, it.durationSeconds, it.level, it.speedKmh, it.distanceMeters) + rpeSuffix(it.rpe) })
        }
        HorizontalDivider()
    }
}

private enum class RoutineSave { IDLE, SAVING, SAVED, FAILED }

/** " @8" after a set logged with an RPE, nothing otherwise. */
private fun rpeSuffix(rpe: Double?): String = rpe?.let { " @${rpeText(it)}" }.orEmpty()
