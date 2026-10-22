package io.fitgymlog.app.ui.settings

import android.text.format.DateUtils
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
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
import io.fitgymlog.app.LOG_TAG
import io.fitgymlog.app.R
import io.fitgymlog.app.catching
import io.fitgymlog.app.container
import io.fitgymlog.app.sync.DriveAuth
import io.fitgymlog.app.sync.SyncFailure
import io.fitgymlog.app.sync.SyncStatus
import kotlinx.coroutines.launch

/** Google Drive sync: connect once per phone, then it runs on its own; this shows how it is going. */
@Composable
fun SyncSection() {
    val app = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val status by app.sync.status.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    var connectError by remember { mutableStateOf<Int?>(null) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (!app.sync.finishConnect(result.data)) connectError = R.string.sync_sign_in_cancelled
    }

    fun connect() {
        connectError = null
        scope.launch {
            val start = catching { app.sync.connect() }.getOrElse { error ->
                Log.w(LOG_TAG, "Connect failed", error)
                connectError = SyncFailure.of(error).label
                return@launch
            }
            when (start) {
                is DriveAuth.Start.NeedsConsent -> consent.launch(IntentSenderRequest.Builder(start.intent.intentSender).build())
                is DriveAuth.Start.Granted -> app.sync.enableAndSync(start.token)
            }
        }
    }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.sync_title), style = MaterialTheme.typography.titleMedium)
        Text(
            text = when (val current = status) {
                SyncStatus.Off -> stringResource(R.string.sync_off_text)
                SyncStatus.Syncing -> stringResource(R.string.sync_syncing)
                SyncStatus.NeedsSignIn -> stringResource(R.string.sync_needs_sign_in)
                is SyncStatus.Failed -> stringResource(R.string.sync_failed, stringResource(current.reason.label))
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
        connectError?.let { Text(stringResource(R.string.sync_connect_failed, stringResource(it)), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (status) {
                SyncStatus.Off -> Button(onClick = ::connect, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.sync_connect)) }
                SyncStatus.NeedsSignIn -> Button(onClick = ::connect, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.sync_sign_in_again)) }
                else -> OutlinedButton(
                    onClick = { app.sync.requestSync(0) },
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

@get:StringRes
private val SyncFailure.label: Int
    get() = when (this) {
        SyncFailure.NETWORK -> R.string.sync_failure_network
        SyncFailure.SIGN_IN_REJECTED -> R.string.sync_failure_sign_in
        SyncFailure.NEWER_APP_FORMAT -> R.string.sync_failure_newer_app
        SyncFailure.CORRUPT_SYNC_FILE -> R.string.sync_failure_corrupt_file
        SyncFailure.OTHER -> R.string.sync_failure_other
    }
