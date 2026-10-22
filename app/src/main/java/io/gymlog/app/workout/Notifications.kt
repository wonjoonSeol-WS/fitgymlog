package io.gymlog.app.workout

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.gymlog.app.MainActivity
import io.gymlog.app.R

object Notifications {
    const val ID_WORKOUT = 1
    private const val ID_REST_OVER = 2
    private const val ID_AUTO_FINISHED = 3
    const val CHANNEL_WORKOUT = "workout"
    private const val CHANNEL_REST_OVER = "rest_over"
    private const val CHANNEL_AUTO_FINISHED = "auto_finished"
    private val REST_OVER_VIBRATION = longArrayOf(0, 400, 200, 400)

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_WORKOUT, context.getString(R.string.channel_workout), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_REST_OVER, context.getString(R.string.channel_rest_over), NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    vibrationPattern = REST_OVER_VIBRATION
                },
                NotificationChannel(CHANNEL_AUTO_FINISHED, context.getString(R.string.channel_auto_finished), NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }

    fun postRestOver(context: Context, nextSet: BandText) {
        ensureChannels(context)
        val notification = Notification.Builder(context, CHANNEL_REST_OVER)
            .setSmallIcon(R.drawable.ic_gymlog)
            .setContentTitle(context.getString(R.string.rest_over_title))
            .setContentText("${nextSet.title}, ${nextSet.subtitle}")
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setTimeoutAfter(REST_OVER_TIMEOUT_MILLIS)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_REST_OVER, notification)
    }

    fun postAutoFinished(context: Context, title: String) {
        ensureChannels(context)
        val notification = Notification.Builder(context, CHANNEL_AUTO_FINISHED)
            .setSmallIcon(R.drawable.ic_gymlog)
            .setContentTitle(context.getString(R.string.auto_finished_title))
            .setContentText(context.getString(R.string.auto_finished_text, title))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_AUTO_FINISHED, notification)
    }

    fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private const val REST_OVER_TIMEOUT_MILLIS = 60_000L
}
