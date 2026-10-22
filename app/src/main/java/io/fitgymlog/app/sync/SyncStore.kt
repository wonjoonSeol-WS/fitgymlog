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
import io.fitgymlog.app.transfer.toBackup
import io.fitgymlog.app.transfer.toRoutine
import io.fitgymlog.app.transfer.toWorkout
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** Reads the phone's syncable data as a snapshot and writes merged changes back in one transaction. */
class SyncStore(private val database: GymDatabase) {
    private val workouts = database.workouts()
    private val routines = database.routines()
    private val customExercises = database.customExercises()
    private val links = database.exerciseLinks()
    private val tombstones = database.tombstones()

    /** Emits whenever anything that syncs changes on this phone. */
    val changes: Flow<Unit> = combine(workouts.finished(), routines.all(), customExercises.all(), links.all(), tombstones.all()) { _, _, _, _, _ -> }

    suspend fun snapshot(): SyncSnapshot = SyncSnapshot(
        workouts = workouts.allFinished().map { it.toWorkout() }.map { SyncWorkout(it.uuid, it.updatedAt.toEpochMilli(), it.toBackup()) },
        routines = routines.allNow().map { it.toRoutine() }.map { SyncRoutine(it.uuid, it.updatedAt.toEpochMilli(), it.toBackup()) },
        customExercises = customExercises.allNow().map { SyncCustomExercise(it.id, it.updatedAt, it.name, it.kind) },
        links = links.allNow().map { SyncLink(it.exerciseId, it.url, it.addedAt) },
        tombstones = tombstones.allNow().map { SyncTombstone(it.kind, it.key, it.deletedAt) },
    ).sorted()

    suspend fun apply(changes: SyncChanges, now: Instant) = database.withTransaction {
        changes.upsertWorkouts.forEach { synced ->
            val workout = synced.workout.toWorkout().copy(
                id = workouts.finishedIdOf(synced.uuid) ?: 0,
                uuid = synced.uuid,
                updatedAt = Instant.ofEpochMilli(synced.updatedAt),
            )
            workouts.save(workout.toGraph())
        }
        changes.deleteWorkouts.forEach { workouts.deleteFinished(it) }
        changes.upsertRoutines.forEach { synced ->
            val existing = routines.findByUuid(synced.uuid)
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
        changes.deleteRoutines.forEach { routines.deleteByUuid(it) }
        changes.upsertCustomExercises.forEach { customExercises.upsert(CustomExerciseEntity(it.id, it.name, it.kind, it.updatedAt)) }
        changes.addLinks.forEach { links.insert(ExerciseLinkEntity(it.exerciseId, it.url, it.addedAt)) }
        changes.removeLinks.forEach { links.delete(it.exerciseId, it.url) }
        tombstones.put(changes.tombstones.map { TombstoneEntity(it.kind, it.key, it.deletedAt) })
        tombstones.deleteOlderThan(now.minus(SyncMerge.TOMBSTONE_LIFETIME).toEpochMilli())
    }
}
