package io.fitgymlog.app.ui.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.fitgymlog.app.R
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.ui.NumberStyles
import io.fitgymlog.app.ui.exercise.FormGuide
import io.fitgymlog.app.ui.lift
import io.fitgymlog.app.workout.ActiveWorkout
import io.fitgymlog.app.workout.SetRef
import io.fitgymlog.app.workout.bandText

@Composable
internal fun FocusPanel(
    workout: ActiveWorkout,
    exerciseOf: (String) -> Exercise,
    unit: WeightUnit,
    bandRemote: Boolean,
    onComplete: (expected: SetRef) -> Unit,
    onNextExercise: () -> Unit,
    onAdjustRest: (Long) -> Unit,
    onSkipRest: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    Column(
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        val view = workout.bandView()
        if (view == null) {
            Text(stringResource(R.string.band_add_exercise), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val exercise = exerciseOf(view.exerciseId)
        val text = bandText(context, workout, exerciseOf, unit)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            when {
                view.isExerciseDone -> if (bandRemote) BodySubtitle(text.subtitle)
                view.weightKg == null && view.reps == null -> BodySubtitle(text.subtitle)
                else -> Text(text.subtitle, style = NumberStyles.timer)
            }
        }
        val endsAt = workout.restEndsAt
        when {
            endsAt != null -> RestBar(
                endsAt = endsAt,
                totalSeconds = workout.exercises.getOrNull(workout.focus)?.restSeconds ?: 0,
                onAdjust = onAdjustRest,
                onSkip = onSkipRest,
            )
            !view.isExerciseDone -> CompleteButton { workout.focusedOpenSet()?.let(onComplete) }
            workout.exercises.size > 1 -> NextExerciseButton(onNextExercise)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(stringResource(R.string.form_guide), style = MaterialTheme.typography.titleMedium)
        key(exercise.id) { FormGuide(exercise) }
    }
}

@Composable
private fun BodySubtitle(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CompleteButton(onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Button(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.lift.doneCheck, contentColor = MaterialTheme.lift.onDoneCheck),
        modifier = Modifier.fillMaxWidth().height(64.dp),
    ) {
        Icon(Icons.Default.Check, null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.complete_set), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun NextExerciseButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.lift.field, contentColor = MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().height(64.dp),
    ) {
        Text(stringResource(R.string.next_exercise), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
    }
}
