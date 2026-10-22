package io.fitgymlog.app.coach

import kotlinx.serialization.Serializable

enum class MemoryTopic { HEALTH, FOOD, SCHEDULE, GOALS }

@Serializable
data class Memory(val id: String, val topic: MemoryTopic, val text: String, val savedAt: String)

enum class Goal { BUILD, KEEP, LOSE }

/** What the user chose to share with the coach model besides the workouts themselves. */
data class Profile(val bodyweightKg: Double?, val goal: Goal, val memories: List<Memory>, val language: String)

data class Target(
    val exerciseId: String,
    val reason: TargetReason,
    val weightKg: Double?,
    val reps: Int?,
    val lastWeightKg: Double?,
    val note: String?,
)

@Serializable
data class Food(val proteinGrams: Int, val nextMealProteinGrams: Int, val summary: String, val ideas: List<String>)

@Serializable
data class Review(val workoutUuid: String, val notes: String, val food: Food? = null)

@Serializable
data class Change(val exerciseId: String, val weightKg: Double? = null, val reps: Int? = null, val applied: Boolean = false)

@Serializable
data class Remember(val topic: MemoryTopic, val text: String, val saved: Boolean = false)

@Serializable
data class ChatTurn(val fromUser: Boolean, val text: String, val changes: List<Change> = emptyList(), val remember: List<Remember> = emptyList())
