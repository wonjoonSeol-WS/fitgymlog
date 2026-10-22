package io.fitgymlog.app.sync

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** What one phone must change locally to match the merged snapshot. */
data class SyncChanges(
    val upsertWorkouts: List<SyncWorkout> = emptyList(),
    val deleteWorkouts: List<String> = emptyList(),
    val upsertRoutines: List<SyncRoutine> = emptyList(),
    val deleteRoutines: List<String> = emptyList(),
    val upsertCustomExercises: List<SyncCustomExercise> = emptyList(),
    val addLinks: List<SyncLink> = emptyList(),
    val removeLinks: List<SyncLink> = emptyList(),
    val tombstones: List<SyncTombstone> = emptyList(),
) {
    val touchesData: Boolean
        get() = upsertWorkouts.isNotEmpty() || deleteWorkouts.isNotEmpty() || upsertRoutines.isNotEmpty() ||
            deleteRoutines.isNotEmpty() || upsertCustomExercises.isNotEmpty() || addLinks.isNotEmpty() || removeLinks.isNotEmpty()
}

/**
 * Two-way merge of the phone's data with the Drive copy. The newer edit of each item wins, a
 * deletion beats any edit made before it, and both sides keep everything else, so phones that
 * sync in any order end up with the same data.
 */
object SyncMerge {
    val TOMBSTONE_LIFETIME: Duration = Duration.ofDays(365)

    fun merge(local: SyncSnapshot, remote: SyncSnapshot, now: Instant): SyncSnapshot {
        val tombstones = (local.tombstones + remote.tombstones)
            .groupBy { it.kind to it.key }
            .values
            .map { versions -> versions.maxBy { it.deletedAt } }
            .filter { Duration.ofMillis(now.toEpochMilli() - it.deletedAt) < TOMBSTONE_LIFETIME }
        val deletedAt = tombstones.associate { (it.kind to it.key) to it.deletedAt }
        fun alive(kind: String, key: String, updatedAt: Long) = deletedAt[kind to key]?.let { updatedAt > it } ?: true

        val workouts = newest(local.workouts, remote.workouts, { it.uuid }, { it.updatedAt })
            .filter { alive(TombstoneKind.WORKOUT, it.uuid, it.updatedAt) }
        val (kept, duplicates) = withoutDuplicates(workouts)
        val duplicateTombstones = duplicates.map { SyncTombstone(TombstoneKind.WORKOUT, it.uuid, maxOf(now.toEpochMilli(), it.updatedAt)) }

        return SyncSnapshot(
            workouts = kept,
            routines = newest(local.routines, remote.routines, { it.uuid }, { it.updatedAt })
                .filter { alive(TombstoneKind.ROUTINE, it.uuid, it.updatedAt) },
            customExercises = newest(local.customExercises, remote.customExercises, { it.id }, { it.updatedAt }),
            links = newest(local.links, remote.links, { TombstoneKind.linkKey(it.exerciseId, it.url) }, { it.addedAt })
                .filter { alive(TombstoneKind.LINK, TombstoneKind.linkKey(it.exerciseId, it.url), it.addedAt) },
            tombstones = tombstones + duplicateTombstones,
        ).sorted()
    }

    fun changes(local: SyncSnapshot, merged: SyncSnapshot): SyncChanges {
        val localWorkouts = local.workouts.associateBy { it.uuid }
        val localRoutines = local.routines.associateBy { it.uuid }
        val localCustom = local.customExercises.associateBy { it.id }
        val localLinks = local.links.map { TombstoneKind.linkKey(it.exerciseId, it.url) }.toSet()
        val mergedLinks = merged.links.map { TombstoneKind.linkKey(it.exerciseId, it.url) }.toSet()
        return SyncChanges(
            upsertWorkouts = merged.workouts.filter { localWorkouts[it.uuid] != it },
            deleteWorkouts = (localWorkouts.keys - merged.workouts.map { it.uuid }.toSet()).sorted(),
            upsertRoutines = merged.routines.filter { localRoutines[it.uuid] != it },
            deleteRoutines = (localRoutines.keys - merged.routines.map { it.uuid }.toSet()).sorted(),
            upsertCustomExercises = merged.customExercises.filter { localCustom[it.id] != it },
            addLinks = merged.links.filter { TombstoneKind.linkKey(it.exerciseId, it.url) !in localLinks },
            removeLinks = local.links.filter { TombstoneKind.linkKey(it.exerciseId, it.url) !in mergedLinks },
            tombstones = merged.tombstones,
        )
    }

    private fun <T> newest(local: List<T>, remote: List<T>, key: (T) -> String, updatedAt: (T) -> Long): List<T> {
        val byKey = LinkedHashMap<String, T>()
        remote.forEach { byKey[key(it)] = it }
        local.forEach { item ->
            val other = byKey[key(item)]
            if (other == null || updatedAt(item) >= updatedAt(other)) byKey[key(item)] = item
        }
        return byKey.values.toList()
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
