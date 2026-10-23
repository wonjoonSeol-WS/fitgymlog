package io.fitgymlog.app.coach

import java.time.LocalDate
import kotlinx.serialization.Serializable

enum class MemoryTopic {
    HEALTH, FOOD, SCHEDULE, GOALS;

    val prefix: String get() = name.lowercase()

    companion object {
        fun of(key: String): MemoryTopic = entries.firstOrNull { key.startsWith(it.prefix + "/") } ?: HEALTH
    }
}

@Serializable
data class Memory(val key: String, val text: String, val savedAt: String, val updatedAt: Long = 0, val until: String? = null) {
    val topic: MemoryTopic get() = MemoryTopic.of(key)

    fun isActive(today: LocalDate): Boolean = until?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { !it.isBefore(today) } ?: true
}

object MemoryKeys {
    private val separators = Regex("""[^\p{L}\p{N}]+""")
    private const val MAX_NAME = 40

    /** topic/short-name in lower case, or null when the topic is unknown or nothing is left of the name. */
    fun normalize(raw: String): String? {
        val trimmed = raw.trim().lowercase()
        val topic = MemoryTopic.entries.firstOrNull { it.prefix == trimmed.substringBefore('/', missingDelimiterValue = "") } ?: return null
        val name = trimmed.substringAfter('/').replace(separators, "-").trim('-').take(MAX_NAME).trimEnd('-')
        return if (name.isEmpty()) null else "${topic.prefix}/$name"
    }

    /** A key under [topic] for a note typed by the user, made unique among [taken]. */
    fun forText(topic: MemoryTopic, text: String, taken: Set<String>): String {
        val name = text.replace('/', ' ').trim().split(Regex("\\s+")).take(3).joinToString(" ")
        val base = normalize("${topic.prefix}/$name") ?: "${topic.prefix}/note"
        return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { it !in taken }
    }
}

enum class Goal { BUILD, RECOMP, KEEP, LOSE }

enum class Sex { MALE, FEMALE }

data class Calories(val maintenance: Int, val target: Int, val fromLeanMass: Boolean = false)

sealed interface BodyRead {
    data object Reading : BodyRead
    data class Found(val entry: BodyMeasurement) : BodyRead
    data object NothingFound : BodyRead
    data object Unreadable : BodyRead
    data class Failed(val error: Throwable) : BodyRead
}

enum class MeasurementSource { HEALTH_CONNECT, PHOTO, MANUAL }

internal fun BodyMeasurement.filledFrom(other: BodyMeasurement) = copy(
    weightKg = weightKg ?: other.weightKg,
    bodyFatPercent = bodyFatPercent ?: other.bodyFatPercent,
    skeletalMuscleKg = skeletalMuscleKg ?: other.skeletalMuscleKg,
    visceralFatLevel = visceralFatLevel ?: other.visceralFatLevel,
)

/** One day's body composition; [date] is ISO, and a later entry for the same day replaces it. */
@Serializable
data class BodyMeasurement(
    val date: String,
    val weightKg: Double? = null,
    val bodyFatPercent: Double? = null,
    val skeletalMuscleKg: Double? = null,
    val visceralFatLevel: Int? = null,
    val source: MeasurementSource = MeasurementSource.MANUAL,
    val updatedAt: Long = 0,
)

/** [memories] are all stored notes; they reach the model only when [shareNotes] is on. */
data class Profile(
    val bodyweightKg: Double?,
    val goal: Goal,
    val memories: List<Memory>,
    val shareNotes: Boolean,
    val language: String,
    val sex: Sex? = null,
    val calories: Calories? = null,
    val body: List<BodyMeasurement> = emptyList(),
)

data class Target(
    val exerciseId: String,
    val reason: TargetReason,
    val weightKg: Double?,
    val reps: Int?,
    val lastWeightKg: Double?,
    val note: String?,
)

@Serializable
data class Food(val proteinGrams: Int, val nextMealProteinGrams: Int, val summary: String, val ideas: List<String>, val kcal: Int? = null)

@Serializable
data class Review(val workoutUuid: String, val notes: String, val food: Food? = null, val updatedAt: Long = 0)

enum class ChangeAction { ADJUST, ADD, REMOVE }

/** A change to a routine for next week; [sets] counts working sets and [minutes] is for cardio. Weights stay with the progression rules. */
@Serializable
data class RoutineChange(
    val routineId: Long,
    val exerciseId: String,
    val action: ChangeAction = ChangeAction.ADJUST,
    val sets: Int? = null,
    val reps: Int? = null,
    val minutes: Int? = null,
    val applied: Boolean = false,
)

/** The coach's look back at the week starting [weekStart], an ISO date. */
@Serializable
data class WeekReview(val weekStart: String, val text: String, val changes: List<RoutineChange> = emptyList(), val updatedAt: Long = 0)

/** [sets] counts working sets; [minutes] is for cardio. */
@Serializable
data class Change(
    val exerciseId: String,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val workoutUuid: String? = null,
    val applied: Boolean = false,
    val action: ChangeAction = ChangeAction.ADJUST,
    val sets: Int? = null,
    val minutes: Int? = null,
    val restSeconds: Int? = null,
)

/** A note offered for [days] days counts through the last of them. */
fun untilFor(days: Int, today: LocalDate): String = today.plusDays(days - 1L).toString()

/** A note the model offers to save, replace ([previous] is the text it replaces) or, with [forget], drop. */
@Serializable
data class Remember(
    val key: String,
    val text: String,
    val previous: String? = null,
    val forget: Boolean = false,
    val days: Int? = null,
    val done: Boolean = false,
)

@Serializable
data class ChatTurn(
    val fromUser: Boolean,
    val text: String,
    val changes: List<Change> = emptyList(),
    val remember: List<Remember> = emptyList(),
    val at: Long = 0,
)
