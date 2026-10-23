package io.fitgymlog.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import io.fitgymlog.app.ui.AppNavigation
import io.fitgymlog.app.ui.FitGymLogTheme
import io.fitgymlog.app.workout.Notifications
import io.fitgymlog.app.workout.WorkoutService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        container.sync.requestSync()
    }

    private var openCheckIn by mutableStateOf(false)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action in PRIVACY_POLICY_ACTIONS) {
            openPrivacyPolicy()
            finish()
            return
        }
        enableEdgeToEdge()
        Notifications.ensureChannels(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (savedInstanceState == null) openCheckIn = intent?.getBooleanExtra(EXTRA_CHECK_IN, false) == true
        var ready by mutableStateOf(false)
        var resume by mutableStateOf(false)
        lifecycleScope.launch {
            container.workouts.awaitLoaded()
            resume = container.workouts.active.value != null
            if (resume) WorkoutService.start(this@MainActivity)
            ready = true
        }
        setContent {
            FitGymLogTheme {
                if (ready) AppNavigation(openActiveWorkout = resume, openCheckIn = openCheckIn, onCheckInOpened = { openCheckIn = false })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action in PRIVACY_POLICY_ACTIONS) openPrivacyPolicy()
        if (intent.getBooleanExtra(EXTRA_CHECK_IN, false)) openCheckIn = true
    }

    private fun openPrivacyPolicy() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, PRIVACY_POLICY_URL.toUri()))
        } catch (e: ActivityNotFoundException) {
            Log.w(LOG_TAG, "No browser to show the privacy policy", e)
        }
    }

    companion object {
        const val EXTRA_CHECK_IN = "io.fitgymlog.app.extra.CHECK_IN"
        private const val PRIVACY_POLICY_URL = "https://wonjoonseol.github.io/fitgymlog/privacy.html"

        // Health Connect opens these to show the privacy policy: the first up to Android 13, the second from Android 14.
        private val PRIVACY_POLICY_ACTIONS = setOf("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE", Intent.ACTION_VIEW_PERMISSION_USAGE)
    }
}
