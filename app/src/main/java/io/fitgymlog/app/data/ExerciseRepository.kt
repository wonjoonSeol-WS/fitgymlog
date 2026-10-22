package io.fitgymlog.app.data

import android.content.Context
import android.content.res.Configuration
import androidx.health.connect.client.records.ExerciseSegment
import io.fitgymlog.app.catalog.ExerciseCatalog
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.transfer.ExerciseMatcher
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ExerciseRepository(
    private val context: Context,
    private val dao: CustomExerciseDao,
    scope: CoroutineScope,
) {
    private val builtins: List<Exercise> = ExerciseCatalog.all.map {
        Exercise(
            id = it.id,
            englishName = it.hevyName,
            kind = it.kind,
            segmentType = it.segmentType,
            links = it.links,
            nameRes = it.nameRes,
        )
    }

    val exercises: StateFlow<List<Exercise>> = dao.all()
        .map { customs -> builtins + customs.map { it.toExercise() } }
        .stateIn(scope, SharingStarted.Eagerly, builtins)

    fun find(id: String): Exercise = exercises.value.firstOrNull { it.id == id } ?: unknown(id)

    suspend fun createCustom(name: String, kind: ExerciseKind, links: List<String> = emptyList()): Exercise {
        val entity = CustomExerciseEntity(
            id = "custom-${UUID.randomUUID()}",
            name = name.trim(),
            kind = kind.name,
            links = links.joinToString("\n"),
        )
        dao.upsert(entity)
        return entity.toExercise()
    }

    suspend fun updateCustom(exercise: Exercise) {
        dao.upsert(
            CustomExerciseEntity(
                id = exercise.id,
                name = exercise.customName.orEmpty(),
                kind = exercise.kind.name,
                links = exercise.links.joinToString("\n"),
            ),
        )
    }

    /** Names in every language we ship plus custom names, so imports match regardless of the source app's language. */
    suspend fun matcher(): ExerciseMatcher {
        val korean = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.KOREAN) })
        val english = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        val names = ExerciseCatalog.all.associate { builtin ->
            builtin.id to listOf(builtin.hevyName, english.getString(builtin.nameRes), korean.getString(builtin.nameRes))
        } + dao.allNow().associate { it.id to listOf(it.name) }
        return ExerciseMatcher(names)
    }

    private fun CustomExerciseEntity.toExercise() = Exercise(
        id = id,
        englishName = name,
        kind = kindOrDefault(),
        segmentType = ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT,
        links = links.lines().filter { it.isNotBlank() },
        customName = name,
    )

    private fun unknown(id: String) = Exercise(
        id = id,
        englishName = id,
        kind = ExerciseKind.WEIGHT_REPS,
        segmentType = ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT,
        customName = id,
    )
}
