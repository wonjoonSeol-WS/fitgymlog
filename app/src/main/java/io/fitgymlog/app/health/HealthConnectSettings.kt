package io.fitgymlog.app.health

import android.content.Context
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient

/** Health Connect's page for one app needs GRANT_RUNTIME_PERMISSIONS, which only the system holds, so this opens its home. */
fun openHealthConnect(context: Context) {
    runCatching { context.startActivity(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
