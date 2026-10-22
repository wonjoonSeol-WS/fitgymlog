package io.fitgymlog.app.workout

import android.content.Context
import io.fitgymlog.app.R
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.ui.formatClock
import io.fitgymlog.app.ui.formatWeight
import io.fitgymlog.app.ui.name

/** The two lines the band's music screen shows: which set, and what to lift. */
data class BandText(val title: String, val subtitle: String)

fun bandText(context: Context, active: ActiveWorkout, exerciseOf: (String) -> Exercise, unit: WeightUnit): BandText {
    val view = active.bandView() ?: return BandText(active.workout.title, context.getString(R.string.band_add_exercise))
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

/** During rest the title counts down over the set just done, which is the set "previous" corrects. */
fun BandText.resting(context: Context, rest: RestClock): BandText = copy(title = context.getString(R.string.band_resting, formatClock(rest.left)))

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
        kind == ExerciseKind.DURATION -> context.getString(R.string.weight_times_amount, weightText, amountText)
        else -> context.getString(R.string.weight_times_reps, weightText, reps)
    }
}
