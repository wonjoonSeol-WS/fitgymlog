package io.fitgymlog.app.ui

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.health.connect.client.records.ExerciseSegment
import io.fitgymlog.app.R
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.model.isTimed
import kotlin.math.roundToLong

val REST_CHOICES = listOf(0, 30, 60, 90, 120, 150, 180, 240, 300)

fun ExerciseKind.usesWeight(): Boolean = this != ExerciseKind.BODYWEIGHT_REPS && !isTimed

private val Exercise.hasIncline: Boolean get() = segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING_TREADMILL

val Exercise.hasSpeed: Boolean get() = hasIncline

val Exercise.hasDistance: Boolean get() = segmentType == ExerciseSegment.EXERCISE_SEGMENT_TYPE_ROWING_MACHINE

val SPEED_RANGE_KMH = 0.5..30.0

val DISTANCE_RANGE_M = 100.0..50_000.0

internal fun tenths(value: Double): Double = (value * 10).roundToLong() / 10.0

@StringRes
fun Exercise.levelLabel(): Int = if (hasIncline) R.string.col_incline else R.string.col_level

@StringRes
fun Exercise.withLevel(): Int = if (hasIncline) R.string.with_incline else R.string.with_level

fun withCardioDetails(resources: Resources, line: String?, exercise: Exercise, unit: WeightUnit, speedKmh: Double?, distanceMeters: Double?, level: Int?): String? {
    val details = listOfNotNull(
        line,
        distanceMeters?.let { "${it.roundToLong()} ${resources.getString(R.string.unit_m)}" },
        speedKmh?.let { speedText(resources, it, unit) },
    ).reduceOrNull { joined, next -> resources.getString(R.string.with_detail, joined, next) }
    return when {
        level == null -> details
        details == null -> "${resources.getString(exercise.levelLabel())} $level"
        else -> resources.getString(exercise.withLevel(), details, level)
    }
}
