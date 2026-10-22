package io.fitgymlog.app.data

import android.content.Context
import androidx.health.connect.client.records.ExerciseSegment
import io.fitgymlog.app.SHIPPED_LANGUAGES
import io.fitgymlog.app.catalog.ExerciseCatalog
import io.fitgymlog.app.links.WebLinks
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.NewExercise
import io.fitgymlog.app.resourcesIn
import io.fitgymlog.app.transfer.ExerciseMatcher
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

    fun find(id: String): Exercise = exercises.value.byId(id)

    suspend fun createCustom(exercise: NewExercise): Exercise {
        val entity = CustomExerciseEntity(
            id = "custom-${UUID.randomUUID()}", name = exercise.name.trim(), kind = exercise.kind.name, updatedAt = System.currentTimeMillis(),
        )
        dao.upsert(entity)
        exercise.links.forEach { addLink(entity.id, it) }
        return entity.toExercise().copy(userLinks = exercise.links)
    }

    suspend fun restoreCustom(id: String, name: String, kind: ExerciseKind) = dao.save(id, name, kind.name, System.currentTimeMillis())

    suspend fun addLink(exerciseId: String, url: String) {
        val link = WebLinks.normalize(url) ?: return
        linkDao.add(exerciseId, link, System.currentTimeMillis())
    }

    suspend fun removeLink(exerciseId: String, url: String) = tombstones.deleteLink(exerciseId, url, System.currentTimeMillis())

    /** Names in every language we ship plus custom names, so imports match regardless of the source app's language. */
    suspend fun matcher(): ExerciseMatcher {
        val languages = SHIPPED_LANGUAGES.map(context::resourcesIn)
        val names = ExerciseCatalog.all.associate { builtin ->
            builtin.id to listOf(builtin.hevyName) + languages.map { it.getString(builtin.nameRes) }
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
}

fun List<Exercise>.byId(id: String): Exercise = firstOrNull { it.id == id } ?: Exercise(
    id = id,
    englishName = id,
    kind = ExerciseKind.WEIGHT_REPS,
    segmentType = ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT,
    customName = id,
)
