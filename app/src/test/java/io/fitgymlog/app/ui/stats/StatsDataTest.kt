package io.fitgymlog.app.ui.stats

import io.fitgymlog.app.coach.BodyMeasurement
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutExercise
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.ui.ChartPoint
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsDataTest {
    private fun workout(at: String, vararg exerciseIds: String, empty: String? = null) = Workout(
        uuid = at,
        title = "Day",
        startedAt = Instant.parse(at),
        endedAt = Instant.parse(at).plusSeconds(3_600),
        exercises = exerciseIds.mapIndexed { index, id -> WorkoutExercise(key = index.toLong(), exerciseId = id, restSeconds = 0, sets = listOf(WorkoutSet(weightKg = 60.0, reps = 5))) } +
            listOfNotNull(empty?.let { WorkoutExercise(key = 99, exerciseId = it, restSeconds = 0, sets = emptyList()) }),
    )

    private val day0 = LocalDate.parse("2026-09-01")

    private fun at(day: Long, value: Double) = ChartPoint(day0.plusDays(day), value)

    @Test
    fun `the trend moves a tenth of the way to each reading, further across a gap, whatever order readings come in`() {
        val trend = weightTrend(listOf(at(4, 81.0), at(0, 80.0), at(1, 79.0)))

        assertEquals(listOf(day0, day0.plusDays(1), day0.plusDays(4)), trend.map { it.date })
        assertEquals(80.0, trend[0].value, 1e-9)
        assertEquals(79.9, trend[1].value, 1e-9)
        assertEquals(79.9 + (1 - 0.9 * 0.9 * 0.9) * (81.0 - 79.9), trend[2].value, 1e-9)
    }

    @Test
    fun `the weekly pace runs from four weeks before the last point, read between the readings around it, or from the start once that spans two weeks`() {
        val long = weeklyPace(listOf(at(0, 83.0), at(20, 81.0), at(38, 79.0)))!!
        val twoWeeks = weeklyPace(listOf(at(0, 80.0), at(14, 79.0)))!!
        val nearlyThree = weeklyPace(listOf(at(0, 80.0), at(20, 79.0)))!!

        assertEquals((79.0 - 82.0) / 28 * 7, long.kgPerWeek, 1e-9)
        assertEquals(4, long.weeks)
        assertEquals(-0.5, twoWeeks.kgPerWeek, 1e-9)
        assertEquals(2, twoWeeks.weeks)
        assertEquals(3, nearlyThree.weeks)
        assertNull(weeklyPace(listOf(at(0, 80.0), at(13, 79.0))))
    }

    @Test
    fun `a range keeps that many days back from today, and all keeps everything`() {
        val today = day0.plusDays(100)
        val points = listOf(at(0, 1.0), at(69, 2.0), at(70, 3.0), at(100, 4.0))

        assertEquals(listOf(3.0, 4.0), points.within(ChartRange.MONTH, today).map { it.value })
        assertEquals(points, points.within(ChartRange.ALL, today))
    }

    @Test
    fun `composition compares the latest scan with the one before and, from three scans on, with the first, and weight-only days are not scans`() {
        val body = listOf(
            BodyMeasurement("2026-09-20", weightKg = 77.5, bodyFatPercent = 18.0, skeletalMuscleKg = 35.0),
            BodyMeasurement("2026-09-01", weightKg = 80.0, bodyFatPercent = 20.0, skeletalMuscleKg = 34.0),
            BodyMeasurement("2026-09-05", weightKg = 79.0),
            BodyMeasurement("2026-09-10", weightKg = 78.0, bodyFatPercent = 19.0),
        )

        val changes = compositionChanges(scans(body))!!
        val rows = changes.rows.associateBy { it.metric }
        val pair = compositionChanges(scans(body).takeLast(2))!!

        assertEquals(listOf("2026-09-01", "2026-09-10", "2026-09-20"), scans(body).map { it.date })
        assertEquals(listOf("2026-09-20", "2026-09-10", "2026-09-01"), listOf(changes.latest, changes.previous, changes.first).map { it.toString() })
        assertNull(pair.first)
        assertNull(compositionChanges(emptyList()))
        assertEquals(-1.0, rows.getValue(CompositionMetric.BODY_FAT).sincePrevious!!, 1e-9)
        assertEquals(-2.0, rows.getValue(CompositionMetric.BODY_FAT).sinceFirst!!, 1e-9)
        assertEquals(77.5 * 0.18 - 78.0 * 0.19, rows.getValue(CompositionMetric.FAT_MASS).sincePrevious!!, 1e-9)
        assertEquals(77.5 * 0.82 - 80.0 * 0.8, rows.getValue(CompositionMetric.LEAN_MASS).sinceFirst!!, 1e-9)
        assertNull(rows.getValue(CompositionMetric.MUSCLE).sincePrevious)
        assertEquals(1.0, rows.getValue(CompositionMetric.MUSCLE).sinceFirst!!, 1e-9)
        assertNull(pair.rows.single { it.metric == CompositionMetric.BODY_FAT }.sinceFirst)
    }

    @Test
    fun `a reading with only a visceral fat level is a scan, and the level is compared like the rest`() {
        val body = listOf(
            BodyMeasurement("2026-09-01", weightKg = 80.0, bodyFatPercent = 20.0, visceralFatLevel = 9),
            BodyMeasurement("2026-10-01", visceralFatLevel = 7),
        )

        val visceral = compositionChanges(scans(body))!!.rows.single { it.metric == CompositionMetric.VISCERAL_FAT }

        assertEquals(listOf("2026-09-01", "2026-10-01"), scans(body).map { it.date })
        assertEquals(7.0, visceral.latest, 0.0)
        assertEquals(-2.0, visceral.sincePrevious!!, 0.0)
    }

    @Test
    fun `weeks start on the locale's first day, run oldest first to this week, and count workouts, minutes and weight times reps`() {
        val zone = java.time.ZoneOffset.UTC
        val sunday = Workout(
            uuid = "sun",
            title = "Day",
            startedAt = Instant.parse("2026-10-18T10:00:00Z"),
            endedAt = Instant.parse("2026-10-18T11:30:00Z"),
            exercises = listOf(WorkoutExercise(key = 1, exerciseId = "squat_barbell", restSeconds = 0, sets = listOf(WorkoutSet(weightKg = 100.0, reps = 5), WorkoutSet(weightKg = 20.0, reps = 5, type = io.fitgymlog.app.model.SetType.WARMUP)))),
        )
        val tuesday = sunday.copy(uuid = "tue", startedAt = Instant.parse("2026-10-20T10:00:00Z"), endedAt = Instant.parse("2026-10-20T10:45:00Z"))
        val today = LocalDate.parse("2026-10-22")

        val mondayWeeks = weeklyTotals(listOf(sunday, tuesday), WeeklyMeasure.WORKOUTS, today, java.time.DayOfWeek.MONDAY, zone, weeks = 3)
        val sundayWeeks = weeklyTotals(listOf(sunday, tuesday), WeeklyMeasure.WORKOUTS, today, java.time.DayOfWeek.SUNDAY, zone, weeks = 3)
        val minutes = weeklyTotals(listOf(sunday, tuesday), WeeklyMeasure.TIME, today, java.time.DayOfWeek.SUNDAY, zone, weeks = 1)
        val volume = weeklyTotals(listOf(sunday, tuesday), WeeklyMeasure.VOLUME, today, java.time.DayOfWeek.SUNDAY, zone, weeks = 1)

        assertEquals(listOf("2026-10-05", "2026-10-12", "2026-10-19"), mondayWeeks.map { it.date.toString() })
        assertEquals(listOf(0.0, 1.0, 1.0), mondayWeeks.map { it.value })
        assertEquals(listOf(0.0, 0.0, 2.0), sundayWeeks.map { it.value })
        assertEquals(135.0, minutes.single().value, 0.0)
        assertEquals(2 * (500.0 + 100.0), volume.single().value, 0.0)
    }

    @Test
    fun `a workout counts in the week of its local date`() {
        val lateSundayInUtc = workout("2026-10-18T23:30:00Z", "squat_barbell")
        val today = LocalDate.parse("2026-10-22")

        val seoul = weeklyTotals(listOf(lateSundayInUtc), WeeklyMeasure.WORKOUTS, today, java.time.DayOfWeek.MONDAY, java.time.ZoneId.of("Asia/Seoul"), weeks = 2)
        val newYork = weeklyTotals(listOf(lateSundayInUtc), WeeklyMeasure.WORKOUTS, today, java.time.DayOfWeek.MONDAY, java.time.ZoneId.of("America/New_York"), weeks = 2)

        assertEquals(listOf(0.0, 1.0), seoul.map { it.value })
        assertEquals(listOf(1.0, 0.0), newYork.map { it.value })
    }

    @Test
    fun `exercises are counted once per workout, most recently done first and by id on a tie, and one with nothing logged is left out`() {
        val history = listOf(
            workout("2026-10-20T10:00:00Z", "squat_barbell", "squat_barbell", "bench_press_barbell"),
            workout("2026-10-22T10:00:00Z", "deadlift_barbell", "squat_barbell", empty = "plank"),
        )

        val uses = exerciseUses(history.reversed())

        assertEquals(listOf("deadlift_barbell", "squat_barbell", "bench_press_barbell"), uses.map { it.exerciseId })
        assertEquals(listOf(1, 2, 1), uses.map { it.sessions })
        assertEquals(uses, exerciseUses(history))
        assertEquals(Instant.parse("2026-10-22T10:00:00Z"), uses.first().lastDone)
    }

    private fun rated(at: String, effort: Int?, minutes: Long = 60) = workout(at, "squat_barbell").let { it.copy(effort = effort, endedAt = it.startedAt.plusSeconds(minutes * 60)) }

    @Test
    fun `training load is effort times minutes, and an unrated workout adds nothing`() {
        assertEquals(420.0, trainingLoad(rated("2026-10-20T10:00:00Z", effort = 7, minutes = 60)), 0.0)
        assertEquals(0.0, trainingLoad(rated("2026-10-20T10:00:00Z", effort = null)), 0.0)
    }

    @Test
    fun `the last 7 days compare with the weekly average of the last 28, and within a tenth is steady`() {
        val now = Instant.parse("2026-10-24T12:00:00Z")
        // Three weeks of one 60-minute workout at 5, then this week two at 7: 840 against (900 + 840) / 4 = 435.
        val history = listOf("2026-10-02", "2026-10-09", "2026-10-16").map { rated("${it}T10:00:00Z", effort = 5) } +
            listOf(rated("2026-10-20T10:00:00Z", effort = 7), rated("2026-10-22T10:00:00Z", effort = 7))
        assertEquals(LoadComparison(93, LoadTrend.ABOVE), loadComparison(history, now))

        val steady = listOf("2026-10-02", "2026-10-09", "2026-10-16", "2026-10-22").map { rated("${it}T10:00:00Z", effort = 6) }
        assertEquals(LoadTrend.STEADY, loadComparison(steady, now)?.trend)

        val lighter = steady.dropLast(1) + rated("2026-10-22T10:00:00Z", effort = 3)
        assertEquals(LoadTrend.BELOW, loadComparison(lighter, now)?.trend)
    }

    @Test
    fun `no comparison until there is a rated week before the last 7 days`() {
        val now = Instant.parse("2026-10-24T12:00:00Z")
        assertNull(loadComparison(listOf(rated("2026-10-22T10:00:00Z", effort = null)), now))
        assertNull(loadComparison(listOf(rated("2026-09-01T10:00:00Z", effort = 8)), now))
        // A first week of ratings has no usual week yet, rather than reading as four times above it.
        assertNull(loadComparison(listOf(rated("2026-10-22T10:00:00Z", effort = 8), rated("2026-10-23T10:00:00Z", effort = 6)), now))
    }

    @Test
    fun `each day's 7-day load and usual week roll forward, and nothing rated gives no series`() {
        val zone = java.time.ZoneOffset.UTC
        val today = LocalDate.parse("2026-10-24")
        val history = listOf(rated("2026-10-10T10:00:00Z", effort = 5), rated("2026-10-22T10:00:00Z", effort = 7))

        val series = loadSeries(history, today, zone, days = 14)!!

        assertEquals(LocalDate.parse("2026-10-11"), series.lastWeek.first().date)
        // Oct 22 falls in the 7 days to today; Oct 10 has dropped out of them but is still in the usual week.
        assertEquals(420.0, series.lastWeek.last().value, 0.0)
        assertEquals((300.0 + 420.0) / 4, series.usualWeek.last().value, 0.0)
        // Oct 16: the 7 days back to Oct 10 hold its workout.
        assertEquals(300.0, series.lastWeek.single { it.date == LocalDate.parse("2026-10-16") }.value, 0.0)
        assertNull(loadSeries(listOf(rated("2026-10-22T10:00:00Z", effort = null)), today, zone))
    }

    @Test
    fun `empty weeks before the first rating are left off, down to two weeks`() {
        val zone = java.time.ZoneOffset.UTC
        val today = LocalDate.parse("2026-10-24")

        val recent = loadSeries(listOf(rated("2026-10-22T10:00:00Z", effort = 7)), today, zone)!!
        val older = loadSeries(listOf(rated("2026-09-20T10:00:00Z", effort = 7)), today, zone)!!

        assertEquals(LocalDate.parse("2026-10-11"), recent.lastWeek.first().date)
        assertEquals(LocalDate.parse("2026-09-20"), older.usualWeek.first().date)
    }
}
