package io.fitgymlog.app.workout

import android.content.Context
import io.fitgymlog.app.R
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.ui.formatClock
import io.fitgymlog.app.ui.formatWeight
import io.fitgymlog.app.ui.name
import java.time.Duration
import java.time.Instant

/** The two lines the band's music screen shows: which set, and what to lift. */
data class BandText(val title: String, val subtitle: String)

data class RestClock(val left: Duration, val length: Duration) {
    val elapsed: Duration get() = (length - left).coerceAtLeast(Duration.ZERO)
}

/** During rest the band counts down and shows the set that comes after the rest. */
data class BandScreen(val view: BandView?, val rest: RestClock?)

fun bandScreen(active: ActiveWorkout, now: Instant): BandScreen {
    val endsAt = active.restEndsAt ?: return BandScreen(active.bandView(), rest = null)
    val startedAt = active.restStartedAt() ?: endsAt
    val rest = RestClock(
        left = Duration.between(now, endsAt).coerceAtLeast(Duration.ZERO),
        length = Duration.between(startedAt, endsAt).coerceAtLeast(Duration.ZERO),
    )
    return BandScreen(active.restOver().bandView(), rest)
}

fun bandText(context: Context, active: ActiveWorkout, exerciseOf: (String) -> Exercise, unit: WeightUnit): BandText =
    viewText(context, active.workout.title, active.bandView(), exerciseOf, unit)

fun bandScreenText(context: Context, screen: BandScreen, workoutTitle: String, exerciseOf: (String) -> Exercise, unit: WeightUnit): BandText {
    val text = viewText(context, workoutTitle, screen.view, exerciseOf, unit)
    val rest = screen.rest ?: return text
    val upcoming = if (screen.view?.isExerciseDone == true) text.title else text.subtitle
    return BandText(context.getString(R.string.band_resting, formatClock(rest.left)), upcoming)
}

private fun viewText(context: Context, workoutTitle: String, view: BandView?, exerciseOf: (String) -> Exercise, unit: WeightUnit): BandText {
    if (view == null) return BandText(workoutTitle, context.getString(R.string.band_add_exercise))
    val exercise = exerciseOf(view.exerciseId)
    val name = exercise.name(context)
    if (view.isExerciseDone) {
        return BandText(context.getString(R.string.band_exercise_done, name), context.getString(R.string.band_next_hint))
    }
    val titleRes = if (view.isCompleted) R.string.band_set_done else R.string.band_set
    return BandText(
        title = context.getString(titleRes, name, view.setNumber, view.setCount),
        subtitle = setLine(context, exercise.kind, view.weightKg, view.reps, unit, view.durationSeconds),
    )
}

fun setLine(context: Context, kind: ExerciseKind, weightKg: Double?, reps: Int?, unit: WeightUnit, durationSeconds: Int? = null): String {
    val weightText = weightKg?.let {
        val formatted = formatWeight(context, it, unit)
        when (kind) {
            ExerciseKind.WEIGHTED_BODYWEIGHT -> "+$formatted"
            ExerciseKind.ASSISTED_BODYWEIGHT -> "-$formatted"
            else -> formatted
        }
    }
    val amountText = if (kind == ExerciseKind.DURATION) {
        durationSeconds?.let { context.getString(R.string.duration_seconds, it) }
    } else {
        reps?.let { context.resources.getQuantityString(R.plurals.reps, it, it) }
    }
    return when {
        weightText == null -> amountText ?: context.getString(R.string.set_not_recorded)
        amountText == null -> weightText
        kind == ExerciseKind.DURATION -> context.getString(R.string.weight_times_duration, weightText, amountText)
        else -> context.getString(R.string.weight_times_reps, weightText, reps)
    }
}
