package io.fitgymlog.app.ui.settings

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.fitgymlog.app.BuildConfig
import io.fitgymlog.app.R
import io.fitgymlog.app.container
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.transfer.ExportFormat
import io.fitgymlog.app.ui.ChoiceDialog
import io.fitgymlog.app.ui.ReadableWidth
import io.fitgymlog.app.ui.formatRest
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class Language(val tag: String) { SYSTEM(""), ENGLISH("en"), KOREAN("ko") }

private val IDLE_CHOICES = listOf(15, 30, 45, 60, 90)
private val SESSION_TYPES = listOf(
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
    ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
    ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS,
    ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING,
    ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT,
)

private enum class Picker { UNIT, LANGUAGE, REST, IDLE, SESSION_TYPE }

private val IMPORT_TYPES = arrayOf("text/*", "text/csv", "text/comma-separated-values", "application/json", "application/octet-stream")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenBandGuide: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val app = context.container
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf<Picker?>(null) }
    var healthMessage by remember { mutableStateOf<String?>(null) }
    val importFlow = rememberImportFlow()

    val healthPermission = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        val ok = granted.containsAll(app.health.permissions)
        app.settings.update { it.copy(healthConnectEnabled = ok) }
        healthMessage = if (ok) null else resources.getString(R.string.health_permission_denied)
    }

    fun enableHealthConnect() {
        scope.launch {
            try {
                when {
                    !app.health.isAvailable -> healthMessage = resources.getString(R.string.health_unavailable)
                    app.health.hasPermissions() -> {
                        app.settings.update { it.copy(healthConnectEnabled = true) }
                        healthMessage = null
                    }
                    else -> healthPermission.launch(app.health.permissions)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                healthMessage = resources.getString(R.string.health_unavailable)
            }
        }
    }
    val today = LocalDate.now().toString()
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { importFlow.export(it, ExportFormat.BACKUP) }
    }
    val exportHevy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { importFlow.export(it, ExportFormat.HEVY) }
    }
    val exportStrong = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { importFlow.export(it, ExportFormat.STRONG) }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importFlow.read(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineMedium) },
            )
        },
    ) { padding ->
        ReadableWidth(Modifier.padding(padding)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Section(stringResource(R.string.section_workout))
                Item(stringResource(R.string.setting_unit), unitName(settings.unit)) { picker = Picker.UNIT }
                Item(stringResource(R.string.setting_language), languageName(currentLanguage(context))) { picker = Picker.LANGUAGE }
                Item(stringResource(R.string.setting_default_rest), formatRest(settings.defaultRestSeconds)) { picker = Picker.REST }
                Item(stringResource(R.string.setting_auto_finish), stringResource(R.string.minutes_value, settings.idleFinishMinutes)) { picker = Picker.IDLE }

                Section(stringResource(R.string.section_band))
                Item(stringResource(R.string.band_guide_title), stringResource(R.string.band_guide_card_text), onOpenBandGuide)
                SwitchItem(
                    title = stringResource(R.string.setting_band_remote),
                    help = stringResource(R.string.setting_band_remote_help),
                    checked = settings.bandRemoteEnabled,
                    onCheckedChange = { enabled -> app.settings.update { it.copy(bandRemoteEnabled = enabled) } },
                )

                Section(stringResource(R.string.section_samsung_health))
                SwitchItem(
                    title = stringResource(R.string.setting_health_connect),
                    help = healthMessage ?: stringResource(R.string.setting_health_connect_help),
                    checked = settings.healthConnectEnabled,
                    onCheckedChange = { enabled ->
                        if (enabled) enableHealthConnect() else app.settings.update { it.copy(healthConnectEnabled = false) }
                    },
                )
                Item(stringResource(R.string.setting_health_type), sessionTypeName(settings.healthSessionType)) { picker = Picker.SESSION_TYPE }

                Section(stringResource(R.string.section_sync))
                SyncSection()

                Section(stringResource(R.string.section_data))
                Item(stringResource(R.string.import_data), stringResource(R.string.import_data_help)) { importFile.launch(IMPORT_TYPES) }
                Item(stringResource(R.string.export_backup), stringResource(R.string.export_backup_help)) { exportBackup.launch("fitgymlog-backup-$today.json") }
                Item(stringResource(R.string.export_hevy), stringResource(R.string.export_hevy_help)) { exportHevy.launch("fitgymlog-hevy-$today.csv") }
                Item(stringResource(R.string.export_strong), stringResource(R.string.export_strong_help)) { exportStrong.launch("fitgymlog-strong-$today.csv") }

                Section(stringResource(R.string.section_about))
                ListItem(headlineContent = { Text(stringResource(R.string.app_name)) }, supportingContent = { Text(BuildConfig.VERSION_NAME) })
            }
        }
    }

    importFlow.Dialogs()

    when (picker) {
        Picker.UNIT -> ChoiceDialog(
            title = stringResource(R.string.setting_unit),
            options = WeightUnit.entries,
            selected = settings.unit,
            label = { unitName(it) },
            onSelect = { unit -> app.settings.update { it.copy(unit = unit) } },
            onDismiss = { picker = null },
        )
        Picker.LANGUAGE -> ChoiceDialog(
            title = stringResource(R.string.setting_language),
            options = Language.entries,
            selected = currentLanguage(context),
            label = { languageName(it) },
            onSelect = { language -> setLanguage(context, language) },
            onDismiss = { picker = null },
        )
        Picker.REST -> ChoiceDialog(
            title = stringResource(R.string.setting_default_rest),
            options = listOf(60, 90, 120, 150, 180, 240, 300),
            selected = settings.defaultRestSeconds,
            label = { formatRest(it) },
            onSelect = { seconds -> app.settings.update { it.copy(defaultRestSeconds = seconds) } },
            onDismiss = { picker = null },
        )
        Picker.IDLE -> ChoiceDialog(
            title = stringResource(R.string.setting_auto_finish),
            options = IDLE_CHOICES,
            selected = settings.idleFinishMinutes,
            label = { stringResource(R.string.minutes_value, it) },
            onSelect = { minutes -> app.settings.update { it.copy(idleFinishMinutes = minutes) } },
            onDismiss = { picker = null },
        )
        Picker.SESSION_TYPE -> ChoiceDialog(
            title = stringResource(R.string.setting_health_type),
            options = SESSION_TYPES,
            selected = settings.healthSessionType,
            label = { sessionTypeName(it) },
            onSelect = { type -> app.settings.update { it.copy(healthSessionType = type) } },
            onDismiss = { picker = null },
        )
        null -> Unit
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Item(title: String, value: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SwitchItem(title: String, help: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(help) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
    )
}

@Composable
private fun unitName(unit: WeightUnit): String = stringResource(if (unit == WeightUnit.KG) R.string.unit_kg_long else R.string.unit_lb_long)

@Composable
private fun languageName(language: Language): String = when (language) {
    Language.SYSTEM -> stringResource(R.string.language_system)
    Language.ENGLISH -> "English"
    Language.KOREAN -> "한국어"
}

@Composable
private fun sessionTypeName(type: Int): String = stringResource(
    when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> R.string.session_weightlifting
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> R.string.session_calisthenics
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> R.string.session_hiit
        ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT -> R.string.session_other
        else -> R.string.session_strength
    },
)

private fun currentLanguage(context: Context): Language {
    val tag = context.getSystemService(LocaleManager::class.java).applicationLocales.get(0)?.language.orEmpty()
    return Language.entries.firstOrNull { it.tag == tag && tag.isNotEmpty() } ?: Language.SYSTEM
}

private fun setLanguage(context: Context, language: Language) {
    context.getSystemService(LocaleManager::class.java).applicationLocales =
        if (language == Language.SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language.tag)
}

