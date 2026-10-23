package io.fitgymlog.app.ui.workout

import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.model.isTimed
import java.math.BigDecimal

internal val REP_CHOICES = (1..30).toList()

internal val SECOND_CHOICES = listOf(5, 10, 15, 20, 25, 30, 40, 45, 50, 60, 75, 90, 105, 120, 150, 180, 210, 240, 270, 300)

private val MINUTE_CHOICES = listOf(1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 15, 20, 25, 30, 40, 45, 50, 60, 75, 90)

internal val LEVEL_CHOICES = (0..20).toList()

internal fun editableWeight(value: Double?): String =
    value?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() }.orEmpty()

internal fun normalSetNumbers(sets: List<WorkoutSet>): List<Int?> {
    var count = 0
    return sets.map { if (it.type == SetType.NORMAL) ++count else null }
}

internal fun WorkoutSet.amount(kind: ExerciseKind): Int? = if (kind.isTimed) durationSeconds?.let(kind::amountOf) else reps

internal fun ExerciseKind.amountOf(seconds: Int): Int = when {
    this != ExerciseKind.CARDIO -> seconds
    seconds == 0 -> 0
    else -> ((seconds + SECONDS_PER_MINUTE / 2) / SECONDS_PER_MINUTE).coerceAtLeast(1)
}

internal fun ExerciseKind.amountChoices(): List<Int> = when (this) {
    ExerciseKind.CARDIO -> MINUTE_CHOICES
    ExerciseKind.DURATION -> SECOND_CHOICES
    else -> REP_CHOICES
}

internal fun ExerciseKind.secondsOf(amount: Int): Int = if (this == ExerciseKind.CARDIO) amount * SECONDS_PER_MINUTE else amount

private const val SECONDS_PER_MINUTE = 60


/** The usual RPE scale for lifting: 6 is 4 or more reps left, 10 is none, in halves. */
internal val RPE_CHOICES = (12..20).map { it / 2.0 }

/** 8.0 shows as "8", 8.5 as "8.5". */
internal fun rpeText(rpe: Double): String = if (rpe % 1.0 == 0.0) rpe.toInt().toString() else rpe.toString()
