package io.fitgymlog.app.watch

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import io.fitgymlog.app.AppContainer
import io.fitgymlog.app.LOG_TAG
import io.fitgymlog.app.catching
import io.fitgymlog.app.coach.Progression
import io.fitgymlog.app.data.WristDevice
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.model.isTimed
import io.fitgymlog.app.ui.fromDisplayWeight
import io.fitgymlog.app.ui.name
import io.fitgymlog.app.ui.toDisplayWeight
import io.fitgymlog.app.ui.unitLabel
import io.fitgymlog.app.ui.usesWeight
import io.fitgymlog.app.workout.ActiveWorkout
import io.fitgymlog.app.workout.setLine
import io.fitgymlog.watchlink.WatchCodec
import io.fitgymlog.watchlink.WatchCommand
import io.fitgymlog.watchlink.WatchExercise
import io.fitgymlog.watchlink.WatchPaths
import io.fitgymlog.watchlink.WatchRoutine
import io.fitgymlog.watchlink.WatchSetRow
import io.fitgymlog.watchlink.WatchState
import io.fitgymlog.watchlink.WatchUnit
import io.fitgymlog.watchlink.WatchWorkout
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Keeps a Wear OS watch showing the workout in progress, and applies what is tapped there. The phone stays the
 * only place the workout is kept; the watch is a remote, like the band, with more room. Only with a Wear OS watch
 * chosen in settings does anything go to the Data Layer, so a phone with a band or nothing on the wrist pays nothing.
 */
class PhoneWatchLink(private val context: Context, private val container: AppContainer) {
    private val data by lazy { Wearable.getDataClient(context) }

    // Names and set lines are written in the app's language, so a change of language is sent on like any other.
    private val language = MutableStateFlow(currentLanguage())

    fun languageChanged() {
        language.value = currentLanguage()
    }

    private fun currentLanguage(): String = context.resources.configuration.locales[0].toLanguageTag()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        container.scope.launch {
            container.workouts.awaitLoaded()
            var published = false
            container.settings.settings.map { it.wrist == WristDevice.WEAR_OS }.distinctUntilChanged().flatMapLatest { watch ->
                if (watch) watchStates() else flowOf(null)
            }.distinctUntilChanged().collect { state ->
                if (state != null) publish(state) else if (published) clear()
                published = state != null
            }
        }
    }

    /** Routines change rarely, so their summaries are built apart from the workout, which changes with every tap. */
    private fun watchStates() = combine(
        container.workouts.active,
        combine(container.routines.routines, container.exercises.exercises, language) { routines, _, _ ->
            routines.map { routine ->
                WatchRoutine(routine.id, routine.name, routine.exercises.joinToString(", ") { container.exercises.find(it.exerciseId).name(context) })
            }
        },
        container.settings.settings.map { it.unit to it.askEffort }.distinctUntilChanged(),
        language,
    ) { active, routines, (unit, askEffort), _ -> WatchState(active?.let { watchWorkout(it, unit) }, routines, askEffort) }

    suspend fun handle(command: WatchCommand) {
        container.workouts.awaitLoaded()
        when (command) {
            is WatchCommand.StartRoutine -> {
                if (container.workouts.active.value != null) return
                val routine = container.routines.find(command.routineId) ?: return
                // Android refuses a foreground service to an app in the background, but the workout is saved first and runs without it.
                catching { container.actions.startRoutine(routine) }.onFailure { Log.i(LOG_TAG, "Started from the watch without the workout service", it) }
            }
            WatchCommand.Finish -> finishedFromWatch = WatchFinish(container.actions.finish()?.id)
            is WatchCommand.RateEffort -> ratedWorkout(finishedFromWatch) { finishedLately() }?.let { container.actions.rateEffort(it, command.effort) }
            // The watch app has just opened: the same state again, freshly stamped, gives it a reading of the phone's clock.
            WatchCommand.Hello -> lastPublished?.let { publish(it) }
            is WatchCommand.WorkoutEdit -> container.workouts.update { it.applyWatch(command, Instant.now()) }
        }
    }

    /** What the last Finish from the watch kept, which a rating from the watch is about; null until one comes in. */
    @Volatile
    private var finishedFromWatch: WatchFinish? = null

    /** The latest workout finished in the last two hours, for a rating whose Finish came before the app restarted. */
    private suspend fun finishedLately(): Long? = container.workouts.history.first()
        .filter { it.endedAt?.isAfter(Instant.now().minus(Duration.ofHours(2))) == true }
        .maxByOrNull { it.endedAt!! }?.id

    /** What the watch was last sent, unstamped; null outside Wear OS mode. */
    @Volatile
    private var lastPublished: WatchState? = null

    private suspend fun publish(state: WatchState) {
        lastPublished = state
        // Stamped here, after the change check, so only a real change is sent but each one carries the time it left.
        val request = PutDataRequest.create(WatchPaths.STATE).setData(WatchCodec.encode(state.copy(sentAtMillis = System.currentTimeMillis()))).setUrgent()
        catching { data.putDataItem(request).await() }.onFailure { Log.d(LOG_TAG, "No watch to update", it) }
    }

    /** Leaving Wear OS mode takes the workout off the watch, rather than leave it showing one that no longer moves. */
    private suspend fun clear() {
        lastPublished = null
        val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(WatchPaths.STATE).build()
        catching { data.deleteDataItems(uri).await() }.onFailure { Log.d(LOG_TAG, "No watch to clear", it) }
    }

    private fun watchWorkout(active: ActiveWorkout, unit: WeightUnit): WatchWorkout = WatchWorkout(
        title = active.workout.title,
        startedAtMillis = active.workout.startedAt.toEpochMilli(),
        exercises = active.exercises.map { block ->
            val exercise = container.exercises.find(block.exerciseId)
            WatchExercise(
                name = exercise.name(context),
                hasWeight = exercise.kind.usesWeight(),
                hasReps = !exercise.kind.isTimed,
                weightStep = toDisplayWeight(Progression.stepKg(exercise, unit), unit),
                sets = block.sets.map { set ->
                    val reps = set.reps ?: set.targetReps
                    WatchSetRow(
                        line = setLine(context, exercise, set.weightKg, reps, unit, set.durationSeconds, set.level, set.speedKmh, set.distanceMeters),
                        weightKg = set.weightKg,
                        reps = reps,
                        done = set.isCompleted,
                        warmup = set.type == SetType.WARMUP,
                    )
                },
            )
        },
        unit = WatchUnit(unitLabel(context, unit), fromDisplayWeight(1.0, unit)),
        focus = active.focus,
        next = active.nextSetToComplete()?.toWatch(),
        restEndsAtMillis = active.restEndsAt?.toEpochMilli(),
        restLengthMillis = active.restClock(Instant.now())?.length?.toMillis(),
    )
}
