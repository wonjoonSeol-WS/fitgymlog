package io.fitgymlog.app.ui.stats

import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.fitgymlog.app.R
import io.fitgymlog.app.catching
import io.fitgymlog.app.checkin.weekLine
import io.fitgymlog.app.checkin.weekSummary
import io.fitgymlog.app.coach.BODYWEIGHT_KG
import io.fitgymlog.app.coach.BODY_FAT_PERCENT
import io.fitgymlog.app.coach.BodyMeasurement
import io.fitgymlog.app.coach.BodyRead
import io.fitgymlog.app.coach.Coach
import io.fitgymlog.app.coach.Goal
import io.fitgymlog.app.coach.MeasurementSource
import io.fitgymlog.app.coach.SKELETAL_MUSCLE_KG
import io.fitgymlog.app.coach.VISCERAL_FAT_LEVEL
import io.fitgymlog.app.coach.coachJson
import io.fitgymlog.app.container
import io.fitgymlog.app.data.byId
import io.fitgymlog.app.health.openHealthConnect
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.ui.BarChart
import io.fitgymlog.app.ui.ChartPoint
import io.fitgymlog.app.ui.ConfirmDialog
import io.fitgymlog.app.ui.LineChart
import io.fitgymlog.app.ui.NumberStyles
import io.fitgymlog.app.ui.ReadableWidth
import io.fitgymlog.app.ui.TwoPaneMinWidth
import io.fitgymlog.app.ui.coach.FailureText
import io.fitgymlog.app.ui.formatWeightNumber
import io.fitgymlog.app.ui.fromDisplayWeight
import io.fitgymlog.app.ui.isDecimalInput
import io.fitgymlog.app.ui.localizedName
import io.fitgymlog.app.ui.oneDecimal
import io.fitgymlog.app.ui.parseDecimal
import io.fitgymlog.app.ui.settings.SettingsSwitch
import io.fitgymlog.app.ui.toDisplayWeight
import io.fitgymlog.app.ui.unitLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.WeekFields
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private data class Editing(val entry: BodyMeasurement, val isNew: Boolean)

private val EditingSaver = listSaver<Editing?, Any>(
    save = { open -> open?.let { listOf(coachJson.encodeToString(BodyMeasurement.serializer(), it.entry), it.isNew) }.orEmpty() },
    restore = { saved -> Editing(coachJson.decodeFromString(BodyMeasurement.serializer(), saved[0] as String), saved[1] as Boolean) },
)

