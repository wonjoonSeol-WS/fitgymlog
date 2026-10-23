package io.fitgymlog.app.workout

import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.RoutineExercise
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Test

class StartingSetsTest {
    private val lastRun = listOf(WorkoutSet(durationSeconds = 1_500, level = 8, speedKmh = 7.5))

    @Test
    fun `a routine's planned time and incline win over last session's`() {
        val planned = RoutineExercise("treadmill", sets = 1, reps = null, weightKg = null, restSeconds = 0, durationSeconds = 1_200, level = 12, speedKmh = 6.5)

        assertEquals(listOf(WorkoutSet(durationSeconds = 1_200, level = 12, speedKmh = 6.5)), startingSets(planned, ExerciseKind.CARDIO, lastRun))
    }

    @Test
    fun `cardio takes each field the routine leaves open from last session on its own`() {
        val planned = RoutineExercise("treadmill", sets = 1, reps = null, weightKg = null, restSeconds = 0, durationSeconds = 1_200)

        assertEquals(listOf(WorkoutSet(durationSeconds = 1_200, level = 8, speedKmh = 7.5)), startingSets(planned, ExerciseKind.CARDIO, lastRun))
    }

    @Test
    fun `after a missed session the next one aims at last session's targets again, not at the reps done`() {
        val missed = listOf(5, 5, 5, 4, 3).map { WorkoutSet(weightKg = 60.0, reps = it, targetReps = 5) }
        val planned = RoutineExercise("squat_barbell", sets = 5, reps = null, weightKg = null, restSeconds = 180)

        assertEquals(List(5) { 5 }, startingSets(planned, ExerciseKind.WEIGHT_REPS, missed).map { it.reps })
    }

    @Test
    fun `cardio the routine leaves open starts from last session`() {
        val open = RoutineExercise("treadmill", sets = 1, reps = null, weightKg = null, restSeconds = 0)

        assertEquals(lastRun, startingSets(open, ExerciseKind.CARDIO, lastRun))
    }

    @Test
    fun `a plank keeps planned seconds but no level or speed, and lifts never get time, level or speed`() {
        val plank = RoutineExercise("plank", sets = 2, reps = null, weightKg = null, restSeconds = 60, durationSeconds = 45, level = 3, speedKmh = 5.0)
        val squat = RoutineExercise("squat_barbell", sets = 1, reps = 5, weightKg = 60.0, restSeconds = 180, durationSeconds = 45, level = 3, speedKmh = 5.0)

        assertEquals(List(2) { WorkoutSet(durationSeconds = 45) }, startingSets(plank, ExerciseKind.DURATION, emptyList()))
        assertEquals(listOf(WorkoutSet(weightKg = 60.0, reps = 5)), startingSets(squat, ExerciseKind.WEIGHT_REPS, lastRun))
    }

    @Test
    fun `a lift without a planned weight takes the closest earlier weight from last session`() {
        val planned = RoutineExercise("squat_barbell", sets = 3, reps = 5, weightKg = null, restSeconds = 180)
        val previous = listOf(WorkoutSet(weightKg = 60.0, reps = 5), WorkoutSet(weightKg = null, reps = 5), WorkoutSet(weightKg = 62.5, reps = 3))

        assertEquals(listOf(60.0, 60.0, 62.5), startingSets(planned, ExerciseKind.WEIGHT_REPS, previous).map { it.weightKg })
    }

    @Test
    fun `a routine takes last session's working sets, not its warm-up or drop set`() {
        val previous = listOf(
            WorkoutSet(type = SetType.WARMUP, weightKg = 20.0, reps = 5),
            WorkoutSet(weightKg = 60.0, reps = 5),
            WorkoutSet(weightKg = 62.5, reps = 3),
            WorkoutSet(type = SetType.DROP, weightKg = 40.0, reps = 12),
        )
        val planned = RoutineExercise("squat_barbell", sets = 3, reps = null, weightKg = null, restSeconds = 180)

        val sets = startingSets(planned, ExerciseKind.WEIGHT_REPS, previous)

        assertEquals(listOf(60.0, 62.5, 62.5), sets.map { it.weightKg })
        assertEquals(listOf(5, 3, 3), sets.map { it.reps })
    }

    @Test
    fun `last time pairs warm-ups with warm-ups and working sets with working sets, so an added warm-up shifts nothing`() {
        val warmup = WorkoutSet(type = SetType.WARMUP, weightKg = 20.0, reps = 5)
        val work = listOf(WorkoutSet(weightKg = 75.0, reps = 5), WorkoutSet(weightKg = 80.0, reps = 5))
        val today = listOf(warmup) + List(3) { WorkoutSet(weightKg = 80.0, reps = 5) }

        assertEquals(listOf(null) + work + listOf(null), previousRows(today, work))
        assertEquals(listOf(warmup) + work + listOf(null), previousRows(today, listOf(warmup) + work))
        assertEquals(work, previousRows(work, listOf(warmup) + work))
    }
}
