package io.fitgymlog.app.sync

import io.fitgymlog.app.transfer.BackupRoutine
import io.fitgymlog.app.transfer.BackupWorkout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object TombstoneKind {
    const val WORKOUT = "workout"
    const val ROUTINE = "routine"
    const val LINK = "link"

    fun linkKey(exerciseId: String, url: String) = "$exerciseId\n$url"
}

/**
 * Everything that syncs between phones, as stored in the Drive app data file. In-progress
 * workouts and settings stay on each phone.
 */
@Serializable
data class SyncSnapshot(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val workouts: List<SyncWorkout> = emptyList(),
    val routines: List<SyncRoutine> = emptyList(),
    val customExercises: List<SyncCustomExercise> = emptyList(),
    val links: List<SyncLink> = emptyList(),
    val tombstones: List<SyncTombstone> = emptyList(),
) {
    /** A canonical order, so two snapshots with the same content compare and serialize equal. */
    fun sorted() = copy(
        workouts = workouts.sortedBy { it.uuid },
        routines = routines.sortedBy { it.uuid },
        customExercises = customExercises.sortedBy { it.id },
        links = links.sortedWith(compareBy({ it.exerciseId }, { it.url })),
        tombstones = tombstones.sortedWith(compareBy({ it.kind }, { it.key })),
    )

    fun encode(): String = json.encodeToString(serializer(), sorted())

    companion object {
        const val FORMAT = "fitgymlog-sync"
        const val VERSION = 1
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(text: String): SyncSnapshot = json.decodeFromString(serializer(), text).also {
            require(it.format == FORMAT) { "not a FitGymLog sync file" }
            require(it.version <= VERSION) { "sync file version ${it.version} is newer than this app" }
        }.sorted()
    }
}

@Serializable
data class SyncWorkout(val uuid: String, val updatedAt: Long, val workout: BackupWorkout)

@Serializable
data class SyncRoutine(val uuid: String, val updatedAt: Long, val routine: BackupRoutine)

@Serializable
data class SyncCustomExercise(val id: String, val updatedAt: Long, val name: String, val kind: String)

@Serializable
data class SyncLink(val exerciseId: String, val url: String, val addedAt: Long)

@Serializable
data class SyncTombstone(val kind: String, val key: String, val deletedAt: Long)
