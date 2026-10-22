package io.gymlog.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.gymlog.app.ui.AppNavigation
import io.gymlog.app.ui.GymLogTheme
import io.gymlog.app.workout.Notifications
import io.gymlog.app.workout.WorkoutService
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifications.ensureChannels(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        var ready by mutableStateOf(false)
        var resume by mutableStateOf(false)
        lifecycleScope.launch {
            container.workouts.awaitLoaded()
            resume = container.workouts.active.value != null
            if (resume) WorkoutService.start(this@MainActivity)
            ready = true
        }
        setContent {
            GymLogTheme {
                if (ready) AppNavigation(openActiveWorkout = resume)
            }
        }
    }
}
