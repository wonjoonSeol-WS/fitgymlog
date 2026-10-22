package io.fitgymlog.app.data

import android.content.Context
import androidx.core.content.edit
import androidx.health.connect.client.records.ExerciseSessionRecord
import io.fitgymlog.app.model.WeightUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Settings(
    val unit: WeightUnit = WeightUnit.KG,
    val defaultRestSeconds: Int = 120,
    val healthConnectEnabled: Boolean = false,
    val healthSessionType: Int = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
    val idleFinishMinutes: Int = 30,
    val bandRemoteEnabled: Boolean = true,
    val barKg: Double = 20.0,
    val barLb: Double = 45.0,
    val bandGuideDismissed: Boolean = false,
) {
    /** Bar weight in the unit the user enters weights in. */
    val bar: Double get() = if (unit == WeightUnit.KG) barKg else barLb
}

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_settings.value)
        prefs.edit {
            putString(UNIT, next.unit.name)
            putInt(DEFAULT_REST, next.defaultRestSeconds)
            putBoolean(HEALTH_ENABLED, next.healthConnectEnabled)
            putInt(HEALTH_SESSION_TYPE, next.healthSessionType)
            putInt(IDLE_FINISH, next.idleFinishMinutes)
            putBoolean(BAND_REMOTE, next.bandRemoteEnabled)
            putFloat(BAR_KG, next.barKg.toFloat())
            putFloat(BAR_LB, next.barLb.toFloat())
            putBoolean(BAND_GUIDE_DISMISSED, next.bandGuideDismissed)
        }
        _settings.value = next
    }

    private fun load(): Settings {
        val defaults = Settings()
        return Settings(
            unit = enumValueOrDefault(prefs.getString(UNIT, null).orEmpty(), defaults.unit),
            defaultRestSeconds = prefs.getInt(DEFAULT_REST, defaults.defaultRestSeconds),
            healthConnectEnabled = prefs.getBoolean(HEALTH_ENABLED, defaults.healthConnectEnabled),
            healthSessionType = prefs.getInt(HEALTH_SESSION_TYPE, defaults.healthSessionType),
            idleFinishMinutes = prefs.getInt(IDLE_FINISH, defaults.idleFinishMinutes),
            bandRemoteEnabled = prefs.getBoolean(BAND_REMOTE, defaults.bandRemoteEnabled),
            barKg = prefs.getFloat(BAR_KG, defaults.barKg.toFloat()).toDouble(),
            barLb = prefs.getFloat(BAR_LB, defaults.barLb.toFloat()).toDouble(),
            bandGuideDismissed = prefs.getBoolean(BAND_GUIDE_DISMISSED, defaults.bandGuideDismissed),
        )
    }

    private companion object {
        const val UNIT = "unit"
        const val DEFAULT_REST = "default_rest_seconds"
        const val HEALTH_ENABLED = "health_connect_enabled"
        const val HEALTH_SESSION_TYPE = "health_session_type"
        const val IDLE_FINISH = "idle_finish_minutes"
        const val BAND_REMOTE = "band_remote_enabled"
        const val BAR_KG = "bar_kg"
        const val BAR_LB = "bar_lb"
        const val BAND_GUIDE_DISMISSED = "band_guide_dismissed"
    }
}
