package io.fitgymlog.app.workout

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
import io.fitgymlog.app.R
import io.fitgymlog.app.container
import io.fitgymlog.app.model.WeightUnit
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    private var bandNextSet: SetRef? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
        session = MediaSession(this, TAG).apply { setCallback(BandCallback(), Handler(Looper.getMainLooper())) }
        val container = container
        scope.launch {
            container.workouts.awaitLoaded()
            launch {
                container.workouts.active.map { it?.restEndsAt }.distinctUntilChanged().collect { RestAlarm.sync(this@WorkoutService, it) }
            }
            val shown = container.settings.settings.map { ShownSettings(it.unit, it.bandRemoteEnabled) }.distinctUntilChanged()
            combine(container.workouts.active, shown, ::Pair).collectLatest { (active, settings) ->
                render(active, settings)
                if (active != null && settings.bandRemoteEnabled) countDownRest(active, settings.unit)
            }
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

    private fun render(active: ActiveWorkout?, settings: ShownSettings) {
        if (active == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        bandNextSet = active.focusedNextSet()
        session.isActive = settings.bandRemoteEnabled
        if (settings.bandRemoteEnabled) publishToBand(active, settings.unit, Instant.now())
        val notification = workoutNotification(active, bandText(this, active, container.exercises::find, settings.unit), settings.bandRemoteEnabled)
        lastNotification = notification
        getSystemService(NotificationManager::class.java).notify(Notifications.ID_WORKOUT, notification)
    }

    // A main-thread delay does not run while the phone sleeps, so the countdown never wakes it.
    private suspend fun countDownRest(active: ActiveWorkout, unit: WeightUnit) {
        val endsAt = active.restEndsAt ?: return
        while (Instant.now().isBefore(endsAt)) {
            delay(REST_REFRESH_MILLIS)
            publishToBand(active, unit, Instant.now())
        }
    }

    private fun publishToBand(active: ActiveWorkout, unit: WeightUnit, now: Instant) {
        val screen = bandScreen(active, now)
        val text = bandScreenText(this, screen, active.workout.title, container.exercises::find, unit)
        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, text.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, text.subtitle)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, active.workout.title)
        screen.rest?.let { metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, it.length.toMillis()) }
        session.setMetadata(metadata.build())
        session.setPlaybackState(playing(screen.rest?.elapsed?.toMillis() ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN))
    }

    private fun workoutNotification(active: ActiveWorkout, text: BandText, withSession: Boolean): Notification {
        val resting = active.restEndsAt
        val builder = Notification.Builder(this, Notifications.CHANNEL_WORKOUT)
            .setSmallIcon(R.drawable.ic_fitgymlog)
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
        .setSmallIcon(R.drawable.ic_fitgymlog)
        .setContentTitle(getString(R.string.app_name))
        .setContentIntent(Notifications.openApp(this))
        .build()

    /** Ends a forgotten workout once nothing has been ticked for a while; one with nothing lifted is dropped. */
    private suspend fun finishWhenIdle() {
        val container = container
        val app = applicationContext
        while (true) {
            delay(IDLE_CHECK_MILLIS)
            val active = container.workouts.active.value ?: continue
            val limit = Duration.ofMinutes(container.settings.settings.value.idleFinishMinutes.toLong())
            if (!active.isIdle(Instant.now(), limit)) continue
            // Finishing stops this service and cancels its scope, so the save and Health Connect write run in the app scope.
            container.scope.launch {
                container.actions.finish(endedAt = active.lastActivityAt)?.let { Notifications.postAutoFinished(app, it.title) }
            }
        }
    }

    private inner class BandCallback : MediaSession.Callback() {
        override fun onPlay() = completeShownSet()

        override fun onPause() = completeShownSet()

        override fun onSkipToNext() = change { it.focusNextExercise() }

        override fun onSkipToPrevious() = change { it.lowerShownReps() }

        private fun completeShownSet() {
            val expected = bandNextSet
            change { it.completeFocusedSet(Instant.now(), expected) }
        }

        private fun change(transform: (ActiveWorkout) -> ActiveWorkout) {
            scope.launch { container.workouts.update(transform) }
        }
    }

    private data class ShownSettings(val unit: WeightUnit, val bandRemoteEnabled: Boolean)

    companion object {
        private const val TAG = "FitGymLog"
        private const val IDLE_CHECK_MILLIS = 60_000L
        private const val REST_REFRESH_MILLIS = 5_000L
        private const val BAND_ACTIONS = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS

        private fun playing(positionMillis: Long): PlaybackState = PlaybackState.Builder()
            .setActions(BAND_ACTIONS)
            .setState(PlaybackState.STATE_PLAYING, positionMillis, 1f)
            .build()

        fun start(context: Context) {
            context.startForegroundService(Intent(context, WorkoutService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WorkoutService::class.java))
        }
    }
}
