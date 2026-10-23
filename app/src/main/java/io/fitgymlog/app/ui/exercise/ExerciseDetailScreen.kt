package io.fitgymlog.app.ui.exercise

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.fitgymlog.app.R
import io.fitgymlog.app.container
import io.fitgymlog.app.data.byId
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.ui.ChartPoint
import io.fitgymlog.app.ui.LineChart
import io.fitgymlog.app.ui.NumberStyles
import io.fitgymlog.app.ui.ReadableWidth
import io.fitgymlog.app.ui.TwoPaneMinWidth
import io.fitgymlog.app.ui.formatDuration
import io.fitgymlog.app.ui.formatWeightNumber
import io.fitgymlog.app.ui.history.SessionLines
import io.fitgymlog.app.ui.history.exerciseSessions
import io.fitgymlog.app.ui.localizedName
import io.fitgymlog.app.ui.oneDecimal
import io.fitgymlog.app.ui.stats.dateText
import io.fitgymlog.app.ui.toDisplayWeight
import io.fitgymlog.app.ui.unitLabel
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseDetailScreen(exerciseId: String, onBack: () -> Unit, progressFirst: Boolean = false) {
    val context = LocalContext.current
    val app = context.container
    val exercises by app.exercises.exercises.collectAsStateWithLifecycle()
    val withExercise by remember(exerciseId) { app.workouts.recentWith(exerciseId, limit = Int.MAX_VALUE) }
        .collectAsStateWithLifecycle(null)
    val allSessions = remember(withExercise, exerciseId) { withExercise?.let { exerciseSessions(it, exerciseId) } }
    val sessions = allSessions?.take(CHART_SESSIONS)
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val exercise = exercises.byId(exerciseId)
    val best = remember(allSessions, exercise.kind) {
        val zone = ZoneId.systemDefault()
        allSessions?.let { all -> records(all.map { (workout, sets) -> workout.startedAt.atZone(zone).toLocalDate() to sets }, exercise.kind) }
    }
    val name = exercise.localizedName()

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(name, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        val nameItems: LazyListScope.() -> Unit = {
            if (name != exercise.englishName) {
                item { Text(exercise.englishName, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        val guideItems: LazyListScope.() -> Unit = {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.form_guide), style = MaterialTheme.typography.titleLarge)
                    FormGuide(exercise)
                }
            }
        }
        val sessionItems: LazyListScope.() -> Unit = {
            if (!best.isNullOrEmpty()) item { Records(exercise, best, settings.unit) }
            if (!sessions.isNullOrEmpty()) item { ProgressChart(exercise, sessions, settings.unit) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.recent_sessions), style = MaterialTheme.typography.titleLarge)
                    when {
                        sessions == null -> Unit
                        sessions.isEmpty() -> Text(stringResource(R.string.no_sessions_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> SessionLines(context, exercise, sessions.take(RECENT_SESSIONS), settings.unit)
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            if (maxWidth >= TwoPaneMinWidth) {
                Row(Modifier.fillMaxSize()) {
                    DetailColumn(Modifier.weight(1f), { nameItems(); guideItems() })
                    DetailColumn(Modifier.weight(1f), sessionItems)
                }
            } else {
                ReadableWidth { gutter ->
                    DetailColumn(gutter = gutter, content = {
                        nameItems()
                        if (progressFirst) { sessionItems(); guideItems() } else { guideItems(); sessionItems() }
                    })
                }
            }
        }
    }
}

private const val RECENT_SESSIONS = 10
private const val CHART_SESSIONS = 30

@Composable
private fun ProgressChart(exercise: Exercise, sessions: List<Pair<Workout, List<WorkoutSet>>>, unit: WeightUnit) {
    val resources = LocalResources.current
    val chartable = remember(sessions, exercise.kind) {
        val zone = ZoneId.systemDefault()
        chartableMetrics(sessions.map { (workout, sets) -> workout.startedAt.atZone(zone).toLocalDate() to sets }, exercise.kind)
    }
    if (chartable.isEmpty()) return
    var metric by rememberSaveable(exercise.id) { mutableStateOf(chartable.keys.first()) }
    val shown = metric.takeIf { it in chartable } ?: chartable.keys.first()
    val cardio = exercise.kind == ExerciseKind.CARDIO
    val points = chartable.getValue(shown).map { point ->
        point.copy(
            value = when (shown) {
                ProgressMetric.REPS -> point.value
                ProgressMetric.DURATION -> if (cardio) point.value / 60 else point.value
                else -> toDisplayWeight(point.value, unit)
            },
        )
    }
    val unitText = unitLabel(unit)
    val format: (Double) -> String = when (shown) {
        ProgressMetric.REPS -> { value -> value.roundToInt().toString() }
        ProgressMetric.DURATION -> { value -> formatDuration(resources, (if (cardio) value * 60 else value).roundToInt(), exercise.kind) }
        else -> { value -> "${BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()} $unitText" }
    }
    val wholeSteps = shown == ProgressMetric.REPS || shown == ProgressMetric.DURATION
    val name = stringResource(metricName(shown))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.progress), style = MaterialTheme.typography.titleLarge)
        if (chartable.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chartable.keys.forEach { option ->
                    FilterChip(selected = option == shown, onClick = { metric = option }, label = { Text(stringResource(metricName(option))) })
                }
            }
        }
        LineChart(points, format, stringResource(R.string.progress_description, name, format(points.first().value), format(points.last().value)), minStep = if (wholeSteps) 1.0 else 0.0)
    }
}

@Composable
private fun Records(exercise: Exercise, best: Map<ProgressMetric, ChartPoint>, unit: WeightUnit) {
    val resources = LocalResources.current
    val unitText = unitLabel(unit)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.records), style = MaterialTheme.typography.titleLarge)
        best.forEach { (metric, point) ->
            val value = when (metric) {
                ProgressMetric.REPS -> pluralStringResource(R.plurals.reps, point.value.roundToInt(), point.value.roundToInt())
                ProgressMetric.DURATION -> formatDuration(resources, point.value.roundToInt(), exercise.kind)
                ProgressMetric.ESTIMATED_MAX -> "${oneDecimal(toDisplayWeight(point.value, unit))} $unitText"
                else -> "${formatWeightNumber(point.value, unit)} $unitText"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(metricName(metric)), modifier = Modifier.weight(1f))
                Text(value, style = NumberStyles.cell)
                Text(dateText(point.date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
            }
        }
    }
}

@StringRes
internal fun metricName(metric: ProgressMetric): Int = when (metric) {
    ProgressMetric.HEAVIEST -> R.string.progress_heaviest
    ProgressMetric.ESTIMATED_MAX -> R.string.progress_estimated_max
    ProgressMetric.LEAST_ASSISTANCE -> R.string.progress_least_assistance
    ProgressMetric.REPS -> R.string.progress_reps
    ProgressMetric.DURATION -> R.string.progress_duration
}

@Composable
private fun DetailColumn(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit, gutter: Dp = 0.dp) {
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(horizontal = 16.dp + gutter, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
}
