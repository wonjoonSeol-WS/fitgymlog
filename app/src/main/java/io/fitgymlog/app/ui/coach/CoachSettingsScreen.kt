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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.fitgymlog.app.R
import io.fitgymlog.app.catching
import io.fitgymlog.app.coach.CoachTask
import io.fitgymlog.app.coach.Connection
import io.fitgymlog.app.coach.Goal
import io.fitgymlog.app.container
import io.fitgymlog.app.ui.ChoiceDialog
import io.fitgymlog.app.ui.ReadableWidth
import io.fitgymlog.app.ui.formatWeight
import io.fitgymlog.app.ui.fromDisplayWeight
import io.fitgymlog.app.ui.formatWeightNumber
import io.fitgymlog.app.ui.isDecimalInput
import io.fitgymlog.app.ui.parseDecimal
import io.fitgymlog.app.ui.settings.SettingsItem
import io.fitgymlog.app.ui.settings.SettingsSection
import io.fitgymlog.app.ui.settings.SettingsSwitch
import io.fitgymlog.app.ui.unitLabel
import kotlinx.coroutines.launch

/** Documented OpenAI-compatible base addresses; the key decides which models the list shows. */
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
    data object Goal : Picker
}

private sealed interface Probe {
    data object Idle : Probe
    data object Running : Probe
    data class Found(val models: List<String>) : Probe
    data class Failed(val message: Int) : Probe
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachSettingsScreen(onBack: () -> Unit, onOpenMemory: () -> Unit) {
    val context = LocalContext.current
    val coach = context.container.coach
    val app = context.container
    val settings by coach.settings.settings.collectAsStateWithLifecycle()
    val unit = app.settings.settings.collectAsStateWithLifecycle().value.unit
    val data by coach.store.data.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf(settings.baseUrl) }
    var typedKey by rememberSaveable { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(coach.hasKey) }
    var editingKey by rememberSaveable { mutableStateOf(!coach.hasKey) }
    var probe by remember { mutableStateOf<Probe>(Probe.Idle) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }
    val models = (probe as? Probe.Found)?.models.orEmpty()
    val validAddress = Connection.baseUrlOf(address)
    var weightText by rememberSaveable(unit) { mutableStateOf(settings.bodyweightKg?.let { formatWeightNumber(it, unit) }.orEmpty()) }

    fun saveAddress(input: String) {
        address = input
        Connection.baseUrlOf(input)?.let { base -> coach.settings.update { it.copy(baseUrl = base) } }
    }

    fun connect() {
        val base = validAddress ?: return
        if (typedKey.isNotBlank()) {
            coach.saveKey(typedKey)
            typedKey = ""
            hasKey = true
            editingKey = false
        }
        probe = Probe.Running
        scope.launch {
            probe = catching { coach.models(base, apiKey = null) }.fold(
                onSuccess = { found ->
                    if (settings.model.isBlank()) found.firstOrNull()?.let { first -> coach.settings.update { it.copy(model = first) } }
                    Probe.Found(found)
                },
                onFailure = { Probe.Failed(failureText(it)) },
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
        ReadableWidth(Modifier.padding(padding)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
                                onClick = { saveAddress(provider.baseUrl) },
                                label = { Text(provider.label) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = address,
                        onValueChange = ::saveAddress,
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
                    if (editingKey) {
                        OutlinedTextField(
                            value = typedKey,
                            onValueChange = { typedKey = it.trim() },
                            label = { Text(stringResource(R.string.coach_key)) },
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
                                    TextButton(onClick = { editingKey = true }) { Text(stringResource(R.string.coach_key_change)) }
                                    TextButton(onClick = {
                                        coach.saveKey(null)
                                        hasKey = false
                                        editingKey = true
                                        probe = Probe.Idle
                                    }) { Text(stringResource(R.string.coach_key_remove)) }
                                }
                            },
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = ::connect,
                            enabled = validAddress != null && (hasKey || typedKey.isNotBlank()) && probe != Probe.Running,
                        ) { Text(stringResource(R.string.coach_connect)) }
                    }
                    when (val state = probe) {
                        Probe.Idle -> Unit
                        Probe.Running -> Text(stringResource(R.string.coach_connecting), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        is Probe.Found -> Text(pluralStringResource(R.plurals.coach_connected, state.models.size, state.models.size), color = MaterialTheme.colorScheme.primary)
                        is Probe.Failed -> Text(stringResource(state.message), color = MaterialTheme.colorScheme.error)
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
                ListItem(
                    headlineContent = { Text(stringResource(R.string.coach_bodyweight)) },
                    supportingContent = {
                        Text(settings.bodyweightKg?.let { formatWeight(context, it, unit) } ?: stringResource(R.string.coach_bodyweight_unset))
                    },
                    trailingContent = {
                        OutlinedTextField(
                            value = weightText,
                            onValueChange = { text ->
                                if (!isDecimalInput(text)) return@OutlinedTextField
                                weightText = text
                                val kg = parseDecimal(text)?.let { fromDisplayWeight(it, unit) }?.takeIf { it in BODYWEIGHT_KG }
                                coach.settings.update { it.copy(bodyweightKg = kg) }
                            },
                            suffix = { Text(unitLabel(unit)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.padding(start = 8.dp).fillMaxWidth(0.4f),
                        )
                    },
                )
                SettingsItem(stringResource(R.string.coach_goal), stringResource(goalText(settings.goal))) { picker = Picker.Goal }
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
                SettingsItem(stringResource(R.string.coach_preview), stringResource(R.string.coach_preview_help)) {
                    scope.launch { preview = coach.preview(app.workouts.active.value) }
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
            options = listOf("") + (models.ifEmpty { listOf(settings.model) } + listOfNotNull(settings.taskModels[open.task])).filter { it.isNotBlank() }.distinct(),
            selected = settings.taskModels[open.task].orEmpty(),
            label = { it.ifEmpty { stringResource(R.string.coach_model_default) } },
            onSelect = { model -> coach.settings.update { it.copy(taskModels = if (model.isEmpty()) it.taskModels - open.task else it.taskModels + (open.task to model)) } },
            onDismiss = { picker = null },
        )
        Picker.Goal -> ChoiceDialog(
            title = stringResource(R.string.coach_goal),
            options = Goal.entries,
            selected = settings.goal,
            label = { stringResource(goalText(it)) },
            onSelect = { goal -> coach.settings.update { it.copy(goal = goal) } },
            onDismiss = { picker = null },
        )
        null -> Unit
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

private val BODYWEIGHT_KG = 25.0..300.0

private fun taskText(task: CoachTask): Int = when (task) {
    CoachTask.TARGETS -> R.string.coach_task_targets
    CoachTask.REVIEW -> R.string.coach_task_review
    CoachTask.CHAT -> R.string.coach_task_chat
}
