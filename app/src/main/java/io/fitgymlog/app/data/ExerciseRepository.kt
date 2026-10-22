package io.fitgymlog.app.data

import android.content.Context
import android.content.res.Configuration
import androidx.health.connect.client.records.ExerciseSegment
import io.fitgymlog.app.catalog.ExerciseCatalog
import io.fitgymlog.app.links.WebLinks
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.transfer.ExerciseMatcher
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class ExerciseRepository(
    private val context: Context,
    private val dao: CustomExerciseDao,
    private val linkDao: ExerciseLinkDao,
    private val tombstones: TombstoneDao,
    scope: CoroutineScope,
) {
    private val builtins: List<Exercise> = ExerciseCatalog.all.map {
        Exercise(
            id = it.id,
            englishName = it.hevyName,
            kind = it.kind,
            segmentType = it.segmentType,
            equipment = it.equipment,
            links = it.links,
            nameRes = it.nameRes,
        )
    }

    val exercises: StateFlow<List<Exercise>> = combine(dao.all(), linkDao.all()) { customs, links ->
        val userLinks = links.groupBy({ it.exerciseId }, { it.url })
        (builtins + customs.map { it.toExercise() }).map { exercise ->
            userLinks[exercise.id]?.let { exercise.copy(userLinks = it) } ?: exercise
        }
    }.stateIn(scope, SharingStarted.Eagerly, builtins)

    fun find(id: String): Exercise = exercises.value.firstOrNull { it.id == id } ?: unknown(id)

    suspend fun createCustom(name: String, kind: ExerciseKind, links: List<String> = emptyList()): Exercise {
        val entity = CustomExerciseEntity(id = "custom-${UUID.randomUUID()}", name = name.trim(), kind = kind.name, updatedAt = System.currentTimeMillis())
        dao.upsert(entity)
        links.forEach { addLink(entity.id, it) }
        return entity.toExercise().copy(userLinks = links)
    }

    suspend fun restoreCustom(id: String, name: String, kind: ExerciseKind) = dao.save(id, name, kind.name, System.currentTimeMillis())

    suspend fun addLink(exerciseId: String, url: String) {
        val link = WebLinks.normalize(url) ?: return
        linkDao.add(exerciseId, link, System.currentTimeMillis())
    }

    suspend fun removeLink(exerciseId: String, url: String) = tombstones.deleteLink(exerciseId, url, System.currentTimeMillis())

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
