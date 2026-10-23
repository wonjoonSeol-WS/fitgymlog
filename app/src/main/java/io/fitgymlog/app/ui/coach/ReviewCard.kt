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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.fitgymlog.app.R
import io.fitgymlog.app.coach.Food
import io.fitgymlog.app.coach.Pending
import io.fitgymlog.app.container
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.ui.NumberStyles
import java.time.Duration
import java.time.Instant

@Composable
fun ReviewCard(workout: Workout) {
    val app = LocalContext.current.container
    val coachReady by app.coach.ready.collectAsStateWithLifecycle()
    val data by app.coach.store.data.collectAsStateWithLifecycle()
    val pending by app.coach.reviewsPending.collectAsStateWithLifecycle()
    val review = data.reviews.firstOrNull { it.workoutUuid == workout.uuid }
    val ready = coachReady && workout.uuid.isNotEmpty()
    val asking = pending[workout.uuid]

    val justFinished = workout.endedAt?.let { Duration.between(it, Instant.now()) < FRESH } == true
    // Waits while the workout is being rated, so the review knows how hard it felt.
    val rating by app.actions.effortPrompt.collectAsStateWithLifecycle()
    LaunchedEffect(workout.uuid, ready, rating) {
        if (rating == workout.id) return@LaunchedEffect
        if (ready && review == null && justFinished && workout.uuid !in app.coach.reviewsPending.value) app.coach.requestReview(workout, onlyIfMissing = true)
    }
    if (review == null && !ready) return

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel {
            Text(stringResource(R.string.coach_notes_title), style = MaterialTheme.typography.titleMedium)
            when {
                review != null -> Text(review.notes)
                asking == Pending.Running -> Text(stringResource(R.string.coach_notes_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    (asking as? Pending.Failed)?.let { FailureText(it.error) }
                    OutlinedButton(onClick = { app.coach.requestReview(workout) }) { Text(stringResource(R.string.coach_notes_get)) }
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
        food.kcal?.let { Tile(stringResource(R.string.coach_calories), stringResource(R.string.kcal, it), Modifier.fillMaxWidth()) }
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
