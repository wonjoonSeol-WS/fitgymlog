package io.gymlog.app.data

import android.content.Context
import androidx.core.content.edit
import androidx.health.connect.client.records.ExerciseSessionRecord
import io.gymlog.app.model.WeightUnit
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
)

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
        )
    }

    private companion object {
        const val UNIT = "unit"
        const val DEFAULT_REST = "default_rest_seconds"
        const val HEALTH_ENABLED = "health_connect_enabled"
        const val HEALTH_SESSION_TYPE = "health_session_type"
        const val IDLE_FINISH = "idle_finish_minutes"
        const val BAND_REMOTE = "band_remote_enabled"
    }
}
