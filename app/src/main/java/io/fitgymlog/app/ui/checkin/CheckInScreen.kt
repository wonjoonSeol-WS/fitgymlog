package io.fitgymlog.app.ui.checkin

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.fitgymlog.app.R
import io.fitgymlog.app.checkin.WeekSummary
import io.fitgymlog.app.checkin.weekSummary
import io.fitgymlog.app.coach.BodyMeasurement
import io.fitgymlog.app.coach.Coach
import io.fitgymlog.app.coach.Goal
import io.fitgymlog.app.coach.Pending
import io.fitgymlog.app.coach.WeekReview
import io.fitgymlog.app.coach.accepts
import io.fitgymlog.app.container
import io.fitgymlog.app.data.byId
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.Routine
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.ui.NumberStyles
import io.fitgymlog.app.ui.ReadableWidth
import io.fitgymlog.app.ui.coach.ChangeChecklist
import io.fitgymlog.app.ui.coach.ChangeRow
import io.fitgymlog.app.ui.coach.FailureText
import io.fitgymlog.app.ui.coach.changeTitle
import io.fitgymlog.app.ui.coach.planText
import io.fitgymlog.app.ui.exercise.metricName
import io.fitgymlog.app.ui.formatWeightNumber
import io.fitgymlog.app.ui.localizedName
import io.fitgymlog.app.ui.oneDecimal
import io.fitgymlog.app.ui.toDisplayWeight
import io.fitgymlog.app.ui.unitLabel
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import kotlin.math.abs
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckInScreen(onBack: () -> Unit) {
    val app = LocalContext.current.container
    val coach = app.coach
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val coachSettings by coach.settings.settings.collectAsStateWithLifecycle()
    val data by coach.store.data.collectAsStateWithLifecycle()
    val ready by coach.ready.collectAsStateWithLifecycle()
    val pending by coach.weekPending.collectAsStateWithLifecycle()
    val history by app.workouts.history.collectAsStateWithLifecycle<List<Workout>?>(null)
    val exercises by app.exercises.exercises.collectAsStateWithLifecycle()
    val routines by app.routines.routines.collectAsStateWithLifecycle(emptyList())
    val body by produceState<List<BodyMeasurement>?>(null, data.measurements) { value = coach.bodyHistory() }
    val locale = LocalConfiguration.current.locales[0]
    val today = LocalDate.now()
    val summary = remember(history, body, exercises, locale, today) {
        val workouts = history ?: return@remember null
        weekSummary(workouts, body.orEmpty(), today, WeekFields.of(locale).firstDayOfWeek, ZoneId.systemDefault()) { exercises.byId(it).kind }
    }
    val unit = settings.unit

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(stringResource(R.string.check_in)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        val week = summary ?: return@Scaffold
        ReadableWidth(Modifier.padding(padding)) { gutter ->
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp + gutter, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item(key = "range") { Text(rangeText(week), style = MaterialTheme.typography.titleLarge) }
                item(key = "training") { Training(week, unit) }
                item(key = "records") { Records(week, exercises) }
                item(key = "missed") { Missed(week, exercises) }
                item(key = "weight") { Weight(week, unit, coachSettings.goal.takeIf { coachSettings.enabled }) }
                if (coachSettings.enabled) {
                    item(key = "coach") {
                        NextWeek(
                            review = data.weeks.find { it.weekStart == week.start.toString() },
                            routines = routines,
                            exercises = exercises,
                            ready = ready,
                            pending = pending,
                            onAsk = { coach.lookBackOn(week) },
                            onApply = { weekStart, indices -> app.scope.launch { coach.applyWeekChanges(weekStart, indices) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun Training(week: WeekSummary, unit: WeightUnit) {
    val resources = LocalResources.current
    val unitText = unitLabel(unit)
    val hours = { minutes: Long -> resources.getString(R.string.hours_value, oneDecimal(minutes / 60.0)) }
    val volume = { kg: Double -> "${formatWeightNumber(kg, unit)} $unitText" }
    Section(stringResource(R.string.check_in_training)) {
        TotalRow(stringResource(R.string.week_workouts), week.workouts.first.toString(), week.workouts.second.toString())
        TotalRow(stringResource(R.string.week_time), hours(week.minutes.first), hours(week.minutes.second))
        TotalRow(stringResource(R.string.volume), volume(week.volumeKg.first), volume(week.volumeKg.second))
    }
}

@Composable
private fun TotalRow(label: String, now: String, lastWeek: String) {
    Row {
        Text(label, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(now, style = NumberStyles.cell)
            Text(stringResource(R.string.check_in_last_week, lastWeek), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Records(week: WeekSummary, exercises: List<Exercise>) {
    Section(stringResource(R.string.check_in_records)) {
        if (week.records.isEmpty()) Muted(stringResource(R.string.check_in_no_records))
        week.records.forEach { record ->
            val metrics = record.metrics.map { stringResource(metricName(it)) }.joinToString(", ")
            Text(stringResource(R.string.check_in_change_in, exercises.byId(record.exerciseId).localizedName(), metrics))
        }
    }
}

@Composable
private fun Missed(week: WeekSummary, exercises: List<Exercise>) {
    Section(stringResource(R.string.check_in_missed)) {
        if (week.missed.isEmpty()) Muted(stringResource(R.string.check_in_no_missed))
        week.missed.forEach { missed ->
            val short = pluralStringResource(R.plurals.check_in_missed_value, missed.sets, missed.missed, missed.sets)
            Text(stringResource(R.string.check_in_change_in, exercises.byId(missed.exerciseId).localizedName(), short))
        }
    }
}

@Composable
private fun Weight(week: WeekSummary, unit: WeightUnit, goal: Goal?) {
    val unitText = unitLabel(unit)
    val mass = { kg: Double -> "${oneDecimal(toDisplayWeight(kg, unit))} $unitText" }
    Section(stringResource(R.string.body_weight)) {
        val trend = week.weightTrendKg
        when {
            trend == null -> Muted(stringResource(R.string.check_in_no_weight))
            week.weightChangeKg == null -> Text(stringResource(R.string.check_in_weight_first, mass(trend)))
            else -> Text(stringResource(R.string.check_in_weight, mass(trend), signedMass(week.weightChangeKg, unit, unitText)))
        }
        if (trend != null) {
            goal?.let { Coach.plannedWeeklyChangeKg(trend, it) }?.let { planned ->
                Muted(stringResource(R.string.weight_goal_pace, signedMass(planned, unit, unitText)))
            }
        }
    }
}

private fun signedMass(kg: Double, unit: WeightUnit, unitText: String): String {
    val shown = oneDecimal(abs(toDisplayWeight(kg, unit)))
    val sign = when {
        shown == "0" -> ""
        kg > 0 -> "+"
        else -> "-"
    }
    return "$sign$shown $unitText"
}

@Composable
private fun NextWeek(
    review: WeekReview?,
    routines: List<Routine>,
    exercises: List<Exercise>,
    ready: Boolean,
    pending: Pending?,
    onAsk: () -> Unit,
    onApply: (String, Set<Int>) -> Unit,
) {
    Section(stringResource(R.string.check_in_next_week)) {
        Muted(stringResource(R.string.check_in_coach_help))
        review?.let { week ->
            if (week.text.isNotEmpty()) Text(week.text)
            if (week.changes.isNotEmpty()) {
                val byId = routines.associateBy { it.id }
                val rows = week.changes.map { change ->
                    val routine = byId[change.routineId]
                    val title = changeTitle(change.action, exercises.byId(change.exerciseId).localizedName())
                    ChangeRow(
                        title = routine?.let { stringResource(R.string.check_in_change_in, it.name, title) } ?: title,
                        detail = planText(change.sets, change.reps, change.minutes),
                        checkable = routine?.accepts(change) == true,
                        applied = change.applied,
                    )
                }
                val footer = if (week.changes.any { !it.applied }) stringResource(R.string.check_in_changes_stale) else null
                ChangeChecklist(stringResource(R.string.check_in_changes), rows, footer) { onApply(week.weekStart, it) }
            }
        }
        when (pending) {
            Pending.Running -> Muted(stringResource(R.string.check_in_asking))
            is Pending.Failed -> FailureText(pending.error)
            null -> Unit
        }
        if (!ready) Muted(stringResource(R.string.check_in_needs_coach))
        Button(enabled = ready && pending != Pending.Running, onClick = onAsk) { Text(stringResource(R.string.check_in_ask)) }
    }
}

@Composable
private fun Muted(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun rangeText(week: WeekSummary): String {
    val locale = LocalConfiguration.current.locales[0]
    val format = remember(locale) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale) }
    return stringResource(R.string.check_in_range, week.start.format(format), week.end.format(format))
}
