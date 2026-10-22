package io.fitgymlog.app.ui.settings

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.fitgymlog.app.R
import io.fitgymlog.app.container
import io.fitgymlog.app.sync.DriveAuth
import io.fitgymlog.app.sync.SyncStatus
import kotlinx.coroutines.launch

/** Google Drive sync: connect once per phone, then it runs on its own; this shows how it is going. */
@Composable
fun SyncSection() {
    val app = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val status by app.sync.status.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    var connectError by remember { mutableStateOf<String?>(null) }
    val cancelled = stringResource(R.string.sync_sign_in_cancelled)
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        scope.launch {
            if (!app.sync.finishConnect(result.data)) connectError = cancelled
        }
    }

    fun connect() {
        connectError = null
        scope.launch {
            runCatching { app.sync.connect() }
                .onSuccess { start ->
                    when (start) {
                        is DriveAuth.Start.NeedsConsent -> consent.launch(IntentSenderRequest.Builder(start.intent.intentSender).build())
                        is DriveAuth.Start.Granted -> app.sync.enableAndSync(start.token)
                    }
                }
                .onFailure { connectError = it.message ?: it.javaClass.simpleName }
        }
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.sync_title), style = MaterialTheme.typography.titleMedium)
        Text(
            text = when (val current = status) {
                SyncStatus.Off -> stringResource(R.string.sync_off_text)
                SyncStatus.Syncing -> stringResource(R.string.sync_syncing)
                SyncStatus.NeedsSignIn -> stringResource(R.string.sync_needs_sign_in)
                is SyncStatus.Failed -> stringResource(R.string.sync_failed, current.reason)
                is SyncStatus.Synced -> current.at?.let {
                    stringResource(R.string.sync_last, DateUtils.getRelativeTimeSpanString(it.toEpochMilli()).toString())
                } ?: stringResource(R.string.sync_on_text)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (status is SyncStatus.Failed || status is SyncStatus.NeedsSignIn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        settings.syncAccount?.takeIf { status != SyncStatus.Off }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        connectError?.let { Text(stringResource(R.string.sync_connect_failed, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (status) {
                SyncStatus.Off -> Button(onClick = ::connect, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.sync_connect)) }
                SyncStatus.NeedsSignIn -> Button(onClick = ::connect, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.sync_sign_in_again)) }
                else -> OutlinedButton(
                    onClick = { scope.launch { app.sync.sync() } },
                    enabled = status != SyncStatus.Syncing,
                    shape = RoundedCornerShape(12.dp),
                ) { Text(stringResource(R.string.sync_now)) }
            }
            if (status != SyncStatus.Off) {
                TextButton(onClick = { app.sync.disconnect() }) { Text(stringResource(R.string.sync_turn_off)) }
            }
        }
    }
}
