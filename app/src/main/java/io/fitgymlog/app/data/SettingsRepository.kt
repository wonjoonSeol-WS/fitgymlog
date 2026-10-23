package io.fitgymlog.app.data

import android.content.Context
import androidx.core.content.edit
import androidx.health.connect.client.records.ExerciseSessionRecord
import io.fitgymlog.app.model.WeightUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What is on the wrist. A Galaxy Fit can't run apps, so it is driven through its music controls; a Wear OS watch runs
 * FitGymLog's own watch app.
 */
enum class WristDevice { FIT_BAND, WEAR_OS, OFF }

data class Settings(
    val unit: WeightUnit = WeightUnit.KG,
    val defaultRestSeconds: Int = 120,
    val healthConnectEnabled: Boolean = false,
    val healthSessionType: Int = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
    val skipRecordedCardio: Boolean = true,
    val readBodyFromHealth: Boolean = true,
    val warmupSet: Boolean = false,
    val weeklyCheckIn: Boolean = true,
    val idleFinishMinutes: Int = 30,
    val wrist: WristDevice = WristDevice.FIT_BAND,
    val barKg: Double = 20.0,
    val barLb: Double = 45.0,
    val bandGuideDismissed: Boolean = false,
    val playVideosInApp: Boolean = true,
    val askEffort: Boolean = true,
    val logRpe: Boolean = false,
    val syncEnabled: Boolean = false,
    val syncAccount: String? = null,
    val lastSyncAt: Long? = null,
) {
    /** The band remote is a media session, which a Wear OS watch would also show, over its own app. */
    val bandRemoteEnabled: Boolean get() = wrist == WristDevice.FIT_BAND

    /** Bar weight in the unit the user enters weights in. */
    val bar: Double get() = if (unit == WeightUnit.KG) barKg else barLb
}

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    fun update(transform: (Settings) -> Settings) {
        _settings.update(transform)
        synchronized(prefs) { save(_settings.value) }
    }

    private fun save(next: Settings) {
        prefs.edit {
            putString(UNIT, next.unit.name)
            putInt(DEFAULT_REST, next.defaultRestSeconds)
            putBoolean(HEALTH_ENABLED, next.healthConnectEnabled)
            putInt(HEALTH_SESSION_TYPE, next.healthSessionType)
            putBoolean(SKIP_RECORDED_CARDIO, next.skipRecordedCardio)
            putBoolean(READ_BODY_FROM_HEALTH, next.readBodyFromHealth)
            putBoolean(WARMUP_SET, next.warmupSet)
            putBoolean(WEEKLY_CHECK_IN, next.weeklyCheckIn)
            putInt(IDLE_FINISH, next.idleFinishMinutes)
            putString(WRIST, next.wrist.name)
            putFloat(BAR_KG, next.barKg.toFloat())
            putFloat(BAR_LB, next.barLb.toFloat())
            putBoolean(BAND_GUIDE_DISMISSED, next.bandGuideDismissed)
            putBoolean(PLAY_VIDEOS_IN_APP, next.playVideosInApp)
            putBoolean(ASK_EFFORT, next.askEffort)
            putBoolean(LOG_RPE, next.logRpe)
            putBoolean(SYNC_ENABLED, next.syncEnabled)
            putString(SYNC_ACCOUNT, next.syncAccount)
            if (next.lastSyncAt == null) remove(LAST_SYNC_AT) else putLong(LAST_SYNC_AT, next.lastSyncAt)
        }
    }

    private fun load(): Settings {
        val defaults = Settings()
        return Settings(
            unit = enumValueOrDefault(prefs.getString(UNIT, null).orEmpty(), defaults.unit),
            defaultRestSeconds = prefs.getInt(DEFAULT_REST, defaults.defaultRestSeconds),
            healthConnectEnabled = prefs.getBoolean(HEALTH_ENABLED, defaults.healthConnectEnabled),
            healthSessionType = prefs.getInt(HEALTH_SESSION_TYPE, defaults.healthSessionType),
            skipRecordedCardio = prefs.getBoolean(SKIP_RECORDED_CARDIO, defaults.skipRecordedCardio),
            readBodyFromHealth = prefs.getBoolean(READ_BODY_FROM_HEALTH, defaults.readBodyFromHealth),
            warmupSet = prefs.getBoolean(WARMUP_SET, defaults.warmupSet),
            weeklyCheckIn = prefs.getBoolean(WEEKLY_CHECK_IN, defaults.weeklyCheckIn),
            idleFinishMinutes = prefs.getInt(IDLE_FINISH, defaults.idleFinishMinutes),
            // Before the watch app there was only the band switch, so an install that had it off had nothing on the wrist.
            wrist = prefs.getString(WRIST, null)?.let { enumValueOrDefault(it, defaults.wrist) }
                ?: if (prefs.getBoolean(BAND_REMOTE, true)) WristDevice.FIT_BAND else WristDevice.OFF,
            barKg = prefs.getFloat(BAR_KG, defaults.barKg.toFloat()).toDouble(),
            barLb = prefs.getFloat(BAR_LB, defaults.barLb.toFloat()).toDouble(),
            bandGuideDismissed = prefs.getBoolean(BAND_GUIDE_DISMISSED, defaults.bandGuideDismissed),
            playVideosInApp = prefs.getBoolean(PLAY_VIDEOS_IN_APP, defaults.playVideosInApp),
            askEffort = prefs.getBoolean(ASK_EFFORT, defaults.askEffort),
            logRpe = prefs.getBoolean(LOG_RPE, defaults.logRpe),
            syncEnabled = prefs.getBoolean(SYNC_ENABLED, defaults.syncEnabled),
            syncAccount = prefs.getString(SYNC_ACCOUNT, null),
            lastSyncAt = if (prefs.contains(LAST_SYNC_AT)) prefs.getLong(LAST_SYNC_AT, 0) else null,
        )
    }

    private companion object {
        const val UNIT = "unit"
        const val DEFAULT_REST = "default_rest_seconds"
        const val HEALTH_ENABLED = "health_connect_enabled"
        const val HEALTH_SESSION_TYPE = "health_session_type"
        const val SKIP_RECORDED_CARDIO = "skip_recorded_cardio"
        const val READ_BODY_FROM_HEALTH = "read_body_from_health"
        const val WARMUP_SET = "warmup_set"
        const val WEEKLY_CHECK_IN = "weekly_check_in"
        const val IDLE_FINISH = "idle_finish_minutes"
        const val BAND_REMOTE = "band_remote_enabled"
        const val WRIST = "wrist_device"
        const val BAR_KG = "bar_kg"
        const val BAR_LB = "bar_lb"
        const val BAND_GUIDE_DISMISSED = "band_guide_dismissed"
        const val PLAY_VIDEOS_IN_APP = "play_videos_in_app"
        const val ASK_EFFORT = "ask_effort"
        const val LOG_RPE = "log_rpe"
        const val SYNC_ENABLED = "sync_enabled"
        const val SYNC_ACCOUNT = "sync_account"
        const val LAST_SYNC_AT = "last_sync_at"
    }
}
