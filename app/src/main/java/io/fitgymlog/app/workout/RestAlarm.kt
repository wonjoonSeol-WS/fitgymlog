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

/** An exact alarm, because a coroutine delay stalls while the phone sleeps in a pocket. */
object RestAlarm {
    fun sync(context: Context, endsAt: Instant?) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (endsAt == null || !alarmManager.canScheduleExactAlarms()) {
            alarmManager.cancel(pendingIntent(context))
            return
        }
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAt.toEpochMilli(), pendingIntent(context))
    }

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
