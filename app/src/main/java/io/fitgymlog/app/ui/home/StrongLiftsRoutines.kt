package io.fitgymlog.app.ui.home

import io.fitgymlog.app.data.RoutineRepository
import io.fitgymlog.app.model.Routine
import io.fitgymlog.app.model.RoutineExercise

/** StrongLifts 5x5 workouts A and B, alternated each session; deadlift is one set of five. */
object StrongLiftsRoutines {
    private const val SETS = 5
    private const val REPS = 5

    /** Adds whichever of A and B is missing, so tapping twice doesn't duplicate them. */
    suspend fun addTo(routines: RoutineRepository, restSeconds: Int) {
        fun lift(id: String, sets: Int = SETS) = RoutineExercise(id, sets, REPS, null, restSeconds)
        val existing = routines.all().map { it.name }.toSet()
        listOf(
            Routine(name = "StrongLifts A", exercises = listOf(lift("squat_barbell"), lift("bench_press_barbell"), lift("bent_over_row_barbell"))),
            Routine(name = "StrongLifts B", exercises = listOf(lift("squat_barbell"), lift("overhead_press_barbell"), lift("deadlift_barbell", sets = 1))),
        ).filter { it.name !in existing }.forEach { routines.save(it) }
    }
}
