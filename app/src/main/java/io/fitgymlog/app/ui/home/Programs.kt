package io.fitgymlog.app.ui.home

import androidx.annotation.StringRes
import io.fitgymlog.app.R
import io.fitgymlog.app.data.RoutineRepository
import io.fitgymlog.app.model.Routine
import io.fitgymlog.app.model.RoutineExercise
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class Program(@param:StringRes val title: Int, @param:StringRes val summary: Int) {
    STRONGLIFTS(R.string.stronglifts_5x5, R.string.program_stronglifts_summary),
    FULL_BODY(R.string.program_full_body, R.string.program_full_body_summary),
    UPPER_LOWER(R.string.program_upper_lower, R.string.program_upper_lower_summary),
    PUSH_PULL_LEGS(R.string.program_push_pull_legs, R.string.program_push_pull_legs_summary),
    ;

    fun routines(restSeconds: Int): List<PlannedRoutine> {
        fun routine(@StringRes name: Int, vararg lifts: RoutineExercise) = PlannedRoutine(name, lifts.toList())
        fun lift(id: String, sets: Int, reps: Int) = RoutineExercise(id, sets, reps, weightKg = null, restSeconds = restSeconds)
        return when (this) {
            STRONGLIFTS -> listOf(
                routine(
                    R.string.routine_stronglifts_a,
                    lift("squat_barbell", 5, 5), lift("bench_press_barbell", 5, 5), lift("bent_over_row_barbell", 5, 5),
                ),
                routine(
                    R.string.routine_stronglifts_b,
                    lift("squat_barbell", 5, 5), lift("overhead_press_barbell", 5, 5), lift("deadlift_barbell", 1, 5),
                ),
            )
            FULL_BODY -> listOf(
                routine(
                    R.string.routine_full_body,
                    lift("leg_press_machine", 3, 10), lift("bench_press_dumbbell", 3, 10), lift("lat_pulldown_cable", 3, 10),
                    lift("romanian_deadlift_dumbbell", 3, 10), lift("shoulder_press_dumbbell", 3, 10), lift("seated_cable_row_v_grip", 3, 10),
                ),
            )
            UPPER_LOWER -> listOf(
                routine(
                    R.string.routine_upper,
                    lift("bench_press_barbell", 3, 8), lift("bent_over_row_barbell", 3, 8), lift("shoulder_press_dumbbell", 3, 10),
                    lift("lat_pulldown_cable", 3, 10), lift("bicep_curl_dumbbell", 3, 12), lift("triceps_pushdown", 3, 12),
                ),
                routine(
                    R.string.routine_lower,
                    lift("squat_barbell", 3, 8), lift("romanian_deadlift_barbell", 3, 8), lift("leg_press_machine", 3, 10),
                    lift("lying_leg_curl_machine", 3, 12), lift("standing_calf_raise_machine", 3, 12),
                ),
            )
            PUSH_PULL_LEGS -> listOf(
                routine(
                    R.string.routine_push,
                    lift("bench_press_barbell", 3, 8), lift("incline_bench_press_dumbbell", 3, 10), lift("shoulder_press_dumbbell", 3, 10),
                    lift("lateral_raise_dumbbell", 3, 12), lift("triceps_pushdown", 3, 12),
                ),
                routine(
                    R.string.routine_pull,
                    lift("lat_pulldown_cable", 3, 10), lift("seated_cable_row_v_grip", 3, 10), lift("bent_over_row_dumbbell", 3, 10),
                    lift("face_pull", 3, 15), lift("bicep_curl_dumbbell", 3, 12), lift("hammer_curl_dumbbell", 3, 12),
                ),
                routine(
                    R.string.routine_legs,
                    lift("squat_barbell", 3, 8), lift("romanian_deadlift_barbell", 3, 10), lift("leg_press_machine", 3, 10),
                    lift("leg_extension_machine", 3, 12), lift("lying_leg_curl_machine", 3, 12), lift("standing_calf_raise_machine", 3, 15),
                ),
            )
        }
    }
}

data class PlannedRoutine(@param:StringRes val name: Int, val exercises: List<RoutineExercise>)

object Programs {
    private val adding = Mutex()

    suspend fun add(program: Program, routines: RoutineRepository, namesOf: (Int) -> List<String>, restSeconds: Int): Int =
        addMissing(program.routines(restSeconds), namesOf, existing = { routines.all() }, save = { routines.save(it) })

    /**
     * [namesOf] gives a routine's name in the app language first, then in the other languages, so a routine
     * added before the app language changed still counts as there.
     */
    internal suspend fun addMissing(
        wanted: List<PlannedRoutine>,
        namesOf: (Int) -> List<String>,
        existing: suspend () -> List<Routine>,
        save: suspend (Routine) -> Unit,
    ): Int = adding.withLock {
        val taken = existing().map { it.name }.toSet()
        val missing = wanted.filter { planned -> namesOf(planned.name).none { it in taken } }
        missing.forEach { save(Routine(name = namesOf(it.name).first(), exercises = it.exercises)) }
        missing.size
    }
}
