package io.fitgymlog.app.coach

import android.util.Log
import io.fitgymlog.app.LOG_TAG
import io.fitgymlog.app.sync.SideSync
import io.fitgymlog.app.sync.SyncMerge
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

@Serializable
data class CoachSyncFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val memories: List<Memory> = emptyList(),
    val forgotten: Map<String, Long> = emptyMap(),
    val reviews: List<Review> = emptyList(),
    val measurements: List<BodyMeasurement> = emptyList(),
    val removedMeasurements: Map<String, Long> = emptyMap(),
) {
    companion object {
        const val FORMAT = "fitgymlog-coach"
        const val VERSION = 1
    }
}

/** Notes, workout reviews and body measurements follow the user across phones; the chat stays on the phone it happened on. */
object CoachSyncMerge {
    private val memoryOrder = compareBy<Memory>({ it.updatedAt }, { coachJson.encodeToString(Memory.serializer(), it) })
    private val reviewOrder = compareBy<Review>({ it.updatedAt }, { coachJson.encodeToString(Review.serializer(), it) })
    private val measurementOrder = compareBy<BodyMeasurement>({ it.updatedAt }, { coachJson.encodeToString(BodyMeasurement.serializer(), it) })

    fun merge(local: CoachData, remote: CoachSyncFile, now: Instant): CoachData {
        val forgotten = removals(local.forgotten, remote.forgotten, now)
        val removedMeasurements = removals(local.removedMeasurements, remote.removedMeasurements, now)
        val memories = newest(local.memories + remote.memories, memoryOrder, forgotten, Memory::key, Memory::updatedAt) { forgetKey(it.key) }
        val days = (local.measurements + remote.measurements).filter { (removedMeasurements[it.date] ?: Long.MIN_VALUE) < it.updatedAt }.groupBy { it.date }
        val measurements = newest(local.measurements + remote.measurements, measurementOrder, removedMeasurements, BodyMeasurement::date, BodyMeasurement::updatedAt)
            .map { kept -> days.getValue(kept.date).sortedWith(measurementOrder.reversed()).fold(kept) { merged, other -> merged.filledFrom(other) } }
        val reviews = (local.reviews + remote.reviews).groupBy { it.workoutUuid }.values.map { versions -> versions.maxWith(reviewOrder) }
        return local.copy(
            memories = memories.sortedBy { it.key },
            forgotten = forgotten,
            reviews = reviews.sortedWith(reviewOrder),
            measurements = measurements.sortedBy { it.date },
            removedMeasurements = removedMeasurements,
        )
    }

    private fun removals(local: Map<String, Long>, remote: Map<String, Long>, now: Instant): Map<String, Long> =
        (local.keys + remote.keys).associateWith { maxOf(local[it] ?: 0, remote[it] ?: 0) }.filterValues { !SyncMerge.isExpired(it, now) }

    /** The newest version of each key, unless it was removed after that version was made. */
    private fun <T> newest(
        versions: List<T>,
        order: Comparator<T>,
        removed: Map<String, Long>,
        keyOf: (T) -> String,
        updatedAt: (T) -> Long,
        removalKeyOf: (T) -> String = keyOf,
    ): List<T> = versions.groupBy(keyOf).values.map { it.maxWith(order) }.filter { (removed[removalKeyOf(it)] ?: Long.MIN_VALUE) < updatedAt(it) }

    fun fileOf(data: CoachData) = CoachSyncFile(
        memories = data.memories.sortedBy { it.key },
        forgotten = data.forgotten,
        reviews = data.reviews.sortedWith(reviewOrder),
        measurements = data.measurements.sortedBy { it.date },
        removedMeasurements = data.removedMeasurements,
    )

    /** Null for a file that doesn't read or comes from a newer app; such a file is left alone rather than overwritten. */
    fun decode(text: String): CoachSyncFile? = runCatching { coachJson.decodeFromString(CoachSyncFile.serializer(), text) }
        .getOrNull()
        ?.takeIf { it.format == CoachSyncFile.FORMAT && it.version <= CoachSyncFile.VERSION }
        ?.let { file -> file.copy(measurements = file.measurements.filter { runCatching { LocalDate.parse(it.date) }.isSuccess }) }

    fun encode(file: CoachSyncFile): String = coachJson.encodeToString(CoachSyncFile.serializer(), file)
}

class CoachSync(private val store: CoachStore, private val settings: CoachSettingsRepository) : SideSync {
    @Volatile
    private var synced: CoachSyncFile? = null

    override val fileName = "fitgymlog-coach.json"

    override val enabled: Boolean get() = settings.settings.value.syncNotes

    override val changes: Flow<Unit> = flow {
        store.current()
        emitAll(store.data.map(CoachSyncMerge::fileOf).distinctUntilChanged().drop(1).map { })
    }

    override suspend fun changedSinceSync(): Boolean = CoachSyncMerge.fileOf(store.current()) != synced

    override suspend fun merge(remote: String?, now: Instant): String? {
        val file = if (remote == null) CoachSyncFile() else CoachSyncMerge.decode(remote)
        if (file == null) {
            Log.w(LOG_TAG, "Coach sync file unreadable or from a newer app; left as it is")
            synced = CoachSyncMerge.fileOf(store.current())
            return null
        }
        store.update { CoachSyncMerge.merge(it, file, now) }
        val merged = CoachSyncMerge.fileOf(store.current())
        synced = merged
        return if (merged == file) null else CoachSyncMerge.encode(merged)
    }
}
