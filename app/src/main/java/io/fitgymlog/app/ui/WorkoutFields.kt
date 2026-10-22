package io.fitgymlog.app.ui

import androidx.annotation.StringRes
import androidx.health.connect.client.records.ExerciseSegment
import io.fitgymlog.app.R
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.isTimed

val REST_CHOICES = listOf(0, 30, 60, 90, 120, 150, 180, 240, 300)

fun ExerciseKind.usesWeight(): Boolean = this != ExerciseKind.BODYWEIGHT_REPS && !isTimed

private val Exercise.hasIncline: Boolean get() = segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING_TREADMILL

@StringRes
fun Exercise.levelLabel(): Int = if (hasIncline) R.string.col_incline else R.string.col_level

@StringRes
fun Exercise.withLevel(): Int = if (hasIncline) R.string.with_incline else R.string.with_level
