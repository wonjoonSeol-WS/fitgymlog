package io.fitgymlog.app.workout

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.fitgymlog.app.container
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.launch

/**
 * An alarm rather than a coroutine delay, which stalls while the phone sleeps in a pocket. Exact when the user allows
 * Alarms & reminders; otherwise Android may hold it back a few minutes in deep sleep, which still beats no buzz at all.
 */
object RestAlarm {
    fun sync(context: Context, endsAt: Instant?) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (endsAt == null) {
            alarmManager.cancel(pendingIntent(context))
            return
        }
        if (alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAt.toEpochMilli(), pendingIntent(context))
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAt.toEpochMilli(), pendingIntent(context))
        }
    }

    fun canBeExact(context: Context): Boolean = context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, RestOverReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

class RestOverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val container = context.container
        container.scope.launch {
            try {
                var rested: ActiveWorkout? = null
                container.workouts.update { current ->
                    if (current.isRestDue(Instant.now().plus(ALARM_SLACK))) current.restOver().also { rested = it } else current
                }
                rested?.let { Notifications.postRestOver(context, bandText(context, it, container.exercises::find, container.settings.settings.value.unit)) }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val ALARM_SLACK: Duration = Duration.ofSeconds(1)
    }
}

/** Granting Alarms & reminders mid-rest makes the rest already running exact, without waiting for the next set. */
class ExactAlarmPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) return
        val pending = goAsync()
        val container = context.container
        // The broadcast can start the app, so the workout is loaded before its rest is read.
        container.scope.launch {
            try {
                container.workouts.awaitLoaded()
                RestAlarm.sync(context, container.workouts.active.value?.restEndsAt)
            } finally {
                pending.finish()
            }
        }
    }
}
