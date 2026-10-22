package io.gymlog.app

import android.app.Application
import android.content.Context
import io.gymlog.app.data.ExerciseRepository
import io.gymlog.app.data.GymDatabase
import io.gymlog.app.data.RoutineRepository
import io.gymlog.app.data.SettingsRepository
import io.gymlog.app.data.WorkoutRepository
import io.gymlog.app.health.HealthConnectWriter
import io.gymlog.app.transfer.TransferService
import io.gymlog.app.workout.WorkoutActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class GymLogApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val database = GymDatabase.create(context)
    val settings = SettingsRepository(context)
    val exercises = ExerciseRepository(context, database.customExercises(), scope)
    val routines = RoutineRepository(database.routines())
    val workouts = WorkoutRepository(database.workouts(), scope)
    val health = HealthConnectWriter(context)
    val actions = WorkoutActions(context, workouts, exercises, settings, health)
    val transfer = TransferService(context, exercises, workouts, routines, settings)
}

val Context.container: AppContainer get() = (applicationContext as GymLogApp).container
