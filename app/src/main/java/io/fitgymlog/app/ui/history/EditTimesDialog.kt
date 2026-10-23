package io.fitgymlog.app.ui.history

import android.text.format.DateFormat
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.fitgymlog.app.LOG_TAG
import io.fitgymlog.app.R
import io.fitgymlog.app.catching
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.workout.RetimeProblem
import io.fitgymlog.app.workout.retimeProblem
import io.fitgymlog.app.workout.timeBounds
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

private enum class Field { START_DATE, START_TIME, END_DATE, END_TIME }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTimesDialog(workout: Workout, onSave: suspend (Instant, Instant) -> RetimeProblem?, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val locale = LocalConfiguration.current.locales[0]
    val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
    val times = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    var start by rememberSaveable { mutableStateOf(workout.startedAt.atZone(zone).toLocalDateTime()) }
    var end by rememberSaveable { mutableStateOf((workout.endedAt ?: Instant.now()).atZone(zone).toLocalDateTime()) }
    var field by rememberSaveable { mutableStateOf<Field?>(null) }
    var refused by remember { mutableStateOf<Triple<Instant, Instant, RetimeProblem>?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val bounds = workout.timeBounds()
    val startAt = start.atZone(zone).toInstant()
    val endAt = end.atZone(zone).toInstant()
    val problem = workout.retimeProblem(startAt, endAt, Instant.now()) ?: refused?.takeIf { it.first == startAt && it.second == endAt }?.third

    fun local(instant: Instant): LocalDateTime = instant.atZone(zone).toLocalDateTime()

    fun label(instant: Instant): String = local(instant).let { if (it.toLocalDate() == start.toLocalDate()) it.format(times) else "${it.format(dates)} ${it.format(times)}" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_times)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TimeRow(stringResource(R.string.workout_start), start.format(dates), start.format(times), { field = Field.START_DATE }, { field = Field.START_TIME })
                TimeRow(stringResource(R.string.workout_end), end.format(dates), end.format(times), { field = Field.END_DATE }, { field = Field.END_TIME })
                bounds.earliestEnd?.takeIf { it != endAt }?.let { last ->
                    TextButton(onClick = { end = local(last) }) { Text(stringResource(R.string.end_at_last_set, local(last).format(times))) }
                }
                problem?.let {
                    val message = when (it) {
                        RetimeProblem.ORDER -> stringResource(R.string.times_order)
                        RetimeProblem.FUTURE -> stringResource(R.string.times_future)
                        RetimeProblem.OUTSIDE_SETS -> stringResource(R.string.times_outside, bounds.latestStart?.let(::label).orEmpty(), bounds.earliestEnd?.let(::label).orEmpty())
                        RetimeProblem.CLASH -> stringResource(R.string.times_clash, workout.title)
                    }
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = problem == null && !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        catching { onSave(startAt, endAt) }
                            .onSuccess { found -> if (found == null) onDismiss() else refused = Triple(startAt, endAt, found) }
                            .onFailure { Log.w(LOG_TAG, "Re-timing failed", it) }
                        saving = false
                    }
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )

    when (field) {
        Field.START_DATE -> DateField(start) { start = it; field = null }
        Field.END_DATE -> DateField(end) { end = it; field = null }
        Field.START_TIME -> TimeField(start) { start = it; field = null }
        Field.END_TIME -> TimeField(end) { end = it; field = null }
        null -> Unit
    }
}

@Composable
private fun TimeRow(label: String, date: String, time: String, onDate: () -> Unit, onTime: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = onDate) { Text(date) }
        TextButton(onClick = onTime) { Text(time) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(value: LocalDateTime, onDone: (LocalDateTime) -> Unit) {
    // The picker works in UTC midnight milliseconds for the chosen calendar day.
    val state = rememberDatePickerState(initialSelectedDateMillis = value.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = { onDone(value) },
        confirmButton = {
            TextButton(onClick = {
                val picked = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                onDone(picked?.atTime(value.toLocalTime()) ?: value)
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = { onDone(value) }) { Text(stringResource(R.string.cancel)) } },
    ) { DatePicker(state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeField(value: LocalDateTime, onDone: (LocalDateTime) -> Unit) {
    val state = rememberTimePickerState(value.hour, value.minute, DateFormat.is24HourFormat(LocalContext.current))
    AlertDialog(
        onDismissRequest = { onDone(value) },
        text = { TimePicker(state) },
        confirmButton = { TextButton(onClick = { onDone(value.with(LocalTime.of(state.hour, state.minute))) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { onDone(value) }) { Text(stringResource(R.string.cancel)) } },
    )
}
