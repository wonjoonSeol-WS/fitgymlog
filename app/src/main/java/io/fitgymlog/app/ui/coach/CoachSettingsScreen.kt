package io.fitgymlog.app.ui.coach

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.fitgymlog.app.R
import io.fitgymlog.app.catching
import io.fitgymlog.app.coach.BODYWEIGHT_KG
import io.fitgymlog.app.coach.BodyMeasurement
import io.fitgymlog.app.coach.Calories
import io.fitgymlog.app.coach.currentWeighIn
import io.fitgymlog.app.coach.CoachTask
import io.fitgymlog.app.coach.Connection
import io.fitgymlog.app.coach.Goal
import io.fitgymlog.app.coach.Sex
import io.fitgymlog.app.container
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.ui.ChoiceDialog
import io.fitgymlog.app.ui.ReadableWidth
import io.fitgymlog.app.ui.formatWeightNumber
import io.fitgymlog.app.ui.fromDisplayWeight
import io.fitgymlog.app.ui.isDecimalInput
import io.fitgymlog.app.ui.parseDecimal
import io.fitgymlog.app.ui.settings.SettingsItem
import io.fitgymlog.app.ui.settings.SettingsSection
import io.fitgymlog.app.ui.settings.SettingsSwitch
import io.fitgymlog.app.ui.stats.dateText
import io.fitgymlog.app.ui.stats.measurementText
import io.fitgymlog.app.ui.unitLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.launch

