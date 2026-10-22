package io.fitgymlog.app.sync

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

data class SyncChanges(
    val upsertWorkouts: List<SyncWorkout> = emptyList(),
    val deleteWorkouts: List<String> = emptyList(),
    val upsertRoutines: List<SyncRoutine> = emptyList(),
    val deleteRoutines: List<String> = emptyList(),
    val upsertCustomExercises: List<SyncCustomExercise> = emptyList(),
    val upsertLinks: List<SyncLink> = emptyList(),
    val removeLinks: List<SyncLink> = emptyList(),
    val tombstones: List<SyncTombstone> = emptyList(),
) {
    val touchesData: Boolean
        get() = upsertWorkouts.isNotEmpty() || deleteWorkouts.isNotEmpty() || upsertRoutines.isNotEmpty() ||
            deleteRoutines.isNotEmpty() || upsertCustomExercises.isNotEmpty() || upsertLinks.isNotEmpty() || removeLinks.isNotEmpty()
}

/**
 * Two-way merge of the phone's data with the Drive copy. The newer edit of each item wins, a
 * deletion beats any edit made before it, and both sides keep everything else, so phones that
 * sync in any order end up with the same data.
 */
object SyncMerge {
    val TOMBSTONE_LIFETIME: Duration = Duration.ofDays(365)

    private val json = Json { encodeDefaults = true }

    fun isExpired(deletedAt: Long, now: Instant): Boolean = deletedAt < now.minus(TOMBSTONE_LIFETIME).toEpochMilli()

    fun isNewest(changedAt: Long, storedAt: Long?, deletedAt: Long?): Boolean =
        (storedAt == null || changedAt >= storedAt) && (deletedAt == null || changedAt > deletedAt)

    fun merge(local: SyncSnapshot, remote: SyncSnapshot, now: Instant): SyncSnapshot {
        val tombstones = latest(local.tombstones + remote.tombstones).filterNot { isExpired(it.deletedAt, now) }
        val deletedAt = tombstones.associate { (it.kind to it.key) to it.deletedAt }
        fun alive(kind: String, key: String, changedAt: Long) = isNewest(changedAt, storedAt = null, deletedAt[kind to key])

        val workouts = newest(local.workouts, remote.workouts, SyncWorkout.serializer(), { it.uuid }, { it.updatedAt })
            .filter { alive(TombstoneKind.WORKOUT, it.uuid, it.updatedAt) }
        val (kept, duplicates) = withoutDuplicates(workouts)
        val duplicateTombstones = duplicates.map { SyncTombstone(TombstoneKind.WORKOUT, it.uuid, maxOf(now.toEpochMilli(), it.updatedAt)) }

        return SyncSnapshot(
            workouts = kept,
            routines = newest(local.routines, remote.routines, SyncRoutine.serializer(), { it.uuid }, { it.updatedAt })
                .filter { alive(TombstoneKind.ROUTINE, it.uuid, it.updatedAt) },
            customExercises = newest(local.customExercises, remote.customExercises, SyncCustomExercise.serializer(), { it.id }, { it.updatedAt }),
            links = newest(local.links.filter { isWebUrl(it.url) }, remote.links.filter { isWebUrl(it.url) }, SyncLink.serializer(), ::linkKey, { it.addedAt })
                .filter { alive(TombstoneKind.LINK, linkKey(it), it.addedAt) },
            tombstones = latest(tombstones + duplicateTombstones),
        ).sorted()
    }

    fun changes(local: SyncSnapshot, merged: SyncSnapshot): SyncChanges {
        val localWorkouts = local.workouts.associateBy { it.uuid }
        val localRoutines = local.routines.associateBy { it.uuid }
        val localCustom = local.customExercises.associateBy { it.id }
        val localLinks = local.links.associateBy(::linkKey)
        val mergedLinks = merged.links.map(::linkKey).toSet()
        return SyncChanges(
            upsertWorkouts = merged.workouts.filter { localWorkouts[it.uuid] != it },
            deleteWorkouts = (localWorkouts.keys - merged.workouts.map { it.uuid }.toSet()).sorted(),
            upsertRoutines = merged.routines.filter { localRoutines[it.uuid] != it },
            deleteRoutines = (localRoutines.keys - merged.routines.map { it.uuid }.toSet()).sorted(),
            upsertCustomExercises = merged.customExercises.filter { localCustom[it.id] != it },
            upsertLinks = merged.links.filter { localLinks[linkKey(it)] != it },
            removeLinks = local.links.filter { linkKey(it) !in mergedLinks },
            tombstones = merged.tombstones,
        )
    }

    private fun linkKey(link: SyncLink) = TombstoneKind.linkKey(link.exerciseId, link.url)

    private fun latest(tombstones: List<SyncTombstone>): List<SyncTombstone> =
        tombstones.groupBy { it.kind to it.key }.values.map { versions -> versions.maxBy { it.deletedAt } }

    /** Copies changed at the same moment are settled by their content, so every phone picks the same one. */
    private fun <T> newest(local: List<T>, remote: List<T>, serializer: KSerializer<T>, key: (T) -> String, updatedAt: (T) -> Long): List<T> {
        val order = compareBy(updatedAt).thenBy { json.encodeToString(serializer, it) }
        return (local + remote).groupBy(key).values.map { versions -> versions.distinct().maxWith(order) }
    }

    /**
     * The same history imported on two phones gets two ids. Workouts that start in the same minute
     * with the same title are one workout; every phone keeps the one with the smaller id.
     */
    private fun withoutDuplicates(workouts: List<SyncWorkout>): Pair<List<SyncWorkout>, List<SyncWorkout>> {
        val groups = workouts.groupBy { minuteOf(it.workout.startedAt) to it.workout.title }.values
        val kept = groups.map { group -> group.minBy { it.uuid } }
        return kept to (workouts - kept.toSet())
    }

    private fun minuteOf(startedAt: String): Instant = Instant.parse(startedAt).truncatedTo(ChronoUnit.MINUTES)
}
