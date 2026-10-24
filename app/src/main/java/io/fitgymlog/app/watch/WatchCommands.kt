package io.fitgymlog.app.watch

import io.fitgymlog.app.workout.ActiveWorkout
import io.fitgymlog.app.workout.SetRef
import io.fitgymlog.watchlink.WatchCommand
import io.fitgymlog.watchlink.WatchCommand.WorkoutEdit
import io.fitgymlog.watchlink.WatchSetRef
import java.time.Duration
import java.time.Instant

internal fun WatchSetRef.toSetRef() = SetRef(exercise, set)

internal fun SetRef.toWatch() = WatchSetRef(exerciseIndex, setIndex)

/** What a Finish from the watch kept: the saved workout's id, or null when no set was done and nothing was kept. */
internal data class WatchFinish(val workoutId: Long?)

/**
 * The workout a rating from the watch is about: the one its last Finish kept, and none when that finish kept nothing,
 * rather than an earlier workout. Only with no Finish seen, as when the app restarted in between, is it [latest].
 */
internal suspend fun ratedWorkout(finish: WatchFinish?, latest: suspend () -> Long?): Long? = if (finish == null) latest() else finish.workoutId

/**
 * Applied the way the band's buttons are: the watch names the set it was showing, so an edit that crosses a change
 * made on the phone lands on that set or does nothing.
 */
internal fun ActiveWorkout.applyWatch(edit: WorkoutEdit, now: Instant): ActiveWorkout =
    when (edit) {
        is WatchCommand.Log -> log(edit, now)
        is WatchCommand.Focus -> focusExercise(edit.exercise)
        is WatchCommand.AdjustRest -> adjustRest(Duration.ofSeconds(edit.seconds.toLong()), now)
        WatchCommand.SkipRest -> if (restEndsAt == null) this else restOver()
    }

private fun ActiveWorkout.log(command: WatchCommand.Log, now: Instant): ActiveWorkout {
    val ref = command.ref.toSetRef()
    val set = setAt(ref) ?: return this
    var logged = this
    command.weightKg?.takeIf { it != set.weightKg }?.let { logged = logged.setWeightFromHere(ref, it.coerceAtLeast(0.0)) }
    command.reps?.takeIf { it != set.reps }?.let { logged = logged.setReps(ref, it.coerceAtLeast(0)) }
    return if (command.complete) logged.complete(ref, now) else logged
}