private enum class Provider(val label: String, val baseUrl: String) {
    OPENAI("OpenAI", "https://api.openai.com/v1"),
    GEMINI("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai"),
    ANTHROPIC("Anthropic", "https://api.anthropic.com/v1"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1"),
    GROQ("Groq", "https://api.groq.com/openai/v1"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com"),
}

private sealed interface Picker {
    data class TaskModel(val task: CoachTask) : Picker
    data object GoalChoice : Picker
    data object SexChoice : Picker
}

private sealed interface Probe {
    data object Idle : Probe
    data object Running : Probe
    data class Found(val models: List<String>) : Probe
    data class Failed(val error: Throwable) : Probe
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachSettingsScreen(onBack: () -> Unit, onOpenMemory: () -> Unit, onOpenBody: () -> Unit) {
    val app = LocalContext.current.container
    val coach = app.coach
    val settings by coach.settings.settings.collectAsStateWithLifecycle()
    val appSettings by app.settings.settings.collectAsStateWithLifecycle()
    val keySavedFor by coach.keySavedFor.collectAsStateWithLifecycle()
    val unit = appSettings.unit
    val data by coach.store.data.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf(settings.baseUrl) }
    var typedKey by remember { mutableStateOf("") }
    var changingKey by rememberSaveable { mutableStateOf(false) }
    var probe by remember { mutableStateOf<Probe>(Probe.Idle) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    val models = (probe as? Probe.Found)?.models.orEmpty()
    val validAddress = Connection.baseUrlOf(address)
    val keyFitsAddress = keySavedFor != null && keySavedFor == validAddress
    val askForKey = changingKey || !keyFitsAddress
    val keyOptional = validAddress?.let(Connection::keyOptional) == true
    var weightText by rememberSaveable(unit) { mutableStateOf(settings.bodyweightKg?.let { formatWeightNumber(it, unit) }.orEmpty()) }
    var heightText by rememberSaveable(unit) { mutableStateOf(settings.heightCm?.let { heightNumber(it, unit) }.orEmpty()) }
    var choosingBirthDate by remember { mutableStateOf(false) }
    val bodyHistory by produceState(emptyList<BodyMeasurement>(), data.measurements) { value = coach.bodyHistory() }
    val calories by produceState<Calories?>(null, bodyHistory, settings.bodyweightKg, settings.heightCm, settings.sex, settings.birthDate, settings.goal) {
        value = coach.calories(bodyHistory)
    }
    val measured = currentWeighIn(bodyHistory, LocalDate.now())

    fun changeAddress(input: String) {
        address = input
        probe = Probe.Idle
    }

    fun connect() {
        val base = validAddress ?: return
        probe = Probe.Running
        scope.launch {
            probe = catching { app.inAppScope { coach.connect(base, typedKey.takeIf { askForKey }) } }.fold(
                onSuccess = { found ->
                    typedKey = ""
                    changingKey = false
                    Probe.Found(found)
                },
                onFailure = { Probe.Failed(it) },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(stringResource(R.string.coach)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        ReadableWidth(Modifier.padding(padding)) { gutter ->
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = gutter)) {
                SettingsSwitch(
                    title = stringResource(R.string.coach_enable),
                    help = stringResource(R.string.coach_setting_help),
                    checked = settings.enabled,
                    onCheckedChange = { enabled -> coach.settings.update { it.copy(enabled = enabled) } },
                )

                SettingsSection(stringResource(R.string.coach_connection))
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Provider.entries.forEach { provider ->
                            FilterChip(
                                selected = validAddress == provider.baseUrl,
                                onClick = { changeAddress(provider.baseUrl) },
                                label = { Text(provider.label) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = address,
                        onValueChange = ::changeAddress,
                        label = { Text(stringResource(R.string.coach_address)) },
                        isError = address.isNotBlank() && validAddress == null,
                        supportingText = if (address.isNotBlank() && validAddress == null) {
                            { Text(stringResource(R.string.coach_address_invalid)) }
                        } else {
                            null
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (askForKey) {
                        OutlinedTextField(
                            value = typedKey,
                            onValueChange = { typedKey = it.trim() },
                            label = { Text(stringResource(if (keyOptional) R.string.coach_key_optional else R.string.coach_key)) },
                            supportingText = if (keySavedFor != null && !changingKey) {
                                { Text(stringResource(R.string.coach_key_for_address)) }
                            } else {
                                null
                            },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.coach_key)) },
                            supportingContent = { Text(stringResource(R.string.coach_key_saved)) },
                            trailingContent = {
                                Row {
                                    TextButton(onClick = { changingKey = true }) { Text(stringResource(R.string.coach_key_change)) }
                                    TextButton(onClick = {
                                        coach.removeKey()
                                        probe = Probe.Idle
                                    }) { Text(stringResource(R.string.coach_key_remove)) }
                                }
                            },
                        )
                    }
                    Button(
                        onClick = ::connect,
                        enabled = validAddress != null && (typedKey.isNotBlank() || !askForKey || keyOptional) && probe != Probe.Running,
                    ) { Text(stringResource(R.string.coach_connect)) }
                    when (val state = probe) {
                        Probe.Idle -> Unit
                        Probe.Running -> Text(stringResource(R.string.coach_connecting), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        is Probe.Found -> Text(pluralStringResource(R.plurals.coach_connected, state.models.size, state.models.size), color = MaterialTheme.colorScheme.primary)
                        is Probe.Failed -> FailureText(state.error)
                    }
                    ModelField(
                        label = stringResource(R.string.coach_model),
                        value = settings.model,
                        models = models,
                        onChange = { model -> coach.settings.update { it.copy(model = model) } },
                    )
                }

                SettingsSection(stringResource(R.string.coach_task_models))
                CoachTask.entries.forEach { task ->
                    SettingsItem(stringResource(taskText(task)), settings.taskModels[task] ?: stringResource(R.string.coach_model_default)) {
                        picker = Picker.TaskModel(task)
                    }
                }

                SettingsSection(stringResource(R.string.coach_about_you))
                NumberItem(
                    title = stringResource(R.string.coach_bodyweight),
                    help = when {
                        measured != null -> stringResource(R.string.coach_bodyweight_measured, "${formatWeightNumber(measured.weightKg!!, unit)} ${unitLabel(unit)}", dateText(measured.date))
                        settings.bodyweightKg == null -> stringResource(R.string.coach_bodyweight_unset)
                        else -> null
                    },
                    text = weightText,
                    suffix = unitLabel(unit),
                ) { text ->
                    if (!isDecimalInput(text)) return@NumberItem
                    weightText = text
                    val kg = parseDecimal(text)?.let { fromDisplayWeight(it, unit) }?.takeIf { it in BODYWEIGHT_KG }
                    coach.settings.update { it.copy(bodyweightKg = kg) }
                }
                NumberItem(
                    title = stringResource(R.string.coach_height),
                    text = heightText,
                    suffix = stringResource(if (unit == WeightUnit.LB) R.string.unit_in else R.string.unit_cm),
                ) { text ->
                    if (!isDecimalInput(text)) return@NumberItem
                    heightText = text
                    val cm = parseDecimal(text)?.let { if (unit == WeightUnit.LB) it * CM_PER_INCH else it }?.takeIf { it in HEIGHT_CM }
                    coach.settings.update { it.copy(heightCm = cm) }
                }
                SettingsItem(
                    stringResource(R.string.coach_body),
                    bodyHistory.lastOrNull()?.let { "${measurementText(it, unit)} (${dateText(it.date)})" } ?: stringResource(R.string.coach_body_summary_empty),
                    onOpenBody,
                )
                SettingsItem(stringResource(R.string.coach_sex), stringResource(sexText(settings.sex))) { picker = Picker.SexChoice }
                SettingsItem(
                    stringResource(R.string.coach_birth_date),
                    settings.birthDate?.let { dateText(it) } ?: stringResource(R.string.coach_birth_date_unset),
                ) { choosingBirthDate = true }
                SettingsItem(stringResource(R.string.coach_goal), stringResource(goalText(settings.goal))) { picker = Picker.GoalChoice }
                Text(
                    calories?.let { stringResource(if (it.fromLeanMass) R.string.coach_calories_estimate_lean else R.string.coach_calories_estimate, it.target, it.maintenance) }
                        ?: stringResource(R.string.coach_calories_unset),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                SettingsSwitch(
                    title = stringResource(R.string.coach_food_guide),
                    help = stringResource(R.string.coach_food_guide_help),
                    checked = settings.foodGuide,
                    onCheckedChange = { on -> coach.settings.update { it.copy(foodGuide = on) } },
                )

                SettingsSection(stringResource(R.string.coach_what_it_sees))
                Text(
                    stringResource(R.string.coach_what_it_sees_text),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                SettingsSwitch(
                    title = stringResource(R.string.coach_share_notes),
                    help = stringResource(R.string.coach_share_notes_help),
                    checked = settings.shareNotes,
                    onCheckedChange = { on -> coach.settings.update { it.copy(shareNotes = on) } },
                )
                SettingsItem(stringResource(R.string.coach_memory), pluralStringResource(R.plurals.coach_memory_count, data.memories.size, data.memories.size), onOpenMemory)
                SettingsSwitch(
                    title = stringResource(R.string.coach_sync_notes),
                    help = stringResource(if (settings.syncNotes && !appSettings.syncEnabled) R.string.coach_sync_notes_needs_drive else R.string.coach_sync_notes_help),
                    checked = settings.syncNotes,
                    onCheckedChange = { on ->
                        coach.settings.update { it.copy(syncNotes = on) }
                        if (on) app.sync.requestSync()
                    },
                )
                val nothingSent = stringResource(R.string.coach_preview_nothing)
                SettingsItem(stringResource(R.string.coach_preview), stringResource(R.string.coach_preview_help)) {
                    scope.launch { preview = catching { coach.preview() }.getOrNull() ?: nothingSent }
                }
                Text(
                    stringResource(R.string.coach_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    when (val open = picker) {
        is Picker.TaskModel -> ChoiceDialog(
            title = stringResource(taskText(open.task)),
            options = taskModelChoices(models, settings.model, settings.taskModels[open.task]),
            selected = settings.taskModels[open.task].orEmpty(),
            label = { it.ifEmpty { stringResource(R.string.coach_model_default) } },
            onSelect = { model -> coach.settings.update { it.copy(taskModels = if (model.isEmpty()) it.taskModels - open.task else it.taskModels + (open.task to model)) } },
            onDismiss = { picker = null },
        )
        Picker.GoalChoice -> ChoiceDialog(
            title = stringResource(R.string.coach_goal),
            options = Goal.entries,
            selected = settings.goal,
            label = { stringResource(goalText(it)) },
            onSelect = { goal -> coach.settings.update { it.copy(goal = goal) } },
            onDismiss = { picker = null },
        )
        Picker.SexChoice -> ChoiceDialog(
            title = stringResource(R.string.coach_sex),
            options = listOf(Sex.MALE, Sex.FEMALE, null),
            selected = settings.sex,
            label = { stringResource(sexText(it)) },
            onSelect = { sex -> coach.settings.update { it.copy(sex = sex) } },
            onDismiss = { picker = null },
        )
        null -> Unit
    }

    if (choosingBirthDate) {
        BirthDateDialog(settings.birthDate, onPick = { picked -> coach.settings.update { it.copy(birthDate = picked) } }, onDismiss = { choosingBirthDate = false })
    }

    preview?.let { text ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(stringResource(R.string.coach_preview)) },
            text = {
                SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
                    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { preview = null }) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun NumberItem(title: String, text: String, help: String? = null, suffix: String? = null, decimal: Boolean = true, onText: (String) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = help?.let { { Text(it) } },
        trailingContent = {
            OutlinedTextField(
                value = text,
                onValueChange = onText,
                textStyle = MaterialTheme.typography.bodyLarge,
                suffix = suffix?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
                modifier = Modifier.padding(start = 8.dp).fillMaxWidth(0.4f).semantics { contentDescription = title },
            )
        },
    )
}

@Composable
private fun ModelField(label: String, value: String, models: List<String>, onChange: (String) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.trim()) },
        label = { Text(label) },
        supportingText = { Text(stringResource(R.string.coach_model_hint)) },
        singleLine = true,
        trailingIcon = if (models.isNotEmpty()) {
            { IconButton(onClick = { choosing = true }) { Icon(Icons.Default.ArrowDropDown, label) } }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    )
    if (choosing) {
        ChoiceDialog(
            title = label,
            options = models,
            selected = value,
            label = { it },
            onSelect = onChange,
            onDismiss = { choosing = false },
        )
    }
}

/** "" stands for the main model; without a fresh model list, the ones already chosen are offered. */
private fun taskModelChoices(found: List<String>, main: String, current: String?): List<String> =
    listOf("") + (found.ifEmpty { listOf(main) } + listOfNotNull(current)).filter { it.isNotBlank() }.distinct()

private val HEIGHT_CM = 100.0..250.0
private const val CM_PER_INCH = 2.54

private fun heightNumber(cm: Double, unit: WeightUnit): String =
    "%.1f".format(Locale.ROOT, if (unit == WeightUnit.LB) cm / CM_PER_INCH else cm).trimEnd('0').trimEnd('.')

private fun taskText(task: CoachTask): Int = when (task) {
    CoachTask.TARGETS -> R.string.coach_task_targets
    CoachTask.REVIEW -> R.string.coach_task_review
    CoachTask.CHAT -> R.string.coach_task_chat
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirthDateDialog(current: LocalDate?, onPick: (LocalDate?) -> Unit, onDismiss: () -> Unit) {
    val today = LocalDate.now()
    val shown = current ?: today.minusYears(30)
    val state = rememberDatePickerState(
        initialSelectedDateMillis = current?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        initialDisplayedMonthMillis = shown.withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = (today.year - 100)..today.year,
        initialDisplayMode = DisplayMode.Input,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(today)

            override fun isSelectableYear(year: Int) = year <= today.year
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val picked = state.selectedDateMillis
            TextButton(enabled = picked != null, onClick = {
                onPick(picked?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() })
                onDismiss()
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onPick(null); onDismiss() }) { Text(stringResource(R.string.clear)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    ) { DatePicker(state) }
}
