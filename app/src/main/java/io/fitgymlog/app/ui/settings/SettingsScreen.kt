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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.fitgymlog.app.BuildConfig
import io.fitgymlog.app.R
import io.fitgymlog.app.catching
import io.fitgymlog.app.checkin.CheckInAlarm
import io.fitgymlog.app.container
import io.fitgymlog.app.data.WristDevice
import io.fitgymlog.app.health.openHealthConnect
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.transfer.ExportFormat
import io.fitgymlog.app.ui.ChoiceDialog
import io.fitgymlog.app.ui.ReadableWidth
import io.fitgymlog.app.ui.formatRest
import io.fitgymlog.app.ui.exercise.openLink
import io.fitgymlog.app.update.UpdateCheck
import io.fitgymlog.app.update.Updates
import java.time.LocalDate
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

private enum class Picker { UNIT, LANGUAGE, REST, IDLE, SESSION_TYPE, WRIST }

private val IMPORT_TYPES = arrayOf("text/*", "text/csv", "text/comma-separated-values", "application/json", "application/octet-stream")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onOpenBandGuide: () -> Unit, onOpenCoach: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val app = context.container
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val coach by app.coach.settings.settings.collectAsStateWithLifecycle()
    val coachReady by app.coach.ready.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var picker by remember { mutableStateOf<Picker?>(null) }
    var healthMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var healthDenied by rememberSaveable { mutableStateOf(false) }
    var readsExercise by remember { mutableStateOf(false) }
    var healthCheck by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { healthCheck++ }
    LaunchedEffect(healthCheck) {
        readsExercise = catching { app.health.readsExercise() }.getOrDefault(false)
        val allowed = catching { app.health.hasPermissions() }.getOrDefault(false)
        if (healthDenied && allowed && (!settings.skipRecordedCardio || readsExercise)) {
            healthDenied = false
            healthMessage = null
        }
    }
    val importFlow = rememberImportFlow()

    // Health Connect is asked again after a request, rather than trusting the result, which may list only what this request changed.
    val healthPermission = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        scope.launch {
            val ok = catching { app.health.hasPermissions() }.getOrDefault(false)
            readsExercise = catching { app.health.readsExercise() }.getOrDefault(false)
            val skipping = settings.skipRecordedCardio
            app.settings.update { it.copy(healthConnectEnabled = ok, skipRecordedCardio = it.skipRecordedCardio && readsExercise) }
            healthDenied = !ok || (skipping && !readsExercise)
            healthMessage = when {
                !ok -> resources.getString(R.string.health_permission_denied)
                healthDenied -> resources.getString(R.string.health_read_denied)
                else -> null
            }
        }
    }

    fun enableHealthConnect() {
        scope.launch {
            catching {
                when {
                    !app.health.isAvailable -> healthMessage = resources.getString(R.string.health_unavailable)
                    app.health.hasPermissions() && (!settings.skipRecordedCardio || app.health.readsExercise()) -> {
                        app.settings.update { it.copy(healthConnectEnabled = true) }
                        healthMessage = null
                    }
                    else -> healthPermission.launch(if (settings.skipRecordedCardio) app.health.permissions else setOf(app.health.writePermission) + app.health.detailPermissions)
                }
            }.onFailure { healthMessage = resources.getString(R.string.health_unavailable) }
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
        ReadableWidth(Modifier.padding(padding)) { gutter ->
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = gutter)) {
                UpdateItem()
                SettingsSection(stringResource(R.string.section_workout))
                SettingsItem(stringResource(R.string.setting_unit), unitName(settings.unit)) { picker = Picker.UNIT }
                SettingsItem(stringResource(R.string.setting_language), languageName(currentLanguage(context))) { picker = Picker.LANGUAGE }
                SettingsItem(stringResource(R.string.setting_default_rest), formatRest(settings.defaultRestSeconds)) { picker = Picker.REST }
                SettingsItem(stringResource(R.string.setting_auto_finish), stringResource(R.string.minutes_value, settings.idleFinishMinutes)) { picker = Picker.IDLE }
                SettingsSwitch(
                    title = stringResource(R.string.setting_warmup),
                    help = stringResource(R.string.setting_warmup_help),
                    checked = settings.warmupSet,
                    onCheckedChange = { on -> app.settings.update { it.copy(warmupSet = on) } },
                )
                SettingsSwitch(
                    title = stringResource(R.string.setting_play_videos_in_app),
                    help = stringResource(R.string.setting_play_videos_in_app_help),
                    checked = settings.playVideosInApp,
                    onCheckedChange = { on -> app.settings.update { it.copy(playVideosInApp = on) } },
                )
                SettingsSwitch(
                    title = stringResource(R.string.setting_check_in),
                    help = stringResource(R.string.setting_check_in_help),
                    checked = settings.weeklyCheckIn,
                    onCheckedChange = { on ->
                        app.settings.update { it.copy(weeklyCheckIn = on) }
                        CheckInAlarm.sync(context, on)
                    },
                )

                SettingsSection(stringResource(R.string.section_band))
                SettingsItem(stringResource(R.string.setting_wrist), wristName(settings.wrist)) { picker = Picker.WRIST }
                when (settings.wrist) {
                    WristDevice.FIT_BAND -> {
                        SettingsHelp(stringResource(R.string.setting_band_remote_help))
                        SettingsItem(stringResource(R.string.band_guide_title), stringResource(R.string.band_guide_card_text), onOpenBandGuide)
                    }
                    WristDevice.WEAR_OS -> SettingsHelp(stringResource(R.string.wrist_wear_help))
                    WristDevice.OFF -> Unit
                }

                SettingsSection(stringResource(R.string.section_samsung_health))
                SettingsSwitch(
                    title = stringResource(R.string.setting_health_connect),
                    help = healthMessage ?: stringResource(R.string.setting_health_connect_help),
                    checked = settings.healthConnectEnabled,
                    onCheckedChange = { enabled ->
                        healthDenied = false
                        healthMessage = null
                        if (enabled) enableHealthConnect() else app.settings.update { it.copy(healthConnectEnabled = false) }
                    },
                )
                SettingsItem(stringResource(R.string.setting_health_type), sessionTypeName(settings.healthSessionType)) { picker = Picker.SESSION_TYPE }
                if (settings.healthConnectEnabled) {
                    SettingsSwitch(
                        title = stringResource(R.string.setting_health_skip_cardio),
                        help = stringResource(R.string.setting_health_skip_cardio_help),
                        checked = settings.skipRecordedCardio && readsExercise,
                        onCheckedChange = { on ->
                            app.settings.update { it.copy(skipRecordedCardio = on) }
                            healthDenied = false
                            healthMessage = null
                            if (on && !readsExercise) {
                                catching { healthPermission.launch(setOf(app.health.readPermission)) }
                                    .onFailure { healthMessage = resources.getString(R.string.health_unavailable) }
                            }
                        },
                    )
                }
                if (healthDenied) {
                    TextButton(onClick = { openHealthConnect(context) }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(stringResource(R.string.health_connect_open))
                    }
                }

                SettingsSection(stringResource(R.string.coach))
                SettingsItem(
                    stringResource(R.string.coach_enable),
                    if (coachReady) coach.model else stringResource(R.string.coach_setting_help),
                    onOpenCoach,
                )

                SettingsSection(stringResource(R.string.section_sync))
                SyncSection()

                SettingsSection(stringResource(R.string.section_data))
                SettingsItem(stringResource(R.string.import_data), stringResource(R.string.import_data_help)) { importFile.launch(IMPORT_TYPES) }
                SettingsItem(stringResource(R.string.export_backup), stringResource(R.string.export_backup_help)) { exportBackup.launch("fitgymlog-backup-$today.json") }
                SettingsItem(stringResource(R.string.export_hevy), stringResource(R.string.export_hevy_help)) { exportHevy.launch("fitgymlog-hevy-$today.csv") }
                SettingsItem(stringResource(R.string.export_strong), stringResource(R.string.export_strong_help)) { exportStrong.launch("fitgymlog-strong-$today.csv") }
            }
        }
    }

    importFlow.Dialogs()

    when (picker) {
        Picker.WRIST -> ChoiceDialog(
            title = stringResource(R.string.setting_wrist),
            options = WristDevice.entries,
            selected = settings.wrist,
            label = { wristName(it) },
            onSelect = { wrist -> app.settings.update { it.copy(wrist = wrist) } },
            onDismiss = { picker = null },
        )
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
internal fun SettingsSection(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
internal fun SettingsItem(title: String, value: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
internal fun SettingsSwitch(title: String, help: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
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

@Composable
private fun wristName(wrist: WristDevice): String = stringResource(
    when (wrist) {
        WristDevice.FIT_BAND -> R.string.wrist_fit_band
        WristDevice.WEAR_OS -> R.string.wrist_wear_os
        WristDevice.OFF -> R.string.wrist_off
    },
)

@Composable
private fun SettingsHelp(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** The version first, with a button to ask GitHub for a newer one; when there is one, the button opens its download page. */
@Composable
private fun UpdateItem() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var check by remember { mutableStateOf<UpdateCheck?>(null) }
    var checking by remember { mutableStateOf(false) }
    val current = BuildConfig.VERSION_NAME
    val result = check
    val status = when {
        checking -> stringResource(R.string.update_checking)
        result == null -> stringResource(R.string.update_never_checked)
        result is UpdateCheck.UpToDate -> stringResource(R.string.update_up_to_date)
        result is UpdateCheck.Available -> stringResource(R.string.update_available, result.version)
        else -> stringResource(R.string.update_failed)
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.app_version, current)) },
        supportingContent = { Text(status) },
        trailingContent = {
            if (result is UpdateCheck.Available) {
                Button(onClick = { openLink(context, result.url) }) { Text(stringResource(R.string.update_download, result.version)) }
            } else {
                OutlinedButton(
                    enabled = !checking,
                    onClick = {
                        checking = true
                        scope.launch {
                            check = Updates.check(current)
                            checking = false
                        }
                    },
                ) { Text(stringResource(R.string.update_check)) }
            }
        },
    )
}
