package io.fitgymlog.app.checkin

import io.fitgymlog.app.coach.BodyMeasurement
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.ui.ChartPoint
import io.fitgymlog.app.ui.exercise.ProgressMetric
import io.fitgymlog.app.ui.exercise.recordsByWorkout
import io.fitgymlog.app.ui.stats.WeeklyMeasure
import io.fitgymlog.app.ui.stats.weeklyTotals
import io.fitgymlog.app.ui.stats.weightTrend
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class WeekRecord(val exerciseId: String, val metrics: List<ProgressMetric>)

data class MissedReps(val exerciseId: String, val missed: Int, val sets: Int)

/** Totals hold this week's value and last week's, in that order. */
data class WeekSummary(
    val start: LocalDate,
    val workouts: Pair<Int, Int>,
    val minutes: Pair<Long, Long>,
    val volumeKg: Pair<Double, Double>,
    val records: List<WeekRecord>,
    val missed: List<MissedReps>,
    val weightTrendKg: Double?,
    val weightChangeKg: Double?,
) {
    val end: LocalDate get() = start.plusDays(6)
}

internal fun weekStart(today: LocalDate, firstDay: DayOfWeek): LocalDate = today.with(TemporalAdjusters.previousOrSame(firstDay))

/**
 * The week holding [today], as the locale counts weeks. The weight is the trend at the week's last reading, against the
 * trend at the last reading before the week.
 */
internal fun weekSummary(
    history: List<Workout>,
    body: List<BodyMeasurement>,
    today: LocalDate,
    firstDay: DayOfWeek,
    zone: ZoneId,
    kindOf: (String) -> ExerciseKind,
): WeekSummary {
    val start = weekStart(today, firstDay)
    fun totals(measure: WeeklyMeasure) = weeklyTotals(history, measure, today, firstDay, zone, weeks = 2).let { (before, now) -> now.value to before.value }
    val week = history.filter { workout -> workout.startedAt.atZone(zone).toLocalDate().let { !it.isBefore(start) && !it.isAfter(start.plusDays(6)) } }
    val ids = week.map { it.id }.toSet()
    val records = recordsByWorkout(history, kindOf).filterKeys { it in ids }.values
        .flatMap { it.entries }
        .groupBy({ it.key }, { it.value })
        .map { (exerciseId, metrics) -> WeekRecord(exerciseId, metrics.flatten().distinct()) }
    val trend = weightTrend(body.mapNotNull { entry -> entry.weightKg?.let { ChartPoint(LocalDate.parse(entry.date), it) } }.filter { !it.date.isAfter(start.plusDays(6)) })
    val now = trend.lastOrNull()?.takeIf { !it.date.isBefore(start) }
    val before = trend.lastOrNull { it.date.isBefore(start) }
    return WeekSummary(
        start = start,
        workouts = totals(WeeklyMeasure.WORKOUTS).let { (now, before) -> now.toInt() to before.toInt() },
        minutes = totals(WeeklyMeasure.TIME).let { (now, before) -> now.toLong() to before.toLong() },
        volumeKg = totals(WeeklyMeasure.VOLUME),
        records = records,
        missed = missedReps(week),
        weightTrendKg = now?.value,
        weightChangeKg = now?.let { latest -> before?.let { latest.value - it.value } },
    )
}

/** Done working sets that fell short of their target reps, by exercise, among those with a target. */
private fun missedReps(week: List<Workout>): List<MissedReps> =
    week.flatMap { it.exercises }.groupBy({ it.exerciseId }, { it.sets }).mapNotNull { (exerciseId, blocks) ->
        val done = blocks.flatten().filter { it.type == SetType.NORMAL && it.isCompleted }
        val targeted = done.mapNotNull { set -> set.targetReps?.let { target -> set.reps?.let { reps -> reps to target } } }
        val missed = targeted.count { (reps, target) -> reps < target }
        if (missed == 0) null else MissedReps(exerciseId, missed, targeted.size)
    }
