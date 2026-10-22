package io.fitgymlog.app.ui.guide

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.fitgymlog.app.R
import io.fitgymlog.app.container
import io.fitgymlog.app.ui.NumberStyles
import io.fitgymlog.app.ui.ReadableWidth
import io.fitgymlog.app.ui.lift
import io.fitgymlog.app.workout.BandText
import io.fitgymlog.app.workout.Notifications
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val GALAXY_WEARABLE = "com.samsung.android.app.watchmanager"
private const val TEST_DELAY_MILLIS = 5_000L

/** Three things make the band useful: notifications allowed, the app switched on in Galaxy Wearable, and a test buzz. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BandGuideScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.container
    var notificationsAllowed by remember { mutableStateOf(notificationsAllowed(context)) }
    var testSent by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { notificationsAllowed = notificationsAllowed(context) }
    val requestNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsAllowed = notificationsAllowed(context)
        val askAgain = context.findActivity()?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.POST_NOTIFICATIONS) } == true
        if (!notificationsAllowed && (granted || !askAgain)) openNotificationSettings(context)
    }
    val testTitle = stringResource(R.string.band_test_title)
    val testText = stringResource(R.string.band_test_text)

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(stringResource(R.string.band_guide_title), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        ReadableWidth(Modifier.padding(padding)) {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                item {
                    Step(1, stringResource(R.string.band_step_notifications), stringResource(R.string.band_step_notifications_text), done = notificationsAllowed) {
                        if (!notificationsAllowed) {
                            Button(
                                onClick = {
                                    if (permissionGranted(context)) {
                                        openNotificationSettings(context)
                                    } else {
                                        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(stringResource(R.string.allow))
                            }
                        }
                    }
                }
                item {
                    Step(2, stringResource(R.string.band_step_wearable), stringResource(R.string.band_step_wearable_text), done = false) {
                        OutlinedButton(onClick = { openGalaxyWearable(context) }, shape = RoundedCornerShape(12.dp)) {
                            Text(stringResource(R.string.open_galaxy_wearable))
                        }
                    }
                }
                item {
                    Step(3, stringResource(R.string.band_step_test), stringResource(R.string.band_step_test_text), done = testSent) {
                        Button(
                            onClick = {
                                testSent = true
                                val appContext = context.applicationContext
                                app.scope.launch {
                                    delay(TEST_DELAY_MILLIS)
                                    Notifications.postRestOver(appContext, BandText(testTitle, testText))
                                }
                            },
                            enabled = notificationsAllowed,
                            shape = RoundedCornerShape(12.dp),
                        ) { Text(stringResource(R.string.send_test_buzz)) }
                    }
                }
                item {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.lift.field, RoundedCornerShape(16.dp)).padding(16.dp),
                    ) {
                        Text(stringResource(R.string.band_buttons_title), style = MaterialTheme.typography.titleSmall)
                        ButtonRow("⏯", stringResource(R.string.band_button_play))
                        ButtonRow("⏭", stringResource(R.string.band_button_next))
                        ButtonRow("⏮", stringResource(R.string.band_button_previous))
                        Text(stringResource(R.string.band_music_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    Text(stringResource(R.string.band_quietbuzz_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Step(number: Int, title: String, text: String, done: Boolean, action: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(32.dp).background(if (done) MaterialTheme.lift.doneCheck else MaterialTheme.colorScheme.primary, CircleShape),
        ) {
            if (done) {
                Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
            } else {
                Text(number.toString(), style = NumberStyles.cell, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            action()
        }
    }
}

@Composable
private fun ButtonRow(symbol: String, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape).clearAndSetSemantics {},
        ) { Text(symbol, color = MaterialTheme.colorScheme.onPrimaryContainer) }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun notificationsAllowed(context: Context): Boolean =
    permissionGranted(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun permissionGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun openNotificationSettings(context: Context) {
    val notifications = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
    runCatching { context.startActivity(notifications.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { runCatching { context.startActivity(appDetails.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

private fun openGalaxyWearable(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(GALAXY_WEARABLE)
    val intent = launch ?: Intent(Intent.ACTION_VIEW, "market://details?id=$GALAXY_WEARABLE".toUri())
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}
