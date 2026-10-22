package io.gymlog.app.catalog

import androidx.annotation.StringRes
import io.gymlog.app.model.ExerciseKind

data class BuiltinExercise(
    val id: String,
    @param:StringRes val nameRes: Int,
    val hevyName: String,
    val kind: ExerciseKind,
    val segmentType: Int,
    val links: List<String> = emptyList(),
)
