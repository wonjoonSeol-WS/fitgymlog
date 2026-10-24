package io.fitgymlog.app.workout

import io.fitgymlog.app.data.Settings
import io.fitgymlog.app.model.Equipment
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.model.WorkoutSet
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class WarmupTest {
    private val working = List(3) { WorkoutSet(weightKg = 60.0, reps = 5) }
    private val squat = Exercise("squat_barbell", "Squat (Barbell)", ExerciseKind.WEIGHT_REPS, segmentType = 0, equipment = Equipment.BARBELL)

    @Test
    fun `a warm-up is five reps on the empty bar, ahead of the working sets`() {
        val warmedUp = withWarmup(working, barKg = 20.0)

        assertEquals(WorkoutSet(type = SetType.WARMUP, weightKg = 20.0, reps = 5), warmedUp.first())
        assertEquals(working, warmedUp.drop(1))
    }

    @Test
    fun `last session's warm-ups come back as they were done, fresh to log, in place of five on the bar`() {
        val done = Instant.parse("2026-10-23T10:00:00Z")
        val last = listOf(
            WorkoutSet(type = SetType.WARMUP, weightKg = 20.0, reps = 10, completedAt = done),
            WorkoutSet(type = SetType.WARMUP, weightKg = 40.0, reps = 6, rpe = 6.0, completedAt = done),
        ) + working.map { it.copy(completedAt = done) }

        val warmedUp = withWarmup(working, barKg = 20.0, previous = last)

        assertEquals(listOf(WorkoutSet(type = SetType.WARMUP, weightKg = 20.0, reps = 10), WorkoutSet(type = SetType.WARMUP, weightKg = 40.0, reps = 6)), warmedUp.take(2))
        assertEquals(working, warmedUp.drop(2))
    }

    @Test
    fun `no warm-up when one is already there or the work is the bar itself, but one when the weight is not known yet`() {
        val copied = listOf(WorkoutSet(type = SetType.WARMUP, weightKg = 30.0, reps = 3)) + working
        val barOnly = List(3) { WorkoutSet(weightKg = 20.0, reps = 5) }
        val blank = List(3) { WorkoutSet() }

        assertEquals(copied, withWarmup(copied, barKg = 20.0))
        assertEquals(barOnly, withWarmup(barOnly, barKg = 20.0))
        assertEquals(4, withWarmup(blank, barKg = 20.0).size)
    }

    @Test
    fun `only with the setting on, only for barbell lifts, and on the bar the settings name in their unit`() {
        val on = Settings(warmupSet = true, unit = WeightUnit.LB, barLb = 35.0)

        assertEquals(working, Settings(warmupSet = false).warmedUp(squat, working))
        assertEquals(working, on.warmedUp(squat.copy(equipment = Equipment.DUMBBELL), working))
        assertEquals(35.0 * 0.45359237, on.warmedUp(squat, working).first().weightKg!!, 1e-9)
    }
}
