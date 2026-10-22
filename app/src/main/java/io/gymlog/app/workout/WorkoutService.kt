package io.gymlog.app.workout

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import io.gymlog.app.R
import io.gymlog.app.container
import io.gymlog.app.data.Settings
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs for the length of a workout. It keeps the workout notification up, schedules the rest alarm,
 * and publishes the current set as a playing media session so a band's music controls drive it:
 * play/pause completes the set, next moves to the next exercise, previous takes one rep off.
 */
class WorkoutService : Service() {
    private val scope = MainScope()
    private lateinit var session: MediaSession
    private var lastNotification: Notification? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        session = MediaSession(this, TAG).apply { setCallback(BandCallback(), Handler(Looper.getMainLooper())) }
        val container = container
        scope.launch {
            container.workouts.awaitLoaded()
            combine(container.workouts.active, container.settings.settings) { active, settings -> active to settings }
                .collect { (active, settings) -> render(active, settings) }
        }
        scope.launch { finishWhenIdle() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(Notifications.ID_WORKOUT, lastNotification ?: placeholder(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        session.release()
        super.onDestroy()
    }

    private fun render(active: ActiveWorkout?, settings: Settings) {
        if (active == null) {
            RestAlarm.sync(this, null)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val text = bandText(this, active, container.exercises::find, settings.unit)
        session.isActive = settings.bandRemoteEnabled
        if (settings.bandRemoteEnabled) {
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, text.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, text.subtitle)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, active.workout.title)
                    .build(),
            )
            session.setPlaybackState(PLAYING)
        }
        RestAlarm.sync(this, active.restEndsAt)
        val notification = workoutNotification(active, text, settings.bandRemoteEnabled)
        lastNotification = notification
        getSystemService(NotificationManager::class.java).notify(Notifications.ID_WORKOUT, notification)
    }

    private fun workoutNotification(active: ActiveWorkout, text: BandText, withSession: Boolean): Notification {
        val resting = active.restEndsAt
        val builder = Notification.Builder(this, Notifications.CHANNEL_WORKOUT)
            .setSmallIcon(R.drawable.ic_gymlog)
            .setContentTitle(text.title)
            .setContentText(text.subtitle)
            .setSubText(active.workout.title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(Notifications.openApp(this))
            .setUsesChronometer(true)
            .setChronometerCountDown(resting != null)
            .setWhen((resting ?: active.workout.startedAt).toEpochMilli())
        if (withSession) builder.setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken))
        return builder.build()
    }

    private fun placeholder(): Notification = Notification.Builder(this, Notifications.CHANNEL_WORKOUT)
        .setSmallIcon(R.drawable.ic_gymlog)
        .setContentTitle(getString(R.string.app_name))
        .setContentIntent(Notifications.openApp(this))
        .build()

    /** Saves a forgotten workout once nothing has been ticked for a while, ending it at the last completed set. */
    private suspend fun finishWhenIdle() {
        val container = container
        while (scope.isActive) {
            delay(IDLE_CHECK_MILLIS)
            val active = container.workouts.active.value ?: continue
            val limit = Duration.ofMinutes(container.settings.settings.value.idleFinishMinutes.toLong())
            val hasLifted = active.exercises.any { exercise -> exercise.sets.any { it.isCompleted } }
            if (hasLifted && active.isIdle(Instant.now(), limit)) {
                container.actions.finish(endedAt = active.lastActivityAt)
                Notifications.postAutoFinished(this, active.workout.title)
            }
        }
    }

    private inner class BandCallback : MediaSession.Callback() {
        override fun onPlay() = change { it.completeFocusedSet(Instant.now()) }

        override fun onPause() = change { it.completeFocusedSet(Instant.now()) }

        override fun onSkipToNext() = change { it.focusNextExercise() }

        override fun onSkipToPrevious() = change { it.lowerShownReps() }

        private fun change(transform: (ActiveWorkout) -> ActiveWorkout) {
            scope.launch { container.workouts.update(transform) }
        }
    }

    companion object {
        private const val TAG = "GymLog"
        private const val IDLE_CHECK_MILLIS = 60_000L

        private val PLAYING = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS,
            )
            .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
            .build()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WorkoutService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WorkoutService::class.java))
        }
    }
}
