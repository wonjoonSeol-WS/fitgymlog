package io.fitgymlog.app.ui.coach

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.fitgymlog.app.R
import io.fitgymlog.app.coach.CoachException
import io.fitgymlog.app.coach.CoachFailure
import io.fitgymlog.app.coach.Goal
import io.fitgymlog.app.coach.MemoryTopic
import io.fitgymlog.app.coach.Sex
import io.fitgymlog.app.coach.TargetReason
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.ui.formatWeightNumber
import io.fitgymlog.app.ui.lift
import io.fitgymlog.app.ui.unitLabel
import kotlin.math.abs

@StringRes
fun failureText(error: Throwable): Int = when ((error as? CoachException)?.failure) {
    CoachFailure.UNAUTHORIZED -> R.string.coach_error_key
    CoachFailure.NOT_FOUND -> R.string.coach_error_not_found
    CoachFailure.REJECTED -> R.string.coach_error_rejected
    CoachFailure.RATE_LIMITED -> R.string.coach_error_rate
    CoachFailure.SERVER -> R.string.coach_error_server
    CoachFailure.OFFLINE -> R.string.coach_error_offline
    CoachFailure.TIMEOUT -> R.string.coach_error_timeout
    CoachFailure.BAD_REPLY -> R.string.coach_error_reply
    null -> R.string.coach_error_unknown
}

@Composable
fun FailureText(error: Throwable, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(stringResource(failureText(error)), color = MaterialTheme.colorScheme.error)
        (error as? CoachException)?.providerMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Null for a first time, whose target has nothing to set and is never shown. */
@StringRes
fun reasonText(reason: TargetReason): Int? = when (reason) {
    TargetReason.ALL_REPS_DONE -> R.string.coach_reason_all_reps
    TargetReason.MORE_REPS -> R.string.coach_reason_more_reps
    TargetReason.HARD_SETS -> R.string.coach_reason_hard_sets
    TargetReason.MISSED_REPS -> R.string.coach_reason_missed
    TargetReason.STALLED -> R.string.coach_reason_stalled
    TargetReason.SAME_AS_LAST -> R.string.coach_reason_same
    TargetReason.FIRST_TIME -> null
}

@StringRes
fun sexText(sex: Sex?): Int = when (sex) {
    Sex.MALE -> R.string.coach_sex_male
    Sex.FEMALE -> R.string.coach_sex_female
    null -> R.string.coach_sex_unset
}

@StringRes
fun goalText(goal: Goal): Int = when (goal) {
    Goal.BUILD -> R.string.coach_goal_build
    Goal.RECOMP -> R.string.coach_goal_recomp
    Goal.KEEP -> R.string.coach_goal_keep
    Goal.LOSE -> R.string.coach_goal_lose
}

@StringRes
fun topicText(topic: MemoryTopic): Int = when (topic) {
    MemoryTopic.HEALTH -> R.string.coach_topic_health
    MemoryTopic.FOOD -> R.string.coach_topic_food
    MemoryTopic.SCHEDULE -> R.string.coach_topic_schedule
    MemoryTopic.GOALS -> R.string.coach_topic_goals
}

@Composable
fun targetText(weightKg: Double?, reps: Int?, unit: WeightUnit): String = listOfNotNull(
    weightKg?.let { "${formatWeightNumber(it, unit)} ${unitLabel(unit)}" },
    reps?.let { "x $it" },
).joinToString(" ")

/** "+2.5", "=" or "-4.5" against last time's weight, green when it is harder, which for [assisted] work is less weight. */
@Composable
fun WeightChange(weightKg: Double?, lastWeightKg: Double?, unit: WeightUnit, assisted: Boolean) {
    if (weightKg == null || lastWeightKg == null) return
    val difference = weightKg - lastWeightKg
    val same = abs(difference) < 0.001
    val harder = if (assisted) difference < 0 else difference > 0
    val text = when {
        same -> "="
        difference > 0 -> "+${formatWeightNumber(difference, unit)}"
        else -> "-${formatWeightNumber(-difference, unit)}"
    }
    val color = when {
        same -> MaterialTheme.colorScheme.onSurfaceVariant
        harder -> MaterialTheme.lift.doneCheck
        else -> MaterialTheme.lift.warmup
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
