package io.fitgymlog.app.sync

import androidx.room.withTransaction
import io.fitgymlog.app.data.CustomExerciseEntity
import io.fitgymlog.app.data.ExerciseLinkEntity
import io.fitgymlog.app.data.GymDatabase
import io.fitgymlog.app.data.RoutineEntity
import io.fitgymlog.app.data.TombstoneEntity
import io.fitgymlog.app.data.toEntity
import io.fitgymlog.app.data.toGraph
import io.fitgymlog.app.data.toRoutine
import io.fitgymlog.app.data.toWorkout
import io.fitgymlog.app.links.WebLinks
import io.fitgymlog.app.transfer.toBackup
import io.fitgymlog.app.transfer.toRoutine
import io.fitgymlog.app.transfer.toWorkout
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

class SyncStore(private val database: GymDatabase) {
    private val workouts = database.workouts()
    private val routines = database.routines()
    private val customExercises = database.customExercises()
    private val links = database.exerciseLinks()
    private val tombstones = database.tombstones()

    /** Emits whenever anything that syncs changes on this phone; logging sets in a workout does not count. */
    val changes: Flow<Unit> = combine(
        workouts.finishedStamp().distinctUntilChanged(),
        routines.all(),
        customExercises.all(),
        links.all(),
        tombstones.all(),
    ) { _, _, _, _, _ -> }

    suspend fun snapshot(): SyncSnapshot = database.withTransaction {
        SyncSnapshot(
            workouts = workouts.allFinished().map { it.toWorkout() }.map { SyncWorkout(it.uuid, it.updatedAt.toEpochMilli(), it.toBackup()) },
            routines = routines.allNow().map { it.toRoutine() }.map { SyncRoutine(it.uuid, it.updatedAt.toEpochMilli(), it.toBackup()) },
            customExercises = customExercises.allNow().map { SyncCustomExercise(it.id, it.updatedAt, it.name, it.kind) },
            links = links.allNow().filter { WebLinks.isWeb(it.url) }.map { SyncLink(it.exerciseId, it.url, it.addedAt) },
            tombstones = tombstones.allNow().map { SyncTombstone(it.kind, it.key, it.deletedAt) },
        ).sorted()
    }

    /** Writes the merged changes, skipping any item edited or deleted on this phone after [snapshot] was read. */
    suspend fun apply(changes: SyncChanges, now: Instant) = database.withTransaction {
        val stored = tombstones.allNow()
        val removedHere = stored.associate { (it.kind to it.key) to it.deletedAt }
        val removedInMerge = changes.tombstones.associate { (it.kind to it.key) to it.deletedAt }

        changes.upsertWorkouts.forEach { synced ->
            val existing = workouts.finishedVersionOf(synced.uuid)
            if (!SyncMerge.isNewest(synced.updatedAt, existing?.updatedAt, removedHere[TombstoneKind.WORKOUT to synced.uuid])) return@forEach
            val workout = synced.workout.toWorkout().copy(id = existing?.id ?: 0, uuid = synced.uuid, updatedAt = Instant.ofEpochMilli(synced.updatedAt))
            workouts.save(workout.toGraph())
        }
        changes.deleteWorkouts.forEach { uuid ->
            removedInMerge[TombstoneKind.WORKOUT to uuid]?.let { workouts.deleteFinishedIfNotNewer(uuid, it) }
        }
        changes.upsertRoutines.forEach { synced ->
            val existing = routines.findByUuid(synced.uuid)
            if (!SyncMerge.isNewest(synced.updatedAt, existing?.updatedAt, removedHere[TombstoneKind.ROUTINE to synced.uuid])) return@forEach
            val routine = synced.routine.toRoutine()
            routines.save(
                RoutineEntity(
                    id = existing?.id ?: 0,
                    uuid = synced.uuid,
                    updatedAt = synced.updatedAt,
                    name = routine.name,
                    position = existing?.position ?: routines.nextPosition(),
                ),
                routine.exercises.map { it.toEntity() },
            )
        }
        changes.deleteRoutines.forEach { uuid ->
            removedInMerge[TombstoneKind.ROUTINE to uuid]?.let { routines.deleteIfNotNewer(uuid, it) }
        }
        changes.upsertCustomExercises.forEach { synced ->
            if (SyncMerge.isNewest(synced.updatedAt, customExercises.updatedAtOf(synced.id), deletedAt = null)) {
                customExercises.upsert(CustomExerciseEntity(synced.id, synced.name, synced.kind, synced.updatedAt))
            }
        }
        changes.upsertLinks.forEach { synced ->
            val removedAt = removedHere[TombstoneKind.LINK to TombstoneKind.linkKey(synced.exerciseId, synced.url)]
            if (SyncMerge.isNewest(synced.addedAt, links.addedAtOf(synced.exerciseId, synced.url), removedAt)) {
                links.upsert(ExerciseLinkEntity(synced.exerciseId, synced.url, synced.addedAt))
            }
        }
        changes.removeLinks.forEach { link ->
            removedInMerge[TombstoneKind.LINK to TombstoneKind.linkKey(link.exerciseId, link.url)]?.let {
                links.deleteIfNotNewer(link.exerciseId, link.url, it)
            }
        }
        tombstones.delete(stored.filter { SyncMerge.isExpired(it.deletedAt, now) })
        tombstones.put(
            changes.tombstones
                .filter { incoming -> removedHere[incoming.kind to incoming.key]?.let { incoming.deletedAt > it } ?: true }
                .map { TombstoneEntity(it.kind, it.key, it.deletedAt) },
        )
    }
}
