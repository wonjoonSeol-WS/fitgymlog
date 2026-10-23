package io.fitgymlog.app.ui.coach

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.fitgymlog.app.R
import io.fitgymlog.app.coach.Target
import io.fitgymlog.app.container
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.ui.NumberStyles
import io.fitgymlog.app.ui.localizedName
import io.fitgymlog.app.workout.ActiveWorkout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TargetsCard(workout: ActiveWorkout, exerciseOf: (String) -> Exercise, unit: WeightUnit, onApply: (List<Target>) -> Unit) {
    val app = LocalContext.current.container
    val coachReady by app.coach.ready.collectAsStateWithLifecycle()
    val todays by app.coach.todaysTargets.collectAsStateWithLifecycle()
    val untouched = workout.exercises.none { exercise -> exercise.sets.any { it.isCompleted } }
    val ready = coachReady && workout.exercises.isNotEmpty()
    val uuid = workout.workout.uuid
    val exerciseIds = workout.exercises.map { it.exerciseId }.toSet()
    val current = todays?.takeIf { it.workoutUuid == uuid }
    val dismissed = current?.dismissed == true
    LaunchedEffect(uuid, ready, untouched, exerciseIds, dismissed) {
        if (!ready || !untouched || dismissed) return@LaunchedEffect
        delay(SETTLE_MILLIS)
        app.scope.launch { app.coach.loadTargets(workout) }
    }
    if (!ready || dismissed || current?.targets?.isEmpty() == true) return
    if (current == null && !untouched) return
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.coach_targets_title), style = MaterialTheme.typography.titleMedium)
            if (current == null) {
                Text(stringResource(R.string.coach_targets_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Text(
                current.error?.let { stringResource(R.string.coach_targets_rules, stringResource(failureText(it))) } ?: stringResource(R.string.coach_targets_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (current.error != null) {
                TextButton(onClick = { app.scope.launch { app.coach.loadTargets(workout, force = true) } }) {
                    Text(stringResource(R.string.coach_retry))
                }
            }
            current.targets.forEach { target ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val applied = target.exerciseId in current.applied
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !applied, onClickLabel = stringResource(R.string.coach_apply), role = Role.Button) {
                        onApply(listOf(target))
                        app.coach.markApplied(listOf(target.exerciseId))
                    },
                ) {
                    val exercise = exerciseOf(target.exerciseId)
                    Column(Modifier.weight(1f)) {
                        Text(exercise.localizedName(), style = MaterialTheme.typography.titleSmall)
                        Text(
                            target.note ?: reasonText(target.reason)?.let { stringResource(it) }.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(targetText(target.weightKg, target.reps, unit), style = NumberStyles.cell)
                        if (applied) {
                            Icon(Icons.Default.Check, stringResource(R.string.coach_applied), tint = MaterialTheme.colorScheme.primary)
                        } else {
                            WeightChange(target.weightKg, target.lastWeightKg, unit, assisted = exercise.kind == ExerciseKind.ASSISTED_BODYWEIGHT)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                val open = current.targets.filter { it.exerciseId !in current.applied }
                Button(enabled = open.isNotEmpty(), onClick = {
                    onApply(open)
                    app.coach.markApplied(open.map { it.exerciseId })
                }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.coach_apply_all)) }
                OutlinedButton(onClick = app.coach::dismissTargets, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.coach_not_now)) }
            }
        }
    }
}

private const val SETTLE_MILLIS = 1_000L
