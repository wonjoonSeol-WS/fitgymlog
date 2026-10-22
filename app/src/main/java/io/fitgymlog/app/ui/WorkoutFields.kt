package io.fitgymlog.app.ui

import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.isTimed

val REST_CHOICES = listOf(0, 30, 60, 90, 120, 150, 180, 240, 300)

fun ExerciseKind.usesWeight(): Boolean = this != ExerciseKind.BODYWEIGHT_REPS && !isTimed
