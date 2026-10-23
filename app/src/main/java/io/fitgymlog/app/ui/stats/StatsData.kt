package io.fitgymlog.app.ui.stats

import io.fitgymlog.app.coach.BodyMeasurement
import io.fitgymlog.app.coach.fatMassKg
import io.fitgymlog.app.coach.leanMassKg
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.ui.ChartPoint
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.pow
import kotlin.math.roundToInt

internal enum class CompositionUnit { PERCENT, MASS, LEVEL }

internal enum class CompositionMetric(val unit: CompositionUnit, val read: (BodyMeasurement) -> Double?) {
    BODY_FAT(CompositionUnit.PERCENT, BodyMeasurement::bodyFatPercent),
    FAT_MASS(CompositionUnit.MASS, ::fatMassKg),
    LEAN_MASS(CompositionUnit.MASS, ::leanMassKg),
    MUSCLE(CompositionUnit.MASS, BodyMeasurement::skeletalMuscleKg),
    VISCERAL_FAT(CompositionUnit.LEVEL, { it.visceralFatLevel?.toDouble() }),
}

internal enum class ChartRange(val days: Long?) { MONTH(30), QUARTER(91), YEAR(365), ALL(null) }

internal fun List<ChartPoint>.within(range: ChartRange, today: LocalDate): List<ChartPoint> =
    range.days?.let { days -> filter { !it.date.isBefore(today.minusDays(days)) } } ?: this

/**
 * An exponentially smoothed average, as in The Hacker's Diet: each day moves the trend a tenth of the way to that day's
 * reading, so a gap of several days moves it further.
 */
internal fun weightTrend(readings: List<ChartPoint>): List<ChartPoint> {
    var trend: ChartPoint? = null
    return readings.sortedBy { it.date }.map { reading ->
        val previous = trend
        val next = if (previous == null) {
            reading
        } else {
            val days = ChronoUnit.DAYS.between(previous.date, reading.date).coerceAtLeast(1)
            val pull = 1 - (1 - TREND_SMOOTHING).pow(days.toDouble())
            ChartPoint(reading.date, previous.value + pull * (reading.value - previous.value))
        }
        trend = next
        next
    }
}

internal data class Pace(val kgPerWeek: Double, val weeks: Int)

/** The trend's change a week over the last [PACE_DAYS], read between readings, or over all of it once it spans [MIN_PACE_DAYS]. */
internal fun weeklyPace(trend: List<ChartPoint>): Pace? {
    val end = trend.lastOrNull() ?: return null
    val windowStart = end.date.minusDays(PACE_DAYS)
    val before = trend.lastOrNull { !it.date.isAfter(windowStart) }
    val start = before?.let { between(it, trend.first { point -> point.date.isAfter(windowStart) }, windowStart) } ?: trend.first()
    val days = ChronoUnit.DAYS.between(start.date, end.date)
    if (days < MIN_PACE_DAYS) return null
    return Pace((end.value - start.value) / days * 7, (days / 7.0).roundToInt())
}

private fun between(before: ChartPoint, after: ChartPoint, date: LocalDate): ChartPoint {
    val share = ChronoUnit.DAYS.between(before.date, date).toDouble() / ChronoUnit.DAYS.between(before.date, after.date)
    return ChartPoint(date, before.value + share * (after.value - before.value))
}

/** Measurements with body fat, skeletal muscle or visceral fat, oldest first: what a scale app or InBody scan gives. */
internal fun scans(body: List<BodyMeasurement>): List<BodyMeasurement> =
    body.filter { it.bodyFatPercent != null || it.skeletalMuscleKg != null || it.visceralFatLevel != null }.sortedBy { it.date }

internal data class CompositionRow(val metric: CompositionMetric, val latest: Double, val sincePrevious: Double?, val sinceFirst: Double?)

internal data class CompositionChanges(val latest: LocalDate, val previous: LocalDate?, val first: LocalDate?, val rows: List<CompositionRow>)

/** The latest scan against the one before it and, from three scans on, against the first. */
internal fun compositionChanges(scans: List<BodyMeasurement>): CompositionChanges? {
    val latest = scans.lastOrNull() ?: return null
    val previous = scans.getOrNull(scans.lastIndex - 1)
    val first = scans.first().takeIf { scans.size >= 3 }
    val rows = CompositionMetric.entries.mapNotNull { metric ->
        val now = metric.read(latest) ?: return@mapNotNull null
        CompositionRow(metric, now, previous?.let(metric.read)?.let { now - it }, first?.let(metric.read)?.let { now - it })
    }
    if (rows.isEmpty()) return null
    return CompositionChanges(LocalDate.parse(latest.date), previous?.let { LocalDate.parse(it.date) }, first?.let { LocalDate.parse(it.date) }, rows)
}

internal enum class WeeklyMeasure { WORKOUTS, TIME, VOLUME, LOAD }

