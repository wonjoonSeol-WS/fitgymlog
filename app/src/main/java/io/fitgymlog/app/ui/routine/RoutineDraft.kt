package io.fitgymlog.app.ui.routine

import io.fitgymlog.app.model.Routine
import io.fitgymlog.app.model.RoutineExercise
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.ui.SPEED_RANGE_KMH
import io.fitgymlog.app.ui.fromDisplaySpeed
import io.fitgymlog.app.ui.tenths
import io.fitgymlog.app.ui.workout.LEVEL_CHOICES
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val MIN_PLANNED_SETS = 1
private const val MAX_PLANNED_SETS = 20

fun plannedSets(input: String): Int? = input.toIntOrNull()?.coerceIn(MIN_PLANNED_SETS, MAX_PLANNED_SETS)

/** Kept as typed, so the field doesn't jump while it's being edited; the range applies when the routine is saved. */
fun plannedSpeed(input: String, unit: WeightUnit): Double? = input.toDoubleOrNull()?.let { fromDisplaySpeed(tenths(it), unit) }

/** [key] tells rows apart in the list, since the same exercise can be planned twice. */
@Serializable
data class PlannedExercise(
    val key: Long,
    val exerciseId: String,
    val sets: Int,
    val reps: Int?,
    val weightKg: Double?,
    val restSeconds: Int,
    val durationSeconds: Int? = null,
    val level: Int? = null,
    val speedKmh: Double? = null,
) {
    constructor(key: Long, exercise: RoutineExercise) : this(
        key = key,
        exerciseId = exercise.exerciseId,
        sets = exercise.sets,
        reps = exercise.reps,
        weightKg = exercise.weightKg,
        restSeconds = exercise.restSeconds,
        durationSeconds = exercise.durationSeconds,
        level = exercise.level,
        speedKmh = exercise.speedKmh,
    )

    fun toRoutineExercise() = RoutineExercise(
        exerciseId = exerciseId,
        sets = sets,
        reps = reps,
        weightKg = weightKg,
        restSeconds = restSeconds,
        durationSeconds = durationSeconds,
        level = level?.coerceIn(LEVEL_CHOICES.first(), LEVEL_CHOICES.last()),
        speedKmh = speedKmh?.coerceIn(SPEED_RANGE_KMH),
    )
}

@Serializable
data class RoutineDraft(
    val name: String = "",
    val planned: List<PlannedExercise> = emptyList(),
    val nextKey: Long = 0,
    val savedName: String = "",
    val savedExercises: List<PlannedExercise> = emptyList(),
) {
    val canSave: Boolean get() = name.isNotBlank() && planned.isNotEmpty()

    val hasChanges: Boolean
        get() = name != savedName || planned.map { it.toRoutineExercise() } != savedExercises.map { it.toRoutineExercise() }

    fun rename(name: String) = copy(name = name)

    fun add(exercise: RoutineExercise) = copy(planned = planned + PlannedExercise(nextKey, exercise), nextKey = nextKey + 1)

    fun update(key: Long, transform: (PlannedExercise) -> PlannedExercise) =
        copy(planned = planned.map { if (it.key == key) transform(it).copy(key = key) else it })

    fun move(key: Long, offset: Int): RoutineDraft {
        val from = planned.indexOfFirst { it.key == key }
        val to = from + offset
        if (from < 0 || to !in planned.indices) return this
        return copy(planned = planned.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun remove(key: Long) = copy(planned = planned.filterNot { it.key == key })

    fun toRoutine(id: Long) = Routine(id = id, name = name.trim(), exercises = planned.map { it.toRoutineExercise() })

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

        fun of(routine: Routine): RoutineDraft {
            val planned = routine.exercises.mapIndexed { index, exercise -> PlannedExercise(index.toLong(), exercise) }
            return RoutineDraft(routine.name, planned, planned.size.toLong(), routine.name, planned)
        }

        fun decode(text: String): RoutineDraft? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}
