package io.fitgymlog.app.coach

import io.fitgymlog.app.model.Routine
import io.fitgymlog.app.model.RoutineExercise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineChangesTest {
    private fun planned(id: String, sets: Int = 5, reps: Int? = 5, seconds: Int? = null) =
        RoutineExercise(id, sets = sets, reps = reps, weightKg = null, restSeconds = 180, durationSeconds = seconds)

    private val dayA = Routine(id = 4, name = "Day A", exercises = listOf(planned("squat_barbell"), planned("bench_press_barbell"), planned("squat_barbell", sets = 1, reps = 8)))

    @Test
    fun `a change applies only to its own routine, once, to an exercise there, or for an add to one not there yet`() {
        assertTrue(dayA.accepts(RoutineChange(4, "squat_barbell", sets = 3)))
        assertTrue(dayA.accepts(RoutineChange(4, "deadlift_barbell", ChangeAction.ADD)))
        assertFalse(dayA.accepts(RoutineChange(5, "squat_barbell", sets = 3)))
        assertFalse(dayA.accepts(RoutineChange(4, "squat_barbell", sets = 3, applied = true)))
        assertFalse(dayA.accepts(RoutineChange(4, "deadlift_barbell", ChangeAction.REMOVE)))
        assertFalse(dayA.accepts(RoutineChange(4, "squat_barbell", ChangeAction.ADD)))
    }

    @Test
    fun `changes apply together at an exercise's first place, and report which ones applied`() {
        val row = planned("bent_over_row_barbell", sets = 3, reps = 8)
        val chosen = mapOf(
            1 to RoutineChange(4, "squat_barbell", sets = 3, reps = 8),
            3 to RoutineChange(4, "bench_press_barbell", ChangeAction.REMOVE),
            5 to RoutineChange(4, "bent_over_row_barbell", ChangeAction.ADD, sets = 3, reps = 8),
            6 to RoutineChange(4, "bicep_curl_dumbbell", ChangeAction.ADD),
            8 to RoutineChange(4, "bench_press_barbell", sets = 2),
            9 to RoutineChange(4, "bent_over_row_barbell", ChangeAction.ADD),
        )

        val applied = dayA.applying(chosen, mapOf("bent_over_row_barbell" to row))

        assertEquals(setOf(1, 3, 5), applied.indices)
        assertEquals(
            listOf(planned("squat_barbell", sets = 3, reps = 8), planned("squat_barbell", sets = 1, reps = 8), row),
            applied.routine.exercises,
        )
    }

    @Test
    fun `cardio minutes become the planned time, and what a change leaves out stays as planned`() {
        val cardio = Routine(id = 7, name = "Cardio", exercises = listOf(planned("treadmill", sets = 1, reps = null, seconds = 1_200)))

        val applied = cardio.applying(mapOf(0 to RoutineChange(7, "treadmill", minutes = 25)), emptyMap())

        assertEquals(listOf(planned("treadmill", sets = 1, reps = null, seconds = 1_500)), applied.routine.exercises)
    }
}
