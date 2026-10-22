package io.gymlog.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gymlog.app.R
import io.gymlog.app.container
import io.gymlog.app.model.Routine
import io.gymlog.app.ui.ReadableWidth
import io.gymlog.app.ui.formatClock
import io.gymlog.app.ui.localizedName
import io.gymlog.app.ui.rememberNow
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenWorkout: () -> Unit, onEditRoutine: (Long) -> Unit) {
    val app = LocalContext.current.container
    val active by app.workouts.active.collectAsStateWithLifecycle()
    val routines by app.routines.routines.collectAsStateWithLifecycle(emptyList())
    val exercises by app.exercises.exercises.collectAsStateWithLifecycle()
    val now = rememberNow()

    fun start(block: suspend () -> Unit) {
        app.scope.launch {
            block()
            withContext(Dispatchers.Main) { onOpenWorkout() }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_workout)) }) }) { padding ->
        ReadableWidth(Modifier.padding(padding)) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                active?.let { current ->
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.workout_in_progress), style = MaterialTheme.typography.labelMedium)
                                    Text(current.workout.title, style = MaterialTheme.typography.titleMedium)
                                    Text(formatClock(Duration.between(current.workout.startedAt, now)))
                                }
                                Button(onClick = onOpenWorkout) { Text(stringResource(R.string.resume)) }
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = { start { app.actions.startEmpty() } },
                        enabled = active == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.start_empty_workout))
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.routines), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onEditRoutine(0) }) { Text(stringResource(R.string.new_routine)) }
                    }
                }
                if (routines.isEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.no_routines), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = { app.scope.launch { StrongLiftsRoutines.addTo(app.routines, app.settings.settings.value.defaultRestSeconds) } }) {
                                Text(stringResource(R.string.add_stronglifts_routines))
                            }
                        }
                    }
                }
                items(routines, key = { it.id }) { routine ->
                    RoutineCard(
                        routine = routine,
                        exerciseNames = routine.exercises.map { planned ->
                            (exercises.firstOrNull { it.id == planned.exerciseId } ?: app.exercises.find(planned.exerciseId)).localizedName()
                        },
                        canStart = active == null,
                        onStart = { start { app.actions.startRoutine(routine) } },
                        onEdit = { onEditRoutine(routine.id) },
                    )
                }
                item { Spacer(Modifier.padding(bottom = 24.dp)) }
            }
        }
    }
}

@Composable
private fun RoutineCard(routine: Routine, exerciseNames: List<String>, canStart: Boolean, onStart: () -> Unit, onEdit: () -> Unit) {
    Card(onClick = onEdit) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(routine.name, style = MaterialTheme.typography.titleMedium)
            Text(
                exerciseNames.joinToString(", "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Button(onClick = onStart, enabled = canStart, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.start_routine))
            }
        }
    }
}
