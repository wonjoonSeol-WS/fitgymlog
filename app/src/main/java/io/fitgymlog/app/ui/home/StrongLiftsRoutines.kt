package io.fitgymlog.app.ui.home

import io.fitgymlog.app.data.RoutineRepository
import io.fitgymlog.app.model.Routine
import io.fitgymlog.app.model.RoutineExercise
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** StrongLifts 5x5 workouts A and B, alternated each session; deadlift is one set of five. */
object StrongLiftsRoutines {
    private const val SETS = 5
    private const val REPS = 5
    private val adding = Mutex()

    /** Returns how many of A and B were missing and added. */
    suspend fun addTo(routines: RoutineRepository, restSeconds: Int): Int =
        addMissing(existing = { routines.all() }, save = { routines.save(it) }, restSeconds = restSeconds)

    internal suspend fun addMissing(existing: suspend () -> List<Routine>, save: suspend (Routine) -> Unit, restSeconds: Int): Int =
        adding.withLock {
            val names = existing().map { it.name }.toSet()
            val missing = routines(restSeconds).filter { it.name !in names }
            missing.forEach { save(it) }
            missing.size
        }

    private fun routines(restSeconds: Int): List<Routine> {
        fun lift(id: String, sets: Int = SETS) = RoutineExercise(id, sets, REPS, null, restSeconds)
        return listOf(
            Routine(name = "StrongLifts A", exercises = listOf(lift("squat_barbell"), lift("bench_press_barbell"), lift("bent_over_row_barbell"))),
            Routine(name = "StrongLifts B", exercises = listOf(lift("squat_barbell"), lift("overhead_press_barbell"), lift("deadlift_barbell", sets = 1))),
        )
    }
}
