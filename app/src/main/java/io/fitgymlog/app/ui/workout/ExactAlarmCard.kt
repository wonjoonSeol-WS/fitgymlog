package io.fitgymlog.app.ui.workout

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.fitgymlog.app.R
import io.fitgymlog.app.catching
import io.fitgymlog.app.workout.RestAlarm

/**
 * Asks once a workout for Alarms & reminders, which Android 14 and later leave off for new installs. Without it the rest
 * buzz still comes, but deep sleep can hold it back a few minutes, so the card says why it matters and steps aside.
 */
@Composable
fun ExactAlarmCard() {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(RestAlarm.canBeExact(context)) }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allowed = RestAlarm.canBeExact(context) }
    if (allowed || dismissed) return

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.exact_alarm_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.exact_alarm_text), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        val settings = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())
                        catching { context.startActivity(settings) }
                    },
                ) { Text(stringResource(R.string.exact_alarm_allow)) }
                TextButton(onClick = { dismissed = true }) { Text(stringResource(R.string.exact_alarm_not_now)) }
            }
        }
    }
}
