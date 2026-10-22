package io.fitgymlog.app.ui.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.fitgymlog.app.R
import io.fitgymlog.app.catching
import io.fitgymlog.app.coach.Food
import io.fitgymlog.app.container
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.ui.NumberStyles
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.launch

private sealed interface Asking {
    data object No : Asking
    data object Yes : Asking
    data class Failed(val message: Int) : Asking
}

/** Notes on a finished workout, asked for on their own right after it ends and on request later. */
@Composable
fun ReviewCard(workout: Workout) {
    val app = LocalContext.current.container
    val coach by app.coach.settings.settings.collectAsStateWithLifecycle()
    val data by app.coach.store.data.collectAsStateWithLifecycle()
    val review = data.reviews.firstOrNull { it.workoutUuid == workout.uuid }
    val ready = coach.enabled && coach.isConnected && app.coach.hasKey && workout.uuid.isNotEmpty()
    var asking by remember(workout.uuid) { mutableStateOf<Asking>(Asking.No) }

    fun ask() {
        asking = Asking.Yes
        app.scope.launch {
            asking = catching { app.coach.review(workout) }.fold({ Asking.No }, { Asking.Failed(failureText(it)) })
        }
    }

    val justFinished = workout.endedAt?.let { Duration.between(it, Instant.now()) < FRESH } == true
    LaunchedEffect(workout.uuid, ready) {
        if (ready && review == null && justFinished && asking == Asking.No) ask()
    }
    if (review == null && !ready) return

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel {
            Text(stringResource(R.string.coach_notes_title), style = MaterialTheme.typography.titleMedium)
            when {
                review != null -> Text(review.notes)
                asking == Asking.Yes -> Text(stringResource(R.string.coach_notes_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    (asking as? Asking.Failed)?.let { Text(stringResource(it.message), color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(onClick = ::ask) { Text(stringResource(R.string.coach_notes_get)) }
                }
            }
        }
        review?.food?.let { FoodPanel(it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoodPanel(food: Food) {
    Panel {
        Text(stringResource(R.string.coach_food_title), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(stringResource(R.string.coach_protein), stringResource(R.string.grams, food.proteinGrams), Modifier.weight(1f))
            Tile(stringResource(R.string.coach_next_meal), stringResource(R.string.grams, food.nextMealProteinGrams), Modifier.weight(1f))
        }
        if (food.summary.isNotBlank()) Text(food.summary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            food.ideas.forEach { idea ->
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(idea, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
        Text(stringResource(R.string.coach_disclaimer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = NumberStyles.stat)
        }
    }
}

private val FRESH = Duration.ofHours(3)
