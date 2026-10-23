package io.fitgymlog.app.workout

import io.fitgymlog.app.model.Workout
import java.time.Instant
import java.time.temporal.ChronoUnit

enum class RetimeProblem { ORDER, FUTURE, OUTSIDE_SETS, CLASH }

data class TimeBounds(val latestStart: Instant?, val earliestEnd: Instant?)

fun Workout.timeBounds(): TimeBounds {
    val ticks = exercises.flatMap { exercise -> exercise.sets.mapNotNull { it.completedAt } }
    return TimeBounds(ticks.minOrNull(), ticks.maxOrNull())
}

/** Picked times are whole minutes, so an end in the minute of the last tick still holds it. */
fun Workout.retimeProblem(start: Instant, end: Instant, now: Instant): RetimeProblem? {
    val bounds = timeBounds()
    return when {
        !start.isBefore(end) -> RetimeProblem.ORDER
        end.isAfter(now) -> RetimeProblem.FUTURE
        bounds.latestStart?.let { start.isAfter(it) } == true -> RetimeProblem.OUTSIDE_SETS
        bounds.earliestEnd?.let { end.isBefore(it.truncatedTo(ChronoUnit.MINUTES)) } == true -> RetimeProblem.OUTSIDE_SETS
        else -> null
    }
}

fun Workout.retimed(start: Instant, end: Instant): Workout = copy(startedAt = start, endedAt = maxOf(end, timeBounds().earliestEnd ?: end))
