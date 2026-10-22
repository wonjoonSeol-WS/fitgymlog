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
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.ui.NumberStyles
import io.fitgymlog.app.ui.formatWeightNumber
import io.fitgymlog.app.ui.localizedName
import io.fitgymlog.app.ui.unitLabel
import io.fitgymlog.app.workout.ActiveWorkout
import kotlinx.coroutines.launch

/** Shown when a workout starts with the coach on, before anything is ticked. */
@Composable
fun TargetsCard(workout: ActiveWorkout, exerciseOf: (String) -> Exercise, unit: WeightUnit, onApply: (List<Target>) -> Unit) {
    val app = LocalContext.current.container
    val coach by app.coach.settings.settings.collectAsStateWithLifecycle()
    val todays by app.coach.todaysTargets.collectAsStateWithLifecycle()
    val untouched = workout.exercises.none { exercise -> exercise.sets.any { it.isCompleted } }
    val ready = coach.enabled && coach.isConnected && app.coach.hasKey && workout.exercises.isNotEmpty()
    val uuid = workout.workout.uuid
    LaunchedEffect(uuid, ready, untouched) {
        if (ready && untouched && todays?.workoutUuid != uuid) app.scope.launch { app.coach.loadTargets(workout) }
    }
    val current = todays?.takeIf { it.workoutUuid == uuid }
    if (!ready || current?.dismissed == true || current?.targets?.isEmpty() == true) return
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
            current.targets.forEach { target ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val applied = target.exerciseId in current.applied
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !applied, role = Role.Button) {
                        onApply(listOf(target))
                        app.coach.markApplied(listOf(target.exerciseId))
                    },
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(exerciseOf(target.exerciseId).localizedName(), style = MaterialTheme.typography.titleSmall)
                        Text(
                            target.note ?: stringResource(reasonText(target.reason)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(targetText(target, unit), style = NumberStyles.cell)
                        if (applied) {
                            Icon(Icons.Default.Check, stringResource(R.string.coach_applied), tint = MaterialTheme.colorScheme.primary)
                        } else {
                            WeightChange(target.weightKg, target.lastWeightKg, unit)
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

@Composable
private fun targetText(target: Target, unit: WeightUnit): String = listOfNotNull(
    target.weightKg?.let { "${formatWeightNumber(it, unit)} ${unitLabel(unit)}" },
    target.reps?.let { "x $it" },
).joinToString(" ")