/** The last [weeks] weeks, oldest first, each from [firstDay]; time in minutes, volume as weight times reps over every set, as a workout's own total. */
internal fun weeklyTotals(history: List<Workout>, measure: WeeklyMeasure, today: LocalDate, firstDay: DayOfWeek, zone: ZoneId, weeks: Int = WEEKS): List<ChartPoint> {
    fun weekOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(firstDay))
    val byWeek = history.groupBy { weekOf(it.startedAt.atZone(zone).toLocalDate()) }
    return (weeks - 1 downTo 0).map { back ->
        val start = weekOf(today).minusWeeks(back.toLong())
        val workouts = byWeek[start].orEmpty()
        val value = when (measure) {
            WeeklyMeasure.WORKOUTS -> workouts.size.toDouble()
            WeeklyMeasure.TIME -> workouts.sumOf { workout -> workout.endedAt?.let { Duration.between(workout.startedAt, it).toMinutes() } ?: 0L }.toDouble()
            WeeklyMeasure.VOLUME -> workouts.sumOf { workout -> workout.exercises.sumOf { exercise -> exercise.sets.sumOf { (it.weightKg ?: 0.0) * (it.reps ?: 0) } } }
            WeeklyMeasure.LOAD -> workouts.sumOf { trainingLoad(it) }
        }
        ChartPoint(start, value)
    }
}

/** How hard it felt times how long it took (session RPE); a workout nobody rated counts for nothing, rather than a guess. */
internal fun trainingLoad(workout: Workout): Double {
    val effort = workout.effort ?: return 0.0
    val endedAt = workout.endedAt ?: return 0.0
    return effort * Duration.between(workout.startedAt, endedAt).toSeconds() / 60.0
}

internal enum class LoadTrend { ABOVE, STEADY, BELOW }

/** The last 7 days against the weekly average of the last 28, as a percentage change. */
internal data class LoadComparison(val percent: Int, val trend: LoadTrend)

/**
 * Null until there is a usual week to compare with: a rated workout from before the last 7 days, within 28. Within a tenth either way counts
 * as steady, since one session more or less moves a week that much.
 */
internal fun loadComparison(history: List<Workout>, now: Instant): LoadComparison? {
    fun loadSince(days: Long) = history.filter { it.startedAt.isAfter(now.minus(Duration.ofDays(days))) && !it.startedAt.isAfter(now) }.sumOf { trainingLoad(it) }
    val lastWeek = loadSince(7)
    val lastFourWeeks = loadSince(28)
    if (lastFourWeeks - lastWeek <= 0.0) return null
    val change = lastWeek / (lastFourWeeks / 4) - 1
    val trend = when {
        change > STEADY_BAND -> LoadTrend.ABOVE
        change < -STEADY_BAND -> LoadTrend.BELOW
        else -> LoadTrend.STEADY
    }
    return LoadComparison((change * 100).roundToInt(), trend)
}

private const val STEADY_BAND = 0.1

/** Each day's rolling 7-day load, and the usual week behind it: the 28 days to that day, a quarter of them. */
internal data class LoadSeries(val lastWeek: List<ChartPoint>, val usualWeek: List<ChartPoint>)

/** The last [days] days, oldest first, less any empty ones at the start beyond the last two weeks; null while nothing in or just before them is rated. */
internal fun loadSeries(history: List<Workout>, today: LocalDate, zone: ZoneId, days: Int = LOAD_DAYS): LoadSeries? {
    val byDay = history.groupBy { it.startedAt.atZone(zone).toLocalDate() }.mapValues { (_, workouts) -> workouts.sumOf(::trainingLoad) }
    fun loadOver(end: LocalDate, length: Long) = (0 until length).sumOf { byDay[end.minusDays(it)] ?: 0.0 }
    val dates = (days - 1 downTo 0).map { today.minusDays(it.toLong()) }
    val usual = dates.map { ChartPoint(it, loadOver(it, 28) / 4) }
    // A day with no usual load has no 7-day load either, so the empty days are the ones before the first usual load.
    val first = usual.indexOfFirst { it.value > 0.0 }.takeIf { it >= 0 } ?: return null
    val start = minOf(first, dates.size - MIN_LOAD_DAYS).coerceAtLeast(0)
    return LoadSeries(dates.drop(start).map { ChartPoint(it, loadOver(it, 7)) }, usual.drop(start))
}

private const val LOAD_DAYS = 56
private const val MIN_LOAD_DAYS = 14

internal data class ExerciseUse(val exerciseId: String, val sessions: Int, val lastDone: Instant)

internal fun exerciseUses(history: List<Workout>): List<ExerciseUse> =
    history.flatMap { workout -> workout.exercises.filter { it.sets.isNotEmpty() }.map { it.exerciseId to workout.startedAt }.distinct() }
        .groupBy({ it.first }, { it.second })
        .map { (id, times) -> ExerciseUse(id, times.size, times.max()) }
        .sortedWith(compareByDescending<ExerciseUse> { it.lastDone }.thenBy { it.exerciseId })

private const val TREND_SMOOTHING = 0.1
private const val WEEKS = 12
private const val PACE_DAYS = 28L
private const val MIN_PACE_DAYS = 14L
