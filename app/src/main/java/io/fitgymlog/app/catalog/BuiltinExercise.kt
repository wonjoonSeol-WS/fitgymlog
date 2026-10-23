package io.fitgymlog.app.catalog

import androidx.annotation.StringRes
import io.fitgymlog.app.model.Equipment
import io.fitgymlog.app.model.ExerciseKind

data class BuiltinExercise(
    val id: String,
    @param:StringRes val nameRes: Int,
    val hevyName: String,
    val kind: ExerciseKind,
    val equipment: Equipment,
    val segmentType: Int,
    val links: List<String> = emptyList(),
    val largeIncrements: Boolean = false,
)
