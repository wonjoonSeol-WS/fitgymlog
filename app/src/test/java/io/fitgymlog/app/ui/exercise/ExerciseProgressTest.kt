package io.fitgymlog.app.ui.exercise

import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutExercise
import io.fitgymlog.app.model.WorkoutSet
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseProgressTest {
    private val monday = LocalDate.parse("2026-10-19")
    private val wednesday = LocalDate.parse("2026-10-21")

    private fun set(kg: Double, reps: Int, type: SetType = SetType.NORMAL) = WorkoutSet(weightKg = kg, reps = reps, type = type)

    private fun workout(id: Long, day: Int, vararg blocks: Pair<String, List<WorkoutSet>>) = Workout(
        id = id,
        title = "Day",
        startedAt = Instant.parse("2026-10-01T10:00:00Z").plusSeconds(86_400L * day),
        exercises = blocks.mapIndexed { index, (exerciseId, sets) -> WorkoutExercise(index.toLong(), exerciseId, restSeconds = 0, sets = sets) },
    )

    private val kinds = mapOf("squat_barbell" to ExerciseKind.WEIGHT_REPS, "pull_up_assisted" to ExerciseKind.ASSISTED_BODYWEIGHT)

    @Test
    fun `a timed exercise's record is its longest working hold, and a warm-up or a hold of nothing does not count`() {
        fun hold(seconds: Int, type: SetType = SetType.NORMAL) = WorkoutSet(durationSeconds = seconds, type = type)
        val sessions = listOf(monday to listOf(hold(60)), wednesday to listOf(hold(45), hold(90), hold(120, SetType.WARMUP), hold(0)))

        val best = records(sessions, ExerciseKind.DURATION)

        assertEquals(listOf(ProgressMetric.DURATION), best.keys.toList())
        assertEquals(wednesday, best.getValue(ProgressMetric.DURATION).date)
        assertEquals(90.0, best.getValue(ProgressMetric.DURATION).value, 0.0)
    }

    @Test
    fun `a record is the best session, the first to reach it on a tie, and for assistance the least`() {
        val sessions = listOf(
            monday to listOf(set(100.0, 3)),
            wednesday to listOf(set(100.0, 5)),
            monday.plusDays(7) to listOf(set(95.0, 8)),
        )
        val pullUps = listOf(monday to listOf(set(30.0, 6)), wednesday to listOf(set(20.0, 4)))

        val squat = records(sessions, ExerciseKind.WEIGHT_REPS)
        val assisted = records(pullUps, ExerciseKind.ASSISTED_BODYWEIGHT)

        assertEquals(monday, squat.getValue(ProgressMetric.HEAVIEST).date)
        assertEquals(95.0 * (1 + 8 / 30.0), squat.getValue(ProgressMetric.ESTIMATED_MAX).value, 1e-9)
        assertEquals(20.0, assisted.getValue(ProgressMetric.LEAST_ASSISTANCE).value, 0.0)
        assertEquals(6.0, assisted.getValue(ProgressMetric.REPS).value, 0.0)
    }

    @Test
    fun `a workout sets a record only by beating every earlier session, never on its first or on a tie, and counts its blocks together`() {
        val history = listOf(
            workout(3, 7, "squat_barbell" to listOf(set(100.0, 5)), "squat_barbell" to listOf(set(105.0, 1)), "squat_barbell" to listOf(set(100.0, 3))),
            workout(1, 0, "squat_barbell" to listOf(set(100.0, 5)), "pull_up_assisted" to listOf(set(30.0, 6))),
            workout(2, 3, "squat_barbell" to listOf(set(120.0, 5, SetType.WARMUP), set(100.0, 5)), "pull_up_assisted" to listOf(set(25.0, 5))),
        )

        val marks = recordsByWorkout(history) { kinds.getValue(it) }

        assertTrue(1L !in marks)
        assertEquals(mapOf("pull_up_assisted" to listOf(ProgressMetric.LEAST_ASSISTANCE)), marks[2L])
        assertEquals(mapOf("squat_barbell" to listOf(ProgressMetric.HEAVIEST)), marks[3L])
    }

    @Test
    fun `each kind of exercise offers the measures that mean progress for it`() {
        assertEquals(listOf(ProgressMetric.HEAVIEST, ProgressMetric.ESTIMATED_MAX), ExerciseKind.WEIGHT_REPS.progressMetrics())
        assertEquals(listOf(ProgressMetric.HEAVIEST), ExerciseKind.WEIGHTED_BODYWEIGHT.progressMetrics())
        assertEquals(listOf(ProgressMetric.LEAST_ASSISTANCE, ProgressMetric.REPS), ExerciseKind.ASSISTED_BODYWEIGHT.progressMetrics())
        assertEquals(listOf(ProgressMetric.REPS), ExerciseKind.BODYWEIGHT_REPS.progressMetrics())
        assertEquals(listOf(ProgressMetric.DURATION), ExerciseKind.CARDIO.progressMetrics())
    }

    @Test
    fun `the heaviest and the best estimated max can come from different sets, and warm-ups never count`() {
        val sets = listOf(
            WorkoutSet(weightKg = 100.0, reps = 5, type = SetType.WARMUP),
            WorkoutSet(weightKg = 80.0, reps = 1),
            WorkoutSet(weightKg = 70.0, reps = 8),
        )
        val sessions = listOf(monday to sets)

        assertEquals(80.0, progressPoints(sessions, ProgressMetric.HEAVIEST).single().value, 0.0)
        assertEquals(70.0 * (1 + 8 / 30.0), progressPoints(sessions, ProgressMetric.ESTIMATED_MAX).single().value, 1e-9)
    }

    @Test
    fun `assistance counts down, a set with no reps is ignored, and a session with nothing to measure has no point`() {
        val sessions = listOf(
            monday to listOf(WorkoutSet(weightKg = 30.0, reps = 6), WorkoutSet(weightKg = 20.0, reps = 0)),
            wednesday to listOf(WorkoutSet(weightKg = 25.0, reps = 5), WorkoutSet(weightKg = 30.0, reps = 8)),
            wednesday.plusDays(2) to listOf(WorkoutSet(weightKg = 20.0)),
        )

        assertEquals(listOf(monday to 30.0, wednesday to 25.0), progressPoints(sessions, ProgressMetric.LEAST_ASSISTANCE).map { it.date to it.value })
        assertEquals(listOf(6.0, 8.0), progressPoints(sessions, ProgressMetric.REPS).map { it.value })
    }

    @Test
    fun `points run oldest first even when sessions come newest first`() {
        val newestFirst = listOf(wednesday to listOf(WorkoutSet(weightKg = 62.5, reps = 5)), monday to listOf(WorkoutSet(weightKg = 60.0, reps = 5)))

        assertEquals(listOf(monday to 60.0, wednesday to 62.5), progressPoints(newestFirst, ProgressMetric.HEAVIEST).map { it.date to it.value })
    }

    @Test
    fun `the estimate is the weight itself for a single, and is not made past twelve reps`() {
        assertEquals(100.0, estimatedMax(100.0, 1)!!, 0.0)
        assertEquals(60.0 * 1.4, estimatedMax(60.0, 12)!!, 1e-9)
        assertNull(estimatedMax(60.0, 13))
        assertNull(estimatedMax(60.0, 0))
    }

    @Test
    fun `a measure with fewer than two sessions to draw is not offered`() {
        val highReps = listOf(monday to listOf(WorkoutSet(weightKg = 40.0, reps = 8)), wednesday to listOf(WorkoutSet(weightKg = 42.5, reps = 15)))

        assertEquals(listOf(ProgressMetric.HEAVIEST), chartableMetrics(highReps, ExerciseKind.WEIGHT_REPS).keys.toList())
    }
}