@Composable
fun StatsScreen(onOpenExercise: (String) -> Unit, onOpenCheckIn: () -> Unit) {
    val context = LocalContext.current
    val app = context.container
    val coach = app.coach
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val unit = settings.unit
    val coachSettings by coach.settings.settings.collectAsStateWithLifecycle()
    val data by coach.store.data.collectAsStateWithLifecycle()
    val ready by coach.ready.collectAsStateWithLifecycle()
    val bodyRead by coach.bodyRead.collectAsStateWithLifecycle()
    val history by app.workouts.history.collectAsStateWithLifecycle<List<Workout>?>(null)
    val exercises by app.exercises.exercises.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val body by produceState<List<BodyMeasurement>?>(null, data.measurements, settings.readBodyFromHealth, refresh) { value = coach.bodyHistory() }
    val healthAvailable = remember { coach.healthBody.isAvailable }
    var healthReadable by remember { mutableStateOf<Boolean?>(null) }
    var healthDenied by rememberSaveable { mutableStateOf(false) }
    var healthCheck by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { healthCheck++ }
    LaunchedEffect(healthCheck) {
        val readable = catching { coach.healthBody.canRead() }.getOrDefault(false)
        if (readable) healthDenied = false
        if (healthReadable != null && readable != healthReadable) {
            coach.forgetHealthReadings()
            refresh++
        }
        healthReadable = readable
    }
    var editing by rememberSaveable(stateSaver = EditingSaver) { mutableStateOf(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var measure by rememberSaveable { mutableStateOf(WeeklyMeasure.WORKOUTS) }
    var range by rememberSaveable { mutableStateOf(ChartRange.QUARTER) }
    var metric by rememberSaveable { mutableStateOf(CompositionMetric.BODY_FAT) }
    val uses = remember(history, exercises) {
        history?.let { workouts -> exerciseUses(workouts).map { use -> use to exercises.byId(use.exerciseId) } }
    }
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val week = remember(history, body, exercises, locale) {
        val workouts = history ?: return@remember null
        weekSummary(workouts, body.orEmpty(), LocalDate.now(), WeekFields.of(locale).firstDayOfWeek, ZoneId.systemDefault()) { exercises.byId(it).kind }
    }

    val allowHealth = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        scope.launch {
            val readable = catching { coach.healthBody.canRead() }.getOrDefault(false)
            app.settings.update { it.copy(readBodyFromHealth = readable) }
            healthDenied = !readable
            healthCheck++
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(coach::readBodyPhoto) }
    LaunchedEffect(bodyRead, editing == null) {
        val found = bodyRead as? BodyRead.Found ?: return@LaunchedEffect
        if (editing == null) {
            editing = Editing(found.entry, isNew = true)
            coach.clearBodyRead()
        }
    }

    val header: LazyListScope.() -> Unit = {
        item(key = "title") {
            Text(stringResource(R.string.tab_stats), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
        }
        week?.let { summary ->
            item(key = "check-in") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.check_in)) },
                    supportingContent = { Text(weekLine(resources, summary)) },
                    modifier = Modifier.clickable(onClick = onOpenCheckIn),
                )
            }
        }
    }
    val weekly: LazyListScope.() -> Unit = {
        history?.takeIf { it.isNotEmpty() }?.let { workouts ->
            item(key = "weeks-title") { SectionTitle(stringResource(R.string.stats_weeks)) }
            item(key = "weeks") { WeeklySection(workouts, unit, measure) { measure = it } }
        }
    }
    val bodyItems: LazyListScope.() -> Unit = section@{
        val entries = body ?: return@section
        item(key = "weight-title") { SectionTitle(stringResource(R.string.body_weight), Modifier.padding(top = 12.dp)) }
        item(key = "weight") {
            WeightSection(
                body = entries,
                unit = unit,
                goal = coachSettings.goal.takeIf { coachSettings.enabled },
                range = range,
                onRange = { range = it },
                onAdd = { editing = Editing(BodyMeasurement(LocalDate.now().toString()), isNew = true) },
            )
        }
        if (healthAvailable) {
            item(key = "health") {
                Column {
                    SettingsSwitch(
                        title = stringResource(R.string.body_health),
                        help = stringResource(
                            when {
                                healthDenied -> R.string.body_health_denied
                                settings.readBodyFromHealth && healthReadable == true -> R.string.body_health_on
                                else -> R.string.body_health_help
                            },
                        ),
                        checked = settings.readBodyFromHealth && healthReadable == true,
                        onCheckedChange = { on ->
                            app.settings.update { it.copy(readBodyFromHealth = on) }
                            coach.forgetHealthReadings()
                            healthDenied = false
                            if (on && healthReadable != true) {
                                catching { allowHealth.launch(coach.healthBody.permissions()) }.onFailure { healthDenied = true }
                            } else {
                                refresh++
                            }
                        },
                    )
                    if (healthDenied) {
                        TextButton(onClick = { openHealthConnect(context) }) { Text(stringResource(R.string.health_connect_open)) }
                    }
                }
            }
        }
        item(key = "composition-title") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 12.dp)) {
                SectionTitle(stringResource(R.string.stats_composition))
                Text(stringResource(R.string.coach_body_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "composition") { CompositionSection(scans(entries), unit, metric) { metric = it } }
        item(key = "photo") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = ready && bodyRead != BodyRead.Reading,
                    onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                ) { Text(stringResource(R.string.body_from_photo)) }
                OutlinedButton(onClick = { editing = Editing(BodyMeasurement(LocalDate.now().toString()), isNew = true) }) {
                    Text(stringResource(R.string.body_add))
                }
            }
        }
        item(key = "photo-status") {
            when (val read = bodyRead) {
                BodyRead.Reading -> Text(stringResource(R.string.body_reading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                BodyRead.NothingFound -> Text(stringResource(R.string.body_nothing_read), color = MaterialTheme.colorScheme.error)
                BodyRead.Unreadable -> Text(stringResource(R.string.body_picture_failed), color = MaterialTheme.colorScheme.error)
                is BodyRead.Failed -> FailureText(read.error)
                else -> Text(
                    stringResource(if (ready) R.string.body_photo_help else R.string.body_photo_needs_coach),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (entries.isNotEmpty()) {
            item(key = "measurements-title") { SectionTitle(stringResource(R.string.stats_measurements), Modifier.padding(top = 12.dp)) }
        }
        items(if (showAll) entries.asReversed() else entries.asReversed().take(RECENT_ENTRIES), key = { "measurement-${it.date}" }) { entry ->
            val stored = data.measurements.find { it.date == entry.date }
            Column(Modifier.fillMaxWidth().then(if (stored != null) Modifier.clickable { editing = Editing(stored, isNew = false) } else Modifier)) {
                Text(dateText(entry.date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(measurementText(entry, unit))
                Text(stringResource(sourceText(entry.source)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(top = 8.dp))
        }
        if (!showAll && entries.size > RECENT_ENTRIES) {
            item(key = "show-all") { TextButton(onClick = { showAll = true }) { Text(stringResource(R.string.body_show_all, entries.size)) } }
        }
    }
    val exerciseItems: LazyListScope.() -> Unit = section@{
        val used = uses ?: return@section
        item(key = "exercises-title") { SectionTitle(stringResource(R.string.stats_exercises), Modifier.padding(top = 12.dp)) }
        if (used.isEmpty()) {
            item(key = "exercises-empty") { Text(stringResource(R.string.no_history), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(used, key = { (use, _) -> "exercise-${use.exerciseId}" }) { (use, exercise) ->
            ListItem(
                headlineContent = { Text(exercise.localizedName()) },
                supportingContent = {
                    Text(pluralStringResource(R.plurals.stats_sessions, use.sessions, use.sessions, dateText(use.lastDone.atZone(ZoneId.systemDefault()).toLocalDate())))
                },
                modifier = Modifier.clickable { onOpenExercise(use.exerciseId) },
            )
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
        if (maxWidth >= TwoPaneMinWidth) {
            Row(Modifier.fillMaxSize()) {
                StatsColumn(Modifier.weight(1f)) { header(); weekly(); exerciseItems() }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                StatsColumn(Modifier.weight(1f)) { bodyItems() }
            }
        } else {
            ReadableWidth { gutter -> StatsColumn(gutter = gutter) { header(); weekly(); bodyItems(); exerciseItems() } }
        }
    }

    editing?.let { open ->
        MeasurementDialog(
            initial = open.entry,
            unit = unit,
            canDelete = !open.isNew,
            onSave = { entry ->
                app.scope.launch {
                    if (open.isNew) coach.store.addMeasurement(entry) else coach.store.editMeasurement(open.entry.date, entry)
                }
            },
            onDelete = { deleting = open.entry.date },
            onDismiss = { editing = null },
        )
    }
    deleting?.let { date ->
        ConfirmDialog(
            title = stringResource(R.string.body_delete_confirm),
            text = null,
            confirm = stringResource(R.string.delete),
            onConfirm = {
                editing = null
                app.scope.launch { coach.store.removeMeasurement(date) }
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun StatsColumn(modifier: Modifier = Modifier, gutter: Dp = 0.dp, content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp + gutter, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = modifier.semantics { heading() })
}

@Composable
private fun WeeklySection(history: List<Workout>, unit: WeightUnit, measure: WeeklyMeasure, onMeasure: (WeeklyMeasure) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val resources = LocalResources.current
    val today = LocalDate.now()
    val bars = remember(history, measure, locale, unit, today) {
        weeklyTotals(history, measure, today, WeekFields.of(locale).firstDayOfWeek, ZoneId.systemDefault()).map { week ->
            when (measure) {
                WeeklyMeasure.WORKOUTS -> week
                WeeklyMeasure.TIME -> week.copy(value = week.value / 60)
                WeeklyMeasure.VOLUME -> week.copy(value = toDisplayWeight(week.value, unit))
                WeeklyMeasure.LOAD -> week
            }
        }
    }
    val unitText = unitLabel(unit)
    val label: (Double) -> String = when (measure) {
        WeeklyMeasure.WORKOUTS -> { value -> value.roundToInt().toString() }
        WeeklyMeasure.TIME -> { value -> resources.getString(R.string.hours_value, oneDecimal(value)) }
        WeeklyMeasure.VOLUME -> { value -> "${formatWeightNumber(fromDisplayWeight(value, unit), unit)} $unitText" }
        WeeklyMeasure.LOAD -> { value -> value.roundToInt().toString() }
    }
    val name = stringResource(weeklyText(measure))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chips(WeeklyMeasure.entries, measure, onMeasure) { stringResource(weeklyText(it)) }
        BarChart(
            bars = bars,
            label = label,
            description = stringResource(R.string.progress_description, name, label(bars.first().value), label(bars.last().value)),
            minStep = if (measure == WeeklyMeasure.TIME) 0.0 else 1.0,
        )
        Text(stringResource(R.string.week_this, label(bars.last().value)), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (measure == WeeklyMeasure.LOAD) LoadText(history)
    }
}

/** Like Apple's training load: the last 7 days against the usual week, or how to get a reading at all. */
@Composable
private fun LoadText(history: List<Workout>) {
    val comparison = remember(history) { loadComparison(history, Instant.now()) }
    val text = if (comparison == null) stringResource(R.string.load_none) else when (comparison.trend) {
        LoadTrend.ABOVE -> stringResource(R.string.load_above, comparison.percent)
        LoadTrend.STEADY -> stringResource(R.string.load_steady)
        LoadTrend.BELOW -> stringResource(R.string.load_below, -comparison.percent)
    }
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@StringRes
private fun weeklyText(measure: WeeklyMeasure): Int = when (measure) {
    WeeklyMeasure.WORKOUTS -> R.string.week_workouts
    WeeklyMeasure.TIME -> R.string.week_time
    WeeklyMeasure.VOLUME -> R.string.volume
    WeeklyMeasure.LOAD -> R.string.week_load
}

@Composable
private fun WeightSection(body: List<BodyMeasurement>, unit: WeightUnit, goal: Goal?, range: ChartRange, onRange: (ChartRange) -> Unit, onAdd: () -> Unit) {
    val readings = remember(body) { body.mapNotNull { entry -> entry.weightKg?.let { ChartPoint(LocalDate.parse(entry.date), it) } } }
    val trend = remember(readings) { weightTrend(readings) }
    val today = LocalDate.now()
    val unitText = unitLabel(unit)
    val massText: (Double) -> String = { kg -> "${oneDecimal(toDisplayWeight(kg, unit))} $unitText" }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (readings.isEmpty()) {
            Text(stringResource(R.string.weight_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val latest = trend.last()
            val stale = latest.date.isBefore(today.minusDays(STALE_TREND_DAYS))
            Text(
                if (stale) {
                    stringResource(R.string.weight_trend_as_of, massText(latest.value), shortDate(latest.date))
                } else {
                    stringResource(R.string.weight_trend, massText(latest.value))
                },
                style = MaterialTheme.typography.headlineSmall,
            )
            weeklyPace(trend)?.takeIf { !stale }?.let { pace ->
                Text(pluralStringResource(R.plurals.weight_pace, pace.weeks, signed(toDisplayWeight(pace.kgPerWeek, unit), unitText), pace.weeks))
            }
            goal?.let { Coach.plannedWeeklyChangeKg(latest.value, it) }?.let { planned ->
                Text(
                    stringResource(R.string.weight_goal_pace, signed(toDisplayWeight(planned, unit), unitText)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Chips(ChartRange.entries, range, onRange) { stringResource(rangeText(it)) }
            val shown = readings.within(range, today).ifEmpty { listOf(readings.last()) }
            val dates = shown.map { it.date }.toSet()
            val shownTrend = trend.filter { it.date in dates }
            val axis: (Double) -> String = { value -> "${oneDecimal(value)} $unitText" }
            LineChart(
                points = shown.map { ChartPoint(it.date, toDisplayWeight(it.value, unit)) },
                label = axis,
                description = stringResource(R.string.progress_description, stringResource(R.string.body_weight), massText(shownTrend.first().value), massText(shownTrend.last().value)),
                trend = shownTrend.map { ChartPoint(it.date, toDisplayWeight(it.value, unit)) },
            )
            Text(stringResource(R.string.weight_trend_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onAdd) { Text(stringResource(R.string.body_add)) }
    }
}

@Composable
private fun CompositionSection(scans: List<BodyMeasurement>, unit: WeightUnit, chosen: CompositionMetric, onChoose: (CompositionMetric) -> Unit) {
    if (scans.isEmpty()) {
        Text(stringResource(R.string.composition_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val available = CompositionMetric.entries.filter { metric -> scans.count { metric.read(it) != null } >= 2 }
    val unitText = unitLabel(unit)
    val display = { metric: CompositionMetric, value: Double -> if (metric.unit == CompositionUnit.MASS) toDisplayWeight(value, unit) else value }
    val displayText = { metric: CompositionMetric, shown: Double ->
        when (metric.unit) {
            CompositionUnit.MASS -> "${oneDecimal(shown)} $unitText"
            CompositionUnit.PERCENT -> "${oneDecimal(shown)}%"
            CompositionUnit.LEVEL -> oneDecimal(shown)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (available.isNotEmpty()) {
            val shown = chosen.takeIf { it in available } ?: available.first()
            if (available.size > 1) Chips(available, shown, onChoose) { stringResource(metricText(it)) }
            val points = scans.mapNotNull { scan -> shown.read(scan)?.let { ChartPoint(LocalDate.parse(scan.date), display(shown, it)) } }
            val axis: (Double) -> String = { value -> displayText(shown, value) }
            LineChart(
                points = points,
                label = axis,
                description = stringResource(R.string.progress_description, stringResource(metricText(shown)), axis(points.first().value), axis(points.last().value)),
                minStep = if (shown.unit == CompositionUnit.LEVEL) 1.0 else 0.0,
            )
        }
        compositionChanges(scans)?.let { changes -> CompositionTable(changes, display, displayText) }
    }
}

@Composable
private fun CompositionTable(
    changes: CompositionChanges,
    display: (CompositionMetric, Double) -> Double,
    displayText: (CompositionMetric, Double) -> String,
) {
    val header = MaterialTheme.typography.labelSmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Spacer(Modifier.weight(LABEL_WEIGHT))
            Text(shortDate(changes.latest), style = header, color = muted, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            changes.previous?.let { Text(stringResource(R.string.composition_vs, shortDate(it)), style = header, color = muted, textAlign = TextAlign.End, modifier = Modifier.weight(1f)) }
            changes.first?.let { Text(stringResource(R.string.composition_vs, shortDate(it)), style = header, color = muted, textAlign = TextAlign.End, modifier = Modifier.weight(1f)) }
        }
        changes.rows.forEach { row ->
            val delta = { change: Double? -> change?.let { signed(display(row.metric, it), "") }.orEmpty() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(metricText(row.metric)), modifier = Modifier.weight(LABEL_WEIGHT))
                Text(displayText(row.metric, display(row.metric, row.latest)), style = NumberStyles.cell, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                changes.previous?.let { Text(delta(row.sincePrevious), style = NumberStyles.cell, textAlign = TextAlign.End, modifier = Modifier.weight(1f)) }
                changes.first?.let { Text(delta(row.sinceFirst), style = NumberStyles.cell, textAlign = TextAlign.End, modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(options: List<T>, selected: T, onSelect: (T) -> Unit, label: @Composable (T) -> String) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option -> FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option)) }) }
    }
}

@StringRes
private fun metricText(metric: CompositionMetric): Int = when (metric) {
    CompositionMetric.BODY_FAT -> R.string.body_fat
    CompositionMetric.FAT_MASS -> R.string.body_fat_mass
    CompositionMetric.LEAN_MASS -> R.string.body_lean
    CompositionMetric.MUSCLE -> R.string.body_muscle
    CompositionMetric.VISCERAL_FAT -> R.string.body_visceral_short
}

@StringRes
private fun rangeText(range: ChartRange): Int = when (range) {
    ChartRange.MONTH -> R.string.range_month
    ChartRange.QUARTER -> R.string.range_quarter
    ChartRange.YEAR -> R.string.range_year
    ChartRange.ALL -> R.string.range_all
}

@Composable
private fun shortDate(date: LocalDate): String {
    val locale = LocalConfiguration.current.locales[0]
    return date.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale))
}

/** "+0.4 kg", "-1.2 kg" or "0 kg", to one decimal. */
private fun signed(value: Double, unitText: String): String {
    val rounded = oneDecimal(abs(value))
    val sign = when {
        rounded == "0" -> ""
        value > 0 -> "+"
        else -> "-"
    }
    return listOf("$sign$rounded", unitText).filter { it.isNotEmpty() }.joinToString(" ")
}

@Composable
fun measurementText(entry: BodyMeasurement, unit: WeightUnit): String {
    val unitText = unitLabel(unit)
    return listOfNotNull(
        entry.weightKg?.let { "${formatWeightNumber(it, unit)} $unitText" },
        entry.bodyFatPercent?.let { stringResource(R.string.body_fat_value, oneDecimal(it)) },
        entry.skeletalMuscleKg?.let { stringResource(R.string.body_muscle_value, "${formatWeightNumber(it, unit)} $unitText") },
        entry.visceralFatLevel?.let { stringResource(R.string.body_visceral_value, it) },
    ).joinToString(", ")
}

fun dateText(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

fun dateText(isoDate: String): String = dateText(LocalDate.parse(isoDate))

@StringRes
private fun sourceText(source: MeasurementSource): Int = when (source) {
    MeasurementSource.HEALTH_CONNECT -> R.string.body_source_health
    MeasurementSource.PHOTO -> R.string.body_source_photo
    MeasurementSource.MANUAL -> R.string.body_source_manual
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeasurementDialog(
    initial: BodyMeasurement,
    unit: WeightUnit,
    canDelete: Boolean,
    onSave: (BodyMeasurement) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val startWeight = initial.weightKg?.let { formatWeightNumber(it, unit) }.orEmpty()
    val startFat = initial.bodyFatPercent?.let(::oneDecimal).orEmpty()
    val startMuscle = initial.skeletalMuscleKg?.let { formatWeightNumber(it, unit) }.orEmpty()
    val startVisceral = initial.visceralFatLevel?.toString().orEmpty()
    var date by rememberSaveable(initial) { mutableStateOf(initial.date) }
    var weight by rememberSaveable(initial) { mutableStateOf(startWeight) }
    var fat by rememberSaveable(initial) { mutableStateOf(startFat) }
    var muscle by rememberSaveable(initial) { mutableStateOf(startMuscle) }
    var visceral by rememberSaveable(initial) { mutableStateOf(startVisceral) }
    var choosingDate by rememberSaveable { mutableStateOf(false) }
    val weightKg = if (weight == startWeight) initial.weightKg else parseDecimal(weight)?.let { fromDisplayWeight(it, unit) }
    val fatPercent = if (fat == startFat) initial.bodyFatPercent else parseDecimal(fat)
    val muscleKg = if (muscle == startMuscle) initial.skeletalMuscleKg else parseDecimal(muscle)?.let { fromDisplayWeight(it, unit) }
    val visceralLevel = visceral.toIntOrNull()
    val entered = listOf(weight, fat, muscle, visceral).any { it.isNotEmpty() }
    val valid = entered &&
        (weight.isEmpty() || weightKg != null && weightKg in BODYWEIGHT_KG) &&
        (fat.isEmpty() || fatPercent != null && fatPercent in BODY_FAT_PERCENT) &&
        (muscle.isEmpty() || muscleKg != null && muscleKg in SKELETAL_MUSCLE_KG) &&
        (visceral.isEmpty() || visceralLevel != null && visceralLevel in VISCERAL_FAT_LEVEL)
    val unitText = unitLabel(unit)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial.source == MeasurementSource.PHOTO && !canDelete) R.string.body_check else R.string.body_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { choosingDate = true }) { Text(dateText(date)) }
                BodyField(stringResource(R.string.body_weight), weight, unitText) { weight = it }
                BodyField(stringResource(R.string.body_fat), fat, "%") { fat = it }
                BodyField(stringResource(R.string.body_muscle), muscle, unitText) { muscle = it }
                BodyField(stringResource(R.string.body_visceral), visceral, suffix = null, whole = true) { visceral = it }
                if (!valid && entered) {
                    Text(stringResource(R.string.body_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val values = initial.copy(weightKg = weightKg, bodyFatPercent = fatPercent, skeletalMuscleKg = muscleKg, visceralFatLevel = visceralLevel)
                onSave(values.copy(date = date, source = if (values == initial) initial.source else MeasurementSource.MANUAL))
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (canDelete) TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )

    if (choosingDate) {
        val today = LocalDate.now()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)

                override fun isSelectableYear(year: Int) = year <= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { choosingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                    choosingDate = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { choosingDate = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state) }
    }
}

@Composable
private fun BodyField(label: String, value: String, suffix: String?, whole: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (if (whole) it.length <= 2 && it.all(Char::isDigit) else isDecimalInput(it)) onChange(it) },
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (whole) KeyboardType.Number else KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val RECENT_ENTRIES = 8
private const val STALE_TREND_DAYS = 7L
private const val LABEL_WEIGHT = 1.3f
