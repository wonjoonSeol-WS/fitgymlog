package io.fitgymlog.app.workout

import io.fitgymlog.app.model.Workout
import java.time.Instant

/** The latest a finished workout may start and the earliest it may end while still holding every ticked set. */
data class TimeBounds(val latestStart: Instant?, val earliestEnd: Instant?)

fun Workout.timeBounds(): TimeBounds {
    val ticks = exercises.flatMap { exercise -> exercise.sets.mapNotNull { it.completedAt } }
    return TimeBounds(ticks.minOrNull(), ticks.maxOrNull())
}

/** Null when [end] isn't after [start] or a ticked set would fall outside them. */
fun Workout.retimed(start: Instant, end: Instant): Workout? {
    val bounds = timeBounds()
    val fits = start.isBefore(end) &&
        bounds.latestStart?.let { !start.isAfter(it) } != false &&
        bounds.earliestEnd?.let { !end.isBefore(it) } != false
    return if (fits) copy(startedAt = start, endedAt = end) else null
}
