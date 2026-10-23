package io.fitgymlog.app.coach

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import io.fitgymlog.app.LOG_TAG
import io.fitgymlog.app.catching
import io.fitgymlog.app.sync.SyncMerge
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CoachData(
    val memories: List<Memory> = emptyList(),
    /** When each forgotten note was dropped, by [forgetKey], so sync doesn't bring it back and its wording isn't kept. */
    val forgotten: Map<String, Long> = emptyMap(),
    val chat: List<ChatTurn> = emptyList(),
    /** Older chat folded into a few sentences; turns up to [summarizedUntil] are covered by it. */
    val chatSummary: String = "",
    val summarizedUntil: Long = 0,
    val reviews: List<Review> = emptyList(),
    val weeks: List<WeekReview> = emptyList(),
    val measurements: List<BodyMeasurement> = emptyList(),
    /** When each removed measurement's date was cleared, so sync doesn't bring it back. */
    val removedMeasurements: Map<String, Long> = emptyMap(),
)

internal fun CoachData.remembering(key: String, text: String, until: String?, nowMillis: Long, today: LocalDate) = copy(
    memories = memories.filterNot { it.key == key } + Memory(key, text.trim(), today.toString(), nowMillis, until),
    forgotten = forgotten - forgetKey(key),
)

internal fun CoachData.forgetting(key: String, nowMillis: Long) =
    copy(memories = memories.filterNot { it.key == key }, forgotten = forgotten + (forgetKey(key) to nowMillis))

/** A note's key is made of its first words, so a forgotten one is remembered only by this digest. */
internal fun forgetKey(key: String): String =
    MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)

internal fun CoachData.withReview(review: Review, nowMillis: Long) =
    copy(reviews = reviews.filterNot { it.workoutUuid == review.workoutUuid } + review.copy(updatedAt = nowMillis))

internal fun CoachData.withWeek(review: WeekReview, nowMillis: Long) =
    copy(weeks = (weeks.filterNot { it.weekStart == review.weekStart } + review.copy(updatedAt = nowMillis)).sortedBy { it.weekStart })

internal fun CoachData.weekApplied(weekStart: String, indices: Set<Int>, nowMillis: Long) = copy(
    weeks = weeks.map { week ->
        if (week.weekStart != weekStart) week else week.copy(changes = week.changes.mapIndexed { i, change -> if (i in indices) change.copy(applied = true) else change }, updatedAt = nowMillis)
    },
)

internal fun CoachData.measured(entry: BodyMeasurement, nowMillis: Long) = copy(
    measurements = (measurements.filterNot { it.date == entry.date } + entry.copy(updatedAt = nowMillis)).sortedBy { it.date },
    removedMeasurements = removedMeasurements - entry.date,
)

internal fun CoachData.unmeasured(date: String, nowMillis: Long) =
    copy(measurements = measurements.filterNot { it.date == date }, removedMeasurements = removedMeasurements + (date to nowMillis))

/** A new reading for a day that already has one keeps the fields it leaves blank, so a body-fat-only reading doesn't wipe the weight. */
internal fun CoachData.measuredAlongside(entry: BodyMeasurement, nowMillis: Long): CoachData {
    val existing = measurements.find { it.date == entry.date } ?: return measured(entry, nowMillis)
    return measured(entry.filledFrom(existing), nowMillis)
}

internal fun CoachData.remeasured(from: String, entry: BodyMeasurement, nowMillis: Long) =
    if (from == entry.date) measured(entry, nowMillis) else unmeasured(from, nowMillis).measuredAlongside(entry, nowMillis)

internal fun CoachData.unsummarized(): List<ChatTurn> = chat.filter { it.at > summarizedUntil }

/** Kept only while [asked] is still the last turn, so a reply that returns after a clear or a newer message is dropped. */
internal fun CoachData.answered(asked: ChatTurn, reply: ChatTurn) = if (chat.lastOrNull() == asked) copy(chat = chat + reply) else this

/** Kept only if nothing moved the summary since [from] was read, such as a clear or another summary. */
internal fun CoachData.summarized(from: Long, summary: String, until: Long) =
    if (summarizedUntil != from) this else copy(chatSummary = summary, summarizedUntil = until)

internal fun CoachData.trimmed(now: Instant) = copy(
    chat = chat.takeLast(MAX_CHAT_TURNS),
    reviews = reviews.takeLast(MAX_REVIEWS),
    weeks = weeks.takeLast(MAX_WEEKS),
    forgotten = forgotten.filterValues { !SyncMerge.isExpired(it, now) },
    measurements = measurements.takeLast(MAX_MEASUREMENTS),
    removedMeasurements = removedMeasurements.filterValues { !SyncMerge.isExpired(it, now) },
)

internal val coachJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

private const val MAX_CHAT_TURNS = 200
private const val MAX_REVIEWS = 60
private const val MAX_WEEKS = 12
private const val MAX_MEASUREMENTS = 1_000

class CoachStore(context: Context, scope: CoroutineScope) {
    private val file = AtomicFile(File(context.filesDir, FILE_NAME))
    private val dir = context.filesDir
    private val writing = Mutex()
    private val _data = MutableStateFlow(CoachData())
    val data: StateFlow<CoachData> = _data.asStateFlow()
    private val loaded = scope.async(Dispatchers.IO) { _data.value = load() }

    suspend fun current(): CoachData {
        loaded.await()
        return _data.value
    }

    suspend fun update(transform: (CoachData) -> CoachData) {
        loaded.await()
        writing.withLock {
            val next = transform(_data.value).trimmed(Instant.now())
            if (next == _data.value) return
            withContext(Dispatchers.IO + NonCancellable) { save(next) }
            _data.value = next
        }
    }

    suspend fun remember(key: String, text: String, until: String? = null) =
        update { it.remembering(key, text, until, System.currentTimeMillis(), LocalDate.now()) }

    /** A note typed by the user, under a key that is free in the stored notes. */
    suspend fun add(topic: MemoryTopic, text: String) = update { data ->
        data.remembering(MemoryKeys.forText(topic, text, data.memories.map { it.key }.toSet()), text, null, System.currentTimeMillis(), LocalDate.now())
    }

    suspend fun forget(key: String) = update { it.forgetting(key, System.currentTimeMillis()) }

    suspend fun keepReview(review: Review) = update { it.withReview(review, System.currentTimeMillis()) }

    suspend fun keepWeek(review: WeekReview) = update { it.withWeek(review, System.currentTimeMillis()) }

    suspend fun addMeasurement(entry: BodyMeasurement) = update { it.measuredAlongside(entry, System.currentTimeMillis()) }

    suspend fun editMeasurement(from: String, entry: BodyMeasurement) = update { it.remeasured(from, entry, System.currentTimeMillis()) }

    suspend fun removeMeasurement(date: String) = update { it.unmeasured(date, System.currentTimeMillis()) }

    /** A file that doesn't read is moved aside rather than overwritten by the next save. */
    private fun load(): CoachData {
        if (!file.baseFile.exists()) return CoachData()
        return catching { coachJson.decodeFromString(CoachData.serializer(), file.readFully().decodeToString()) }.getOrElse { error ->
            val aside = File(dir, "$FILE_NAME.bad-${System.currentTimeMillis()}")
            Log.w(LOG_TAG, "Coach data unreadable, moved to ${aside.name}", error)
            file.baseFile.renameTo(aside)
            CoachData()
        }
    }

    private fun save(data: CoachData) {
        val stream = file.startWrite()
        try {
            stream.write(coachJson.encodeToString(CoachData.serializer(), data).toByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private companion object {
        const val FILE_NAME = "coach.json"
    }
}
