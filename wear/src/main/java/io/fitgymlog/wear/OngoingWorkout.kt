package io.fitgymlog.wear

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import io.fitgymlog.watchlink.WatchWorkout

/**
 * While the phone has a workout open, an ongoing activity puts FitGymLog's icon on the watch face and at the top of
 * the app list, one tap from the set in progress. The rest buzz is the phone's own notification, which the watch
 * already shows, so nothing here makes a sound.
 */
object OngoingWorkout {
    private const val CHANNEL = "workout"
    private const val ID = 1

    /** What the notification shows, so a phone update that changes neither line leaves it alone. */
    private var shown: Pair<String, String>? = null

    fun sync(context: Context, workout: WatchWorkout?) {
        val manager = NotificationManagerCompat.from(context)
        if (workout == null) {
            manager.cancel(ID)
            shown = null
            return
        }
        val text = workout.next?.let { ref -> workout.exerciseAt(ref)?.let { "${it.name} ${ref.set + 1}/${it.sets.size}" } } ?: workout.title
        if (shown == workout.title to text) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_workout), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, WatchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_fitgymlog)
            .setContentTitle(workout.title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
        OngoingActivity.Builder(context, ID, builder)
            .setStaticIcon(R.drawable.ic_fitgymlog)
            .setTouchIntent(open)
            .setStatus(
                Status.Builder()
                    .addTemplate(context.getString(R.string.ongoing_status))
                    .addPart("set", Status.TextPart(text))
                    .build(),
            )
            .build()
            .apply(context)
        manager.notify(ID, builder.build())
        shown = workout.title to text
    }
}
