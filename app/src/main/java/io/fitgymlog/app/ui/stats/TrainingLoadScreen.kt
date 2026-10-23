package io.fitgymlog.app.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.fitgymlog.app.R
import io.fitgymlog.app.container
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.ui.LineChart
import io.fitgymlog.app.ui.ReadableWidth
import io.fitgymlog.app.ui.history.EffortSheet
import io.fitgymlog.app.ui.history.effortLabel
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Like Apple's training load: the last 7 days against the usual week, drawn day by day over the last 8 weeks, with the
 * workouts behind it so an unrated one can be rated right here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrainingLoadScreen(onBack: () -> Unit) {
    val app = LocalContext.current.container
    val history by app.workouts.history.collectAsStateWithLifecycle<List<Workout>?>(null)
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val series = remember(history, today) { history?.let { loadSeries(it, today, zone) } }
    val recent = remember(history, today) {
        val since = today.minusDays(RECENT_DAYS - 1).atStartOfDay(zone).toInstant()
        history.orEmpty().filter { it.endedAt != null && !it.startedAt.isBefore(since) }.sortedByDescending { it.startedAt }
    }
    var rating by rememberSaveable { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(stringResource(R.string.training_load)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        val workouts = history ?: return@Scaffold
        ReadableWidth(Modifier.padding(padding)) { gutter ->
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp + gutter, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "headline") { Text(loadHeadline(workouts), style = MaterialTheme.typography.bodyLarge) }
                series?.let { load ->
                    item(key = "chart") { LoadChart(load) }
                }
                item(key = "explain") {
                    Text(stringResource(R.string.load_explain), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (recent.isNotEmpty()) {
                    item(key = "recent-title") {
                        Text(stringResource(R.string.load_recent), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp).semantics { heading() })
                    }
                    items(recent, key = { it.id }) { workout -> RecentWorkout(workout) { rating = workout.id } }
                }
            }
        }
    }

    val rated = rating?.let { id -> history?.find { it.id == id } }
    if (rated != null) {
        EffortSheet(
            initial = rated.effort,
            onSave = { effort ->
                app.scope.launch { app.actions.rateEffort(rated.id, effort) }
                rating = null
            },
            onSkip = { rating = null },
            onClear = rated.effort?.let {
                {
                    app.scope.launch { app.actions.rateEffort(rated.id, null) }
                    rating = null
                }
            },
        )
    }
}

/** The headline, shared with the Stats card: the comparison, or how to get one. */
@Composable
internal fun loadHeadline(history: List<Workout>): String {
    val comparison = remember(history) { loadComparison(history, Instant.now()) }
    return if (comparison == null) stringResource(R.string.load_none) else when (comparison.trend) {
        LoadTrend.ABOVE -> stringResource(R.string.load_above, comparison.percent)
        LoadTrend.STEADY -> stringResource(R.string.load_steady)
        LoadTrend.BELOW -> stringResource(R.string.load_below, -comparison.percent)
    }
}

@Composable
private fun LoadChart(series: LoadSeries) {
    val label: (Double) -> String = { it.roundToInt().toString() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LineChart(
            points = series.lastWeek,
            label = label,
            description = stringResource(R.string.load_chart_description, label(series.lastWeek.last().value), label(series.usualWeek.last().value)),
            baseline = series.usualWeek,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Legend(stringResource(R.string.load_last_week), MaterialTheme.colorScheme.primary, dashed = false)
            Legend(stringResource(R.string.load_usual_week), MaterialTheme.colorScheme.onSurfaceVariant, dashed = true)
        }
    }
}

@Composable
private fun Legend(text: String, color: Color, dashed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(width = 20.dp, height = 8.dp)) {
            val dash = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null
            drawLine(color, Offset(0f, center.y), Offset(size.width, center.y), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash)
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RecentWorkout(workout: Workout, onRate: () -> Unit) {
    val minutes = Duration.between(workout.startedAt, workout.endedAt ?: workout.startedAt).toMinutes().toInt()
    val date = workout.startedAt.atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    val effort = workout.effort
    ListItem(
        headlineContent = { Text(workout.title) },
        supportingContent = { Text("$date · ${stringResource(R.string.minutes_value, minutes)}") },
        trailingContent = {
            if (effort == null) {
                Text(stringResource(R.string.effort_unrated), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            } else {
                Text("$effort · ${stringResource(effortLabel(effort))}", style = MaterialTheme.typography.labelLarge)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.effort_rate), onClick = onRate),
    )
}

private const val RECENT_DAYS = 28L
