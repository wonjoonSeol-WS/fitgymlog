package io.fitgymlog.app.ui.settings

import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.fitgymlog.app.AppContainer
import io.fitgymlog.app.R
import io.fitgymlog.app.container
import io.fitgymlog.app.transfer.ExerciseChoice
import io.fitgymlog.app.transfer.ExportFormat
import io.fitgymlog.app.transfer.ImportSummary
import io.fitgymlog.app.transfer.MAX_IMPORT_MEGABYTES
import io.fitgymlog.app.transfer.PendingImport
import io.fitgymlog.app.transfer.TransferFailure
import io.fitgymlog.app.transfer.transferFailure
import io.fitgymlog.app.ui.exercise.ExercisePickerDialog
import io.fitgymlog.app.ui.exercise.kindLabel
import io.fitgymlog.app.ui.localizedName
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Stable
class ImportFlow(private val app: AppContainer) : ViewModel() {
    private class Notice(@param:StringRes val text: Int, vararg val args: Any)

    private var pending by mutableStateOf<PendingImport?>(null)
    private val choices = mutableStateMapOf<String, ExerciseChoice>()
    private var mapping by mutableStateOf<String?>(null)
    private var summary by mutableStateOf<ImportSummary?>(null)
    private var notice by mutableStateOf<Notice?>(null)

    fun read(uri: Uri) = launchTask {
        val read = app.transfer.read(uri)
        choices.clear()
        pending = read
    }

    fun export(uri: Uri, format: ExportFormat) = launchTask {
        app.transfer.export(uri, format)
        notice = Notice(R.string.export_done)
    }

    private fun confirm() {
        val current = pending ?: return
        pending = null
        val chosen = choices.toMap()
        launchTask { summary = app.transfer.apply(current, chosen) }
    }

    private fun cancel() {
        pending = null
    }

    private fun launchTask(block: suspend () -> Unit) {
        app.scope.launch(Dispatchers.Main) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "import or export failed", e)
                notice = failureNotice(e.transferFailure())
            }
        }
    }

    private fun failureNotice(failure: TransferFailure): Notice = when (failure) {
        TransferFailure.UNRECOGNIZED -> Notice(R.string.import_unrecognized)
        TransferFailure.NEWER_BACKUP -> Notice(R.string.transfer_error_newer_backup)
        TransferFailure.MALFORMED_BACKUP -> Notice(R.string.transfer_error_malformed_backup)
        TransferFailure.TOO_LARGE -> Notice(R.string.transfer_error_too_large, MAX_IMPORT_MEGABYTES)
        TransferFailure.IO -> Notice(R.string.transfer_error_io)
        TransferFailure.UNKNOWN -> Notice(R.string.transfer_error_unknown)
    }

    @Composable
    fun Dialogs() {
        val exercises by app.exercises.exercises.collectAsStateWithLifecycle()
        when (val current = pending) {
            is PendingImport.Csv -> AlertDialog(
                onDismissRequest = ::cancel,
                title = { Text(stringResource(R.string.import_review_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(pluralStringResource(R.plurals.import_found_workouts, current.workouts.size, current.workouts.size))
                        if (current.errors.isNotEmpty()) {
                            Text(
                                pluralStringResource(R.plurals.import_unreadable_rows, current.errors.size, current.errors.size),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (current.unmatched.isNotEmpty()) {
                            Text(stringResource(R.string.import_unmatched_help))
                            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                                items(current.unmatched) { name ->
                                    val outcome = when (val choice = choices[name]) {
                                        is ExerciseChoice.Existing -> exercises.firstOrNull { it.id == choice.id }
                                            ?.let { stringResource(R.string.import_maps_to, it.localizedName()) }
                                        is ExerciseChoice.Create -> stringResource(R.string.import_creates, choice.name, kindLabel(choice.kind))
                                        null -> null
                                    }
                                    Column(Modifier.clickable { mapping = name }.padding(vertical = 6.dp)) {
                                        Text(name, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            outcome ?: stringResource(R.string.import_new_custom),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = ::confirm) { Text(stringResource(R.string.import_action)) } },
                dismissButton = { TextButton(onClick = ::cancel) { Text(stringResource(R.string.cancel)) } },
            )
            is PendingImport.Backup -> AlertDialog(
                onDismissRequest = ::cancel,
                title = { Text(stringResource(R.string.import_backup_title)) },
                text = {
                    val backup = current.backup
                    Text(
                        stringResource(
                            R.string.import_backup_contents,
                            pluralStringResource(R.plurals.backup_workouts, backup.workouts.size, backup.workouts.size),
                            pluralStringResource(R.plurals.backup_routines, backup.routines.size, backup.routines.size),
                            pluralStringResource(R.plurals.backup_custom_exercises, backup.customExercises.size, backup.customExercises.size),
                        ),
                    )
                },
                confirmButton = { TextButton(onClick = ::confirm) { Text(stringResource(R.string.import_action)) } },
                dismissButton = { TextButton(onClick = ::cancel) { Text(stringResource(R.string.cancel)) } },
            )
            null -> Unit
        }

        mapping?.let { name ->
            ExercisePickerDialog(
                exercises = exercises,
                onPick = { id ->
                    choices[name] = ExerciseChoice.Existing(id)
                    mapping = null
                },
                onCreate = { new ->
                    choices[name] = ExerciseChoice.Create(new.name, new.kind, new.link)
                    mapping = null
                },
                onDismiss = { mapping = null },
            )
        }

        summary?.let { result ->
            AlertDialog(
                onDismissRequest = { summary = null },
                title = { Text(stringResource(R.string.import_done_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(pluralStringResource(R.plurals.import_done_added, result.added, result.added))
                        if (result.skipped > 0) {
                            Text(pluralStringResource(R.plurals.import_done_skipped, result.skipped, result.skipped))
                        }
                        if (result.newExercises > 0) {
                            Text(pluralStringResource(R.plurals.import_done_new_exercises, result.newExercises, result.newExercises))
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { summary = null }) { Text(stringResource(R.string.close)) } },
            )
        }

        notice?.let { shown ->
            AlertDialog(
                onDismissRequest = { notice = null },
                text = { Text(stringResource(shown.text, *shown.args)) },
                confirmButton = { TextButton(onClick = { notice = null }) { Text(stringResource(R.string.close)) } },
            )
        }
    }

    private companion object {
        const val TAG = "ImportFlow"
    }
}

/** Kept in a ViewModel so a pending import, its choices and the result survive a fold or rotation. */
@Composable
fun rememberImportFlow(): ImportFlow {
    val container = LocalContext.current.container
    val activity = checkNotNull(LocalActivity.current as? ComponentActivity)
    return viewModel(viewModelStoreOwner = activity) { ImportFlow(container) }
}
