package io.gymlog.app.workout

import android.content.Context
import io.gymlog.app.R
import io.gymlog.app.model.Exercise
import io.gymlog.app.model.ExerciseKind
import io.gymlog.app.model.WeightUnit
import io.gymlog.app.ui.formatWeight
import io.gymlog.app.ui.name

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
        subtitle = setLine(context, exercise.kind, view.weightKg, view.reps, unit),
    )
}

fun setLine(context: Context, kind: ExerciseKind, weightKg: Double?, reps: Int?, unit: WeightUnit): String {
    val repsText = reps?.let { context.resources.getQuantityString(R.plurals.reps, it, it) }
    val weightText = weightKg?.let {
        val formatted = formatWeight(context, it, unit)
        when (kind) {
            ExerciseKind.WEIGHTED_BODYWEIGHT -> "+$formatted"
            ExerciseKind.ASSISTED_BODYWEIGHT -> "-$formatted"
            else -> formatted
        }
    }
    return when {
        weightText != null && reps != null -> context.getString(R.string.weight_times_reps, weightText, reps)
        weightText != null -> weightText
        repsText != null -> repsText
        else -> context.getString(R.string.set_not_recorded)
    }
}
