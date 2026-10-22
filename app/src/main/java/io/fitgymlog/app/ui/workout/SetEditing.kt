package io.fitgymlog.app.ui.workout

import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.WorkoutSet
import java.math.BigDecimal

internal val REP_CHOICES = (1..30).toList()

internal val SECOND_CHOICES = listOf(5, 10, 15, 20, 25, 30, 40, 45, 50, 60, 75, 90, 105, 120, 150, 180, 210, 240, 270, 300)

internal fun editableWeight(value: Double?): String =
    value?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() }.orEmpty()

internal fun normalSetNumbers(sets: List<WorkoutSet>): List<Int?> {
    var count = 0
    return sets.map { if (it.type == SetType.NORMAL) ++count else null }
}

internal fun WorkoutSet.amount(kind: ExerciseKind): Int? = if (kind == ExerciseKind.DURATION) durationSeconds else reps

