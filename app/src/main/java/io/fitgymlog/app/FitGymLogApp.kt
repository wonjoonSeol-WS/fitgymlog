package io.fitgymlog.app

import android.app.Application
import android.content.Context
import android.util.Log
import io.fitgymlog.app.data.ExerciseRepository
import io.fitgymlog.app.data.GymDatabase
import io.fitgymlog.app.data.RoutineRepository
import io.fitgymlog.app.data.SettingsRepository
import io.fitgymlog.app.data.WorkoutRepository
import io.fitgymlog.app.health.HealthConnectWriter
import io.fitgymlog.app.sync.SyncEngine
import io.fitgymlog.app.sync.SyncStore
import io.fitgymlog.app.transfer.TransferService
import io.fitgymlog.app.workout.WorkoutActions
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

class FitGymLogApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(context: Context) {
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, error -> Log.e(LOG_TAG, "Background work failed", error) },
    )
    private val database = GymDatabase.create(context)
    val settings = SettingsRepository(context)
    val exercises = ExerciseRepository(context, database.customExercises(), database.exerciseLinks(), database.tombstones(), scope)
    val routines = RoutineRepository(database.routines(), database.tombstones())
    val workouts = WorkoutRepository(database.workouts(), database.tombstones(), scope)
    val health = HealthConnectWriter(context)
    val actions = WorkoutActions(context, workouts, exercises, settings, health)
    val transfer = TransferService(context, exercises, workouts, routines, settings)
    val sync = SyncEngine(context, SyncStore(database), settings, scope)

    /** Runs [block] in the app scope, so a write finishes even if the screen that asked for it has gone. */
    suspend fun <T> inAppScope(block: suspend () -> T): T = scope.async { block() }.await()
}

val Context.container: AppContainer get() = (applicationContext as FitGymLogApp).container

const val LOG_TAG = "FitGymLog"
