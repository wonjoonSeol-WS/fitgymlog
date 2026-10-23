package io.fitgymlog.app.checkin

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import io.fitgymlog.app.R
import io.fitgymlog.app.container
import io.fitgymlog.app.workout.Notifications
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The evening of the week's last day, as the locale counts weeks, so the week is all but over. */
internal fun nextCheckIn(now: ZonedDateTime, firstDay: DayOfWeek): ZonedDateTime {
    val thisWeek = now.toLocalDate().with(TemporalAdjusters.nextOrSame(firstDay.minus(1))).atTime(CHECK_IN_TIME).atZone(now.zone)
    return if (thisWeek.isAfter(now)) thisWeek else thisWeek.plusWeeks(1)
}

internal fun firstDayOf(context: Context): DayOfWeek = WeekFields.of(context.resources.configuration.locales[0]).firstDayOfWeek

/** Inexact, since a weekly reminder may come a little late; reset whenever the app starts, as a reboot clears it. */
object CheckInAlarm {
    fun sync(context: Context, enabled: Boolean) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        if (!enabled) {
            alarmManager.cancel(pendingIntent(context))
            return
        }
        val at = nextCheckIn(ZonedDateTime.now(), firstDayOf(context)).toInstant().toEpochMilli()
        alarmManager.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW.toMillis(), pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, CheckInReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private val WINDOW: Duration = Duration.ofMinutes(30)
}

class CheckInReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val container = context.container
        container.scope.launch {
            try {
                if (!container.settings.settings.value.weeklyCheckIn) return@launch
                val summary = weekSummary(
                    history = container.workouts.history.first(),
                    body = emptyList(),
                    today = LocalDate.now(),
                    firstDay = firstDayOf(context),
                    zone = ZoneId.systemDefault(),
                    kindOf = { container.exercises.find(it).kind },
                )
                Notifications.postCheckIn(context, weekLine(context.resources, summary))
                CheckInAlarm.sync(context, enabled = true)
            } finally {
                pending.finish()
            }
        }
    }
}

internal fun weekLine(resources: Resources, summary: WeekSummary): String {
    val workouts = summary.workouts.first
    if (workouts == 0) return resources.getString(R.string.check_in_text_none)
    val done = resources.getQuantityString(R.plurals.check_in_workouts, workouts, workouts)
    val records = summary.records.size
    return if (records == 0) done else resources.getString(R.string.check_in_text_records, done, resources.getQuantityString(R.plurals.check_in_records, records, records))
}

private val CHECK_IN_TIME: LocalTime = LocalTime.of(19, 0)
