package io.fitgymlog.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GymDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), GymDatabase::class.java)

    @Test
    fun aVersionOneWorkoutKeepsEverySetAfterTheLevelColumnIsAdded() {
        helper.createDatabase(NAME, 1).use { db ->
            db.execSQL(
                "INSERT INTO workout (id, uuid, updatedAt, title, startedAt, endedAt, notes, routineId, source, focus, lastActivityAt) " +
                    "VALUES (1, 'w-1', 2000, 'Day A', 1000, 2000, '', NULL, 'APP', 0, 2000)",
            )
            db.execSQL("INSERT INTO workout_exercise (id, workoutId, position, `key`, exerciseId, restSeconds, notes) VALUES (1, 1, 0, 1, 'squat_barbell', 120, '')")
            db.execSQL(
                "INSERT INTO workout_set (id, workoutExerciseId, position, type, weightKg, reps, targetReps, rpe, distanceMeters, durationSeconds, completedAt) " +
                    "VALUES (1, 1, 0, 'NORMAL', 60.0, 5, 5, NULL, NULL, NULL, 1500)",
            )
        }

        helper.runMigrationsAndValidate(NAME, 2, true).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, GymDatabase::class.java, NAME).build()
        try {
            val set = runBlocking { database.workouts().allFinished() }.single().toWorkout().exercises.single().sets.single()
            assertEquals(60.0, set.weightKg!!, 0.0)
            assertEquals(5, set.reps)
            assertEquals(1500L, set.completedAt?.toEpochMilli())
            assertNull(set.level)
        } finally {
            database.close()
        }
    }

    @Test
    fun aVersionTwoRoutineKeepsItsPlanWithNoCardioTargetAfterThoseColumnsAreAdded() {
        helper.createDatabase(NAME, 2).use { db ->
            db.execSQL("INSERT INTO routine (id, uuid, updatedAt, name, position) VALUES (1, 'r-1', 1000, 'Day A', 0)")
            db.execSQL(
                "INSERT INTO routine_exercise (id, routineId, position, exerciseId, sets, reps, weightKg, restSeconds) " +
                    "VALUES (1, 1, 0, 'squat_barbell', 3, 5, 60.0, 180)",
            )
        }

        helper.runMigrationsAndValidate(NAME, 3, true).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, GymDatabase::class.java, NAME).build()
        try {
            val planned = runBlocking { database.routines().allNow() }.single().toRoutine().exercises.single()
            assertEquals(listOf(3, 5, 180), listOf(planned.sets, planned.reps, planned.restSeconds))
            assertNull(planned.durationSeconds)
            assertNull(planned.level)
        } finally {
            database.close()
        }
    }

    @Test
    fun aVersionThreeTreadmillRoutineAndRunKeepTheirTimeAndInclineWithNoSpeedAfterTheSpeedColumnsAreAdded() {
        helper.createDatabase(NAME, 3).use { db ->
            db.execSQL("INSERT INTO routine (id, uuid, updatedAt, name, position) VALUES (1, 'r-1', 1000, 'Cardio', 0)")
            db.execSQL(
                "INSERT INTO routine_exercise (id, routineId, position, exerciseId, sets, reps, weightKg, restSeconds, durationSeconds, level) " +
                    "VALUES (1, 1, 0, 'treadmill', 2, NULL, NULL, 30, 1200, 12)",
            )
            db.execSQL(
                "INSERT INTO workout (id, uuid, updatedAt, title, startedAt, endedAt, notes, source, focus, lastActivityAt) " +
                    "VALUES (1, 'w-1', 1000, 'Cardio', 1000, 4000, '', 'APP', 0, 4000)",
            )
            db.execSQL("INSERT INTO workout_exercise (id, workoutId, position, `key`, exerciseId, restSeconds, notes) VALUES (1, 1, 0, 1, 'treadmill', 0, '')")
            db.execSQL(
                "INSERT INTO workout_set (id, workoutExerciseId, position, type, durationSeconds, level, completedAt) " +
                    "VALUES (1, 1, 0, 'NORMAL', 900, 7, 3000)",
            )
        }

        helper.runMigrationsAndValidate(NAME, 4, true).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, GymDatabase::class.java, NAME).build()
        try {
            val planned = runBlocking { database.routines().allNow() }.single().toRoutine().exercises.single()
            val run = runBlocking { database.workouts().finished().first() }.single().toWorkout().exercises.single().sets.single()
            assertEquals(listOf<Any?>(2, 30, 1200, 12, null), listOf(planned.sets, planned.restSeconds, planned.durationSeconds, planned.level, planned.speedKmh))
            assertEquals(listOf<Any?>(900, 7, null), listOf(run.durationSeconds, run.level, run.speedKmh))
        } finally {
            database.close()
        }
    }

    @Test
    fun aDatabaseFromTheLastReleaseKeepsItsWorkoutsAndRoutinesAfterEveryStepToNow() {
        helper.createDatabase(NAME, 2).use { db ->
            db.execSQL(
                "INSERT INTO workout (id, uuid, updatedAt, title, startedAt, endedAt, notes, routineId, source, focus, lastActivityAt) " +
                    "VALUES (1, 'w-1', 2000, 'Day A', 1000, 2000, '', NULL, 'APP', 0, 2000)",
            )
            db.execSQL("INSERT INTO workout_exercise (id, workoutId, position, `key`, exerciseId, restSeconds, notes) VALUES (1, 1, 0, 1, 'squat_barbell', 120, '')")
            db.execSQL(
                "INSERT INTO workout_set (id, workoutExerciseId, position, type, weightKg, reps, targetReps, rpe, distanceMeters, durationSeconds, completedAt, level) " +
                    "VALUES (1, 1, 0, 'NORMAL', 62.5, 4, 5, NULL, NULL, NULL, 1500, NULL)",
            )
            db.execSQL("INSERT INTO routine (id, uuid, updatedAt, name, position) VALUES (1, 'r-1', 1000, 'Day A', 0)")
            db.execSQL("INSERT INTO routine_exercise (id, routineId, position, exerciseId, sets, reps, weightKg, restSeconds) VALUES (1, 1, 0, 'squat_barbell', 3, 5, 60.0, 180)")
        }

        helper.runMigrationsAndValidate(NAME, 4, true).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, GymDatabase::class.java, NAME).build()
        try {
            val set = runBlocking { database.workouts().allFinished() }.single().toWorkout().exercises.single().sets.single()
            val planned = runBlocking { database.routines().allNow() }.single().toRoutine().exercises.single()
            assertEquals(listOf<Any?>(62.5, 4, 5, 1500L, null, null), listOf(set.weightKg, set.reps, set.targetReps, set.completedAt?.toEpochMilli(), set.level, set.speedKmh))
            assertEquals(listOf<Any?>(3, 5, 60.0, 180, null, null, null), listOf(planned.sets, planned.reps, planned.weightKg, planned.restSeconds, planned.durationSeconds, planned.level, planned.speedKmh))
        } finally {
            database.close()
        }
    }

    private companion object {
        const val NAME = "migration-test"
    }
}
