package io.fitgymlog.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import io.fitgymlog.watchlink.WatchCommand
import kotlinx.coroutines.launch

/** Phone updates arrive through [StateListenerService] whether this is open or not, so the screen only collects them. */
class WatchActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        OngoingWorkout.sync(this, PhoneLink.state.value?.workout)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Wakes a phone app that hasn't run since it restarted, so it publishes.
        if (savedInstanceState == null) lifecycleScope.launch { PhoneLink.send(this@WatchActivity, WatchCommand.Hello) }
        setContent {
            WatchTheme {
                WatchApp(send = { command -> lifecycleScope.launch { PhoneLink.send(this@WatchActivity, command) } })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { PhoneLink.load(this@WatchActivity) }
    }
}
