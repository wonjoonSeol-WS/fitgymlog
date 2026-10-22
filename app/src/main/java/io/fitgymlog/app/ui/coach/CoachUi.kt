package io.fitgymlog.app.ui.coach

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.fitgymlog.app.R
import io.fitgymlog.app.coach.CoachException
import io.fitgymlog.app.coach.CoachFailure
import io.fitgymlog.app.coach.Goal
import io.fitgymlog.app.coach.MemoryTopic
import io.fitgymlog.app.coach.TargetReason
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.ui.formatWeightNumber
import io.fitgymlog.app.ui.lift

fun failureText(error: Throwable): Int = when ((error as? CoachException)?.failure) {
    CoachFailure.UNAUTHORIZED -> R.string.coach_error_key
    CoachFailure.NOT_FOUND -> R.string.coach_error_not_found
    CoachFailure.RATE_LIMITED -> R.string.coach_error_rate
    CoachFailure.SERVER -> R.string.coach_error_server
    CoachFailure.BAD_REPLY -> R.string.coach_error_reply
    CoachFailure.OFFLINE, null -> R.string.coach_error_offline
}

fun reasonText(reason: TargetReason): Int = when (reason) {
    TargetReason.ALL_REPS_DONE -> R.string.coach_reason_all_reps
    TargetReason.MISSED_REPS -> R.string.coach_reason_missed
    TargetReason.STALLED -> R.string.coach_reason_stalled
    TargetReason.FIRST_TIME -> R.string.coach_reason_first
    TargetReason.SAME_AS_LAST -> R.string.coach_reason_same
}

fun goalText(goal: Goal): Int = when (goal) {
    Goal.BUILD -> R.string.coach_goal_build
    Goal.KEEP -> R.string.coach_goal_keep
    Goal.LOSE -> R.string.coach_goal_lose
}

fun topicText(topic: MemoryTopic): Int = when (topic) {
    MemoryTopic.HEALTH -> R.string.coach_topic_health
    MemoryTopic.FOOD -> R.string.coach_topic_food
    MemoryTopic.SCHEDULE -> R.string.coach_topic_schedule
    MemoryTopic.GOALS -> R.string.coach_topic_goals
}

/** "+2.5", "=" or "-4.5" against last time's weight, coloured like the set rows. */
@Composable
fun WeightChange(weightKg: Double?, lastWeightKg: Double?, unit: WeightUnit) {
    if (weightKg == null || lastWeightKg == null) return
    val difference = weightKg - lastWeightKg
    val (text, color) = when {
        difference > 0.001 -> "+${formatWeightNumber(difference, unit)}" to MaterialTheme.lift.doneCheck
        difference < -0.001 -> "-${formatWeightNumber(-difference, unit)}" to MaterialTheme.lift.warmup
        else -> "=" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
