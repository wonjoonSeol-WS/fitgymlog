package io.fitgymlog.app.coach

import android.content.Context
import androidx.core.content.edit
import io.fitgymlog.app.data.enumValueOrDefault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class CoachTask { TARGETS, REVIEW, CHAT }

data class CoachSettings(
    val enabled: Boolean = false,
    val baseUrl: String = "",
    val model: String = "",
    val taskModels: Map<CoachTask, String> = emptyMap(),
    val bodyweightKg: Double? = null,
    val goal: Goal = Goal.BUILD,
    val foodGuide: Boolean = true,
    val shareNotes: Boolean = true,
) {
    fun modelFor(task: CoachTask): String = taskModels[task]?.takeIf { it.isNotBlank() } ?: model

    val isConnected: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()
}

class CoachSettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("coach", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<CoachSettings> = _settings.asStateFlow()

    fun update(transform: (CoachSettings) -> CoachSettings) {
        _settings.update(transform)
        synchronized(prefs) { save(_settings.value) }
    }

    private fun save(next: CoachSettings) {
        prefs.edit {
            putBoolean(ENABLED, next.enabled)
            putString(BASE_URL, next.baseUrl)
            putString(MODEL, next.model)
            CoachTask.entries.forEach { task -> next.taskModels[task]?.let { putString(taskKey(task), it) } ?: remove(taskKey(task)) }
            if (next.bodyweightKg == null) remove(BODYWEIGHT) else putFloat(BODYWEIGHT, next.bodyweightKg.toFloat())
            putString(GOAL, next.goal.name)
            putBoolean(FOOD_GUIDE, next.foodGuide)
            putBoolean(SHARE_NOTES, next.shareNotes)
        }
    }

    private fun load(): CoachSettings {
        val defaults = CoachSettings()
        return CoachSettings(
            enabled = prefs.getBoolean(ENABLED, defaults.enabled),
            baseUrl = prefs.getString(BASE_URL, null).orEmpty(),
            model = prefs.getString(MODEL, null).orEmpty(),
            taskModels = CoachTask.entries.mapNotNull { task -> prefs.getString(taskKey(task), null)?.let { task to it } }.toMap(),
            bodyweightKg = if (prefs.contains(BODYWEIGHT)) prefs.getFloat(BODYWEIGHT, 0f).toDouble() else null,
            goal = enumValueOrDefault(prefs.getString(GOAL, null).orEmpty(), defaults.goal),
            foodGuide = prefs.getBoolean(FOOD_GUIDE, defaults.foodGuide),
            shareNotes = prefs.getBoolean(SHARE_NOTES, defaults.shareNotes),
        )
    }

    private fun taskKey(task: CoachTask) = "model_${task.name.lowercase()}"

    private companion object {
        const val ENABLED = "enabled"
        const val BASE_URL = "base_url"
        const val MODEL = "model"
        const val BODYWEIGHT = "bodyweight_kg"
        const val GOAL = "goal"
        const val FOOD_GUIDE = "food_guide"
        const val SHARE_NOTES = "share_notes"
    }
}
