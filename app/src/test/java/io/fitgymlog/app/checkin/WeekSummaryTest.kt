package io.fitgymlog.app.checkin

import io.fitgymlog.app.coach.BodyMeasurement
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutExercise
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.ui.ChartPoint
import io.fitgymlog.app.ui.exercise.ProgressMetric
import io.fitgymlog.app.ui.stats.weightTrend
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeekSummaryTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.parse("2026-10-22")
    private val done = Instant.parse("2026-10-01T10:00:00Z")

    private fun set(kg: Double, reps: Int, target: Int? = reps, type: SetType = SetType.NORMAL, completed: Boolean = true) =
        WorkoutSet(type = type, weightKg = kg, reps = reps, targetReps = target, completedAt = done.takeIf { completed })

    private fun workout(id: Long, day: String, vararg blocks: Pair<String, List<WorkoutSet>>, minutes: Long = 60) = Workout(
        id = id,
        uuid = "w$id",
        title = "Day",
        startedAt = Instant.parse("${day}T10:00:00Z"),
        endedAt = Instant.parse("${day}T10:00:00Z").plusSeconds(minutes * 60),
        exercises = blocks.mapIndexed { index, (exerciseId, sets) -> WorkoutExercise(index.toLong(), exerciseId, restSeconds = 0, sets = sets) },
    )

    private fun summary(history: List<Workout>, body: List<BodyMeasurement> = emptyList(), firstDay: DayOfWeek = DayOfWeek.MONDAY) =
        weekSummary(history, body, today, firstDay, zone) { ExerciseKind.WEIGHT_REPS }

    @Test
    fun `the week runs from the locale's first day and is set against the week before`() {
        val history = listOf(
            workout(1, "2026-10-12", "squat_barbell" to listOf(set(100.0, 5))),
            workout(2, "2026-10-18", "squat_barbell" to listOf(set(100.0, 5)), minutes = 90),
            workout(3, "2026-10-20", "squat_barbell" to listOf(set(105.0, 5)), "bench_press_barbell" to listOf(set(60.0, 5)), minutes = 45),
        )

        val mondays = summary(history)
        val sundays = summary(history, firstDay = DayOfWeek.SUNDAY)

        assertEquals(LocalDate.parse("2026-10-19"), mondays.start)
        assertEquals(LocalDate.parse("2026-10-25"), mondays.end)
        assertEquals(1 to 2, mondays.workouts)
        assertEquals(45L to 150L, mondays.minutes)
        assertEquals(825.0 to 1_000.0, mondays.volumeKg)
        assertEquals(LocalDate.parse("2026-10-18"), sundays.start)
        assertEquals(2 to 1, sundays.workouts)
    }

    @Test
    fun `new records are this week's, gathered by exercise, and a record set last week is not one`() {
        val history = listOf(
            workout(1, "2026-10-12", "squat_barbell" to listOf(set(100.0, 5)), "bench_press_barbell" to listOf(set(60.0, 5))),
            workout(2, "2026-10-14", "squat_barbell" to listOf(set(110.0, 5)), "bench_press_barbell" to listOf(set(65.0, 5))),
            workout(3, "2026-10-20", "squat_barbell" to listOf(set(112.5, 3)), "bench_press_barbell" to listOf(set(60.0, 5))),
            workout(4, "2026-10-21", "squat_barbell" to listOf(set(100.0, 10))),
        )

        assertEquals(listOf(WeekRecord("squat_barbell", listOf(ProgressMetric.HEAVIEST, ProgressMetric.ESTIMATED_MAX))), summary(history).records)
    }

    @Test
    fun `missed reps count done working sets short of their target, among those with a target`() {
        val history = listOf(
            workout(
                1,
                "2026-10-20",
                "squat_barbell" to listOf(
                    set(20.0, 3, target = 5, type = SetType.WARMUP),
                    set(100.0, 5, target = 5),
                    set(100.0, 4, target = 5),
                    set(100.0, 3, target = 5),
                    set(100.0, 2, target = 5, completed = false),
                    set(80.0, 4, target = 8, type = SetType.DROP),
                ),
                "bench_press_barbell" to listOf(set(60.0, 5, target = 5)),
                "bicep_curl_dumbbell" to listOf(set(12.0, 6, target = null)),
            ),
            workout(2, "2026-10-13", "bench_press_barbell" to listOf(set(60.0, 3, target = 5))),
        )

        assertEquals(listOf(MissedReps("squat_barbell", missed = 2, sets = 3)), summary(history).missed)
    }

    @Test
    fun `the weight is the trend at the week's last reading against the one before the week, and readings after the week are left out`() {
        val body = listOf("2026-10-10" to 80.0, "2026-10-17" to 79.0, "2026-10-20" to 78.5, "2026-10-21" to 78.0, "2026-10-26" to 74.0)
            .map { (date, kg) -> BodyMeasurement(date, weightKg = kg) }
        val trend = weightTrend(body.take(4).map { ChartPoint(LocalDate.parse(it.date), it.weightKg!!) })

        val week = summary(emptyList(), body)

        assertEquals(trend[3].value, week.weightTrendKg!!, 1e-9)
        assertEquals(trend[3].value - trend[1].value, week.weightChangeKg!!, 1e-9)
        assertNull(summary(emptyList(), body.take(2)).weightTrendKg)
        assertNull(summary(emptyList(), body.drop(2).take(2)).weightChangeKg)
    }

    @Test
    fun `the check-in comes on the evening of the week's last day, or a week later once that has passed`() {
        fun at(text: String) = ZonedDateTime.parse(text)

        assertEquals(at("2026-10-25T19:00Z"), nextCheckIn(at("2026-10-22T10:00Z"), DayOfWeek.MONDAY))
        assertEquals(at("2026-10-25T19:00Z"), nextCheckIn(at("2026-10-25T18:59Z"), DayOfWeek.MONDAY))
        assertEquals(at("2026-11-01T19:00Z"), nextCheckIn(at("2026-10-25T19:00Z"), DayOfWeek.MONDAY))
        assertEquals(at("2026-10-24T19:00Z"), nextCheckIn(at("2026-10-22T10:00Z"), DayOfWeek.SUNDAY))
    }
}
