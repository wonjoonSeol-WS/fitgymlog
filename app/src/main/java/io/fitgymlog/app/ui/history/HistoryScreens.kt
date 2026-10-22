package io.fitgymlog.app.ui.history

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.fitgymlog.app.R
import io.fitgymlog.app.container
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.Routine
import io.fitgymlog.app.model.RoutineExercise
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.model.WorkoutSource
import io.fitgymlog.app.ui.ConfirmDialog
import io.fitgymlog.app.ui.ReadableWidth
import io.fitgymlog.app.ui.formatClock
import io.fitgymlog.app.ui.formatWeightNumber
import io.fitgymlog.app.ui.localizedName
import io.fitgymlog.app.ui.workout.Stat
import io.fitgymlog.app.ui.workout.setTypeName
import io.fitgymlog.app.workout.setLine
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onOpenWorkout: (Long) -> Unit) {
    val app = LocalContext.current.container
    val history by app.workouts.history.collectAsStateWithLifecycle(emptyList())
    val exercises by app.exercises.exercises.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_history)) }) }) { padding ->
        ReadableWidth(Modifier.padding(padding)) {
            if (history.isEmpty()) {
                Text(stringResource(R.string.no_history), modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                items(history, key = { it.id }) { workout ->
                    Card(onClick = { onOpenWorkout(workout.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(workout.title, style = MaterialTheme.typography.titleMedium)
                            Text(dateText(workout), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            WorkoutStats(workout, settings.unit)
                            workout.exercises.forEach { exercise ->
                                val info = exercises.firstOrNull { it.id == exercise.exerciseId } ?: app.exercises.find(exercise.exerciseId)
                                Text(pluralStringResource(R.plurals.sets_of_exercise, exercise.sets.size, exercise.sets.size, info.localizedName()))
                            }
                        }
                    }
                }
                item { Spacer(Modifier.padding(bottom = 24.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutDetailScreen(workoutId: Long, onBack: () -> Unit, onOpenExercise: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.container
    val workout by remember(workoutId) { app.workouts.observe(workoutId) }.collectAsStateWithLifecycle(null)
    val exercises by app.exercises.exercises.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var savedAsRoutine by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(workout?.title.orEmpty()) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.save_as_routine)) },
                                onClick = {
                                    menuOpen = false
                                    workout?.let { current ->
                                        app.scope.launch { app.routines.save(current.toRoutine(settings.defaultRestSeconds)) }
                                        savedAsRoutine = true
                                    }
                                },
                            )
                            DropdownMenuItem(text = { Text(stringResource(R.string.delete_workout)) }, onClick = { menuOpen = false; confirmDelete = true })
                        }
                    }
                },
            )
        },
    ) { padding ->
        val current = workout ?: return@Scaffold
        ReadableWidth(Modifier.padding(padding)) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(dateText(current), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (current.source != WorkoutSource.APP) {
                            Text(stringResource(R.string.imported_from, current.source.name.lowercase().replaceFirstChar { it.uppercase() }), style = MaterialTheme.typography.bodySmall)
                        }
                        if (savedAsRoutine) Text(stringResource(R.string.saved_as_routine), color = MaterialTheme.colorScheme.primary)
                        WorkoutStats(current, settings.unit)
                        if (current.notes.isNotBlank()) Text(current.notes)
                    }
                }
                items(current.exercises, key = { it.key }) { exercise ->
                    val info = exercises.firstOrNull { it.id == exercise.exerciseId } ?: app.exercises.find(exercise.exerciseId)
                    Card(onClick = { onOpenExercise(exercise.exerciseId) }) {
                        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(info.localizedName(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            var normal = 0
                            exercise.sets.forEach { set ->
                                if (set.type == SetType.NORMAL) normal++
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (set.type == SetType.NORMAL) normal.toString() else setTypeName(set.type),
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.width(72.dp),
                                    )
                                    Text(setLine(context, info.kind, set.weightKg, set.reps, settings.unit))
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

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_workout),
            text = stringResource(R.string.delete_workout_text),
            confirm = stringResource(R.string.delete),
            onConfirm = {
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
private fun WorkoutStats(workout: Workout, unit: WeightUnit) {
    val sets = workout.exercises.flatMap { it.sets }
    val volumeKg = sets.sumOf { (it.weightKg ?: 0.0) * (it.reps ?: 0) }
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        workout.endedAt?.let { Stat(stringResource(R.string.duration), formatClock(Duration.between(workout.startedAt, it))) }
        Stat(stringResource(R.string.volume), "${formatWeightNumber(volumeKg, unit)} ${stringResource(if (unit == WeightUnit.KG) R.string.unit_kg else R.string.unit_lb)}")
        Stat(stringResource(R.string.sets), sets.size.toString())
    }
}

private fun dateText(workout: Workout): String =
    workout.startedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))

private fun Workout.toRoutine(restSeconds: Int) = Routine(
    name = title,
    exercises = exercises.map { exercise ->
        val working = exercise.sets.filter { it.type == SetType.NORMAL }.ifEmpty { exercise.sets }
        RoutineExercise(
            exerciseId = exercise.exerciseId,
            sets = working.size,
            reps = working.mapNotNull { it.reps }.maxOrNull(),
            weightKg = working.mapNotNull { it.weightKg }.maxOrNull(),
            restSeconds = if (exercise.restSeconds > 0) exercise.restSeconds else restSeconds,
        )
    },
)

/** Used by the exercise screen: the most recent sessions of one exercise, newest first. */
fun recentSessions(history: List<Workout>, exerciseId: String, limit: Int = 10): List<Pair<Workout, List<WorkoutSet>>> =
    history.mapNotNull { workout ->
        val sets = workout.exercises.filter { it.exerciseId == exerciseId }.flatMap { it.sets }
        if (sets.isEmpty()) null else workout to sets
    }.take(limit)

@Composable
fun SessionLines(context: Context, exercise: Exercise, sessions: List<Pair<Workout, List<WorkoutSet>>>, unit: WeightUnit) {
    sessions.forEach { (workout, sets) ->
        Column(Modifier.padding(vertical = 6.dp)) {
            Text(dateText(workout), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(sets.joinToString(", ") { setLine(context, exercise.kind, it.weightKg, it.reps, unit) })
        }
        HorizontalDivider()
    }
}
