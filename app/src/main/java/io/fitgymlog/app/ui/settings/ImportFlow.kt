package io.fitgymlog.app.ui.settings

import android.net.Uri
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.fitgymlog.app.AppContainer
import io.fitgymlog.app.R
import io.fitgymlog.app.container
import io.fitgymlog.app.transfer.ExportFormat
import io.fitgymlog.app.transfer.ImportSummary
import io.fitgymlog.app.transfer.PendingImport
import io.fitgymlog.app.transfer.UnrecognizedFileException
import io.fitgymlog.app.ui.exercise.ExercisePickerDialog
import io.fitgymlog.app.ui.localizedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Import is two steps: read and match the file, then let the user decide what each unrecognized
 * exercise name is (one of ours, or a new custom exercise) before anything is written.
 */
@Stable
class ImportFlow(private val app: AppContainer, private val messages: Messages) {
    data class Messages(val unrecognized: String, val failed: (String) -> String, val exported: String)

    var pending by mutableStateOf<PendingImport?>(null)
        private set
    val choices = mutableStateMapOf<String, String?>()
    var mapping by mutableStateOf<String?>(null)
    var summary by mutableStateOf<ImportSummary?>(null)
    var message by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set

    fun read(uri: Uri) = launchTask {
        val read = app.transfer.read(uri)
        withContext(Dispatchers.Main) {
            choices.clear()
            pending = read
        }
    }

    fun confirm() {
        val current = pending ?: return
        pending = null
        launchTask {
            val result = app.transfer.apply(current, choices.toMap())
            withContext(Dispatchers.Main) { summary = result }
        }
    }

    fun cancel() {
        pending = null
    }

    fun export(uri: Uri, format: ExportFormat) = launchTask {
        app.transfer.export(uri, format)
        withContext(Dispatchers.Main) { message = messages.exported }
    }

    private fun launchTask(block: suspend CoroutineScope.() -> Unit) {
        busy = true
        app.scope.launch {
            try {
                block()
            } catch (e: UnrecognizedFileException) {
                withContext(Dispatchers.Main) { message = messages.unrecognized }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { message = messages.failed(e.message.orEmpty()) }
            } finally {
                withContext(Dispatchers.Main) { busy = false }
            }
        }
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
                                    val choice = choices[name]
                                    val target = choice?.let { id -> exercises.firstOrNull { it.id == id } }
                                    Column(Modifier.clickable { mapping = name }.padding(vertical = 6.dp)) {
                                        Text(name, style = MaterialTheme.typography.bodyLarge)
                                        Text(
                                            target?.let { stringResource(R.string.import_maps_to, it.localizedName()) }
                                                ?: stringResource(R.string.import_new_custom),
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
                    Text(
                        stringResource(
                            R.string.import_backup_text,
                            current.backup.workouts.size,
                            current.backup.routines.size,
                            current.backup.customExercises.size,
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
                    choices[name] = id
                    mapping = null
                },
                onCreate = { _ ->
                    choices.remove(name)
                    mapping = null
                },
                onDismiss = { mapping = null },
            )
        }

        summary?.let { result ->
            AlertDialog(
                onDismissRequest = { summary = null },
                title = { Text(stringResource(R.string.import_done_title)) },
                text = { Text(stringResource(R.string.import_done_text, result.added, result.skipped, result.newExercises)) },
                confirmButton = { TextButton(onClick = { summary = null }) { Text(stringResource(R.string.close)) } },
            )
        }

        message?.let { text ->
            AlertDialog(
                onDismissRequest = { message = null },
                text = { Text(text) },
                confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(R.string.close)) } },
            )
        }
    }
}

@Composable
fun rememberImportFlow(): ImportFlow {
    val context = LocalContext.current
    val resources = LocalResources.current
    val unrecognized = stringResource(R.string.import_unrecognized)
    val exported = stringResource(R.string.export_done)
    return remember {
        ImportFlow(
            context.container,
            ImportFlow.Messages(
                unrecognized = unrecognized,
                failed = { reason -> resources.getString(R.string.transfer_failed, reason) },
                exported = exported,
            ),
        )
    }
}
