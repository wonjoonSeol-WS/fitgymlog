package io.fitgymlog.app.ui.workout

import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.WorkoutSet
import java.math.BigDecimal

internal val REP_CHOICES = (1..30).toList()

internal val SECOND_CHOICES = listOf(5, 10, 15, 20, 25, 30, 40, 45, 50, 60, 75, 90, 105, 120, 150, 180, 210, 240, 270, 300)

internal val MINUTE_CHOICES = listOf(1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 15, 20, 25, 30, 40, 45, 50, 60, 75, 90)

internal val LEVEL_CHOICES = (0..20).toList()

internal fun editableWeight(value: Double?): String =
    value?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() }.orEmpty()

internal fun normalSetNumbers(sets: List<WorkoutSet>): List<Int?> {
    var count = 0
    return sets.map { if (it.type == SetType.NORMAL) ++count else null }
}

/** What the amount column shows and its pad picks: whole minutes for cardio, seconds for other timed sets, otherwise reps. */
internal fun WorkoutSet.amount(kind: ExerciseKind): Int? = when (kind) {
    ExerciseKind.CARDIO -> durationSeconds?.let { (it + SECONDS_PER_MINUTE / 2) / SECONDS_PER_MINUTE }
    ExerciseKind.DURATION -> durationSeconds
    else -> reps
}

internal fun ExerciseKind.amountChoices(): List<Int> = when (this) {
    ExerciseKind.CARDIO -> MINUTE_CHOICES
    ExerciseKind.DURATION -> SECOND_CHOICES
    else -> REP_CHOICES
}

internal fun ExerciseKind.secondsOf(amount: Int): Int = if (this == ExerciseKind.CARDIO) amount * SECONDS_PER_MINUTE else amount

private const val SECONDS_PER_MINUTE = 60

