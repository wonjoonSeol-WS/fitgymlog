package io.fitgymlog.app.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

    private companion object {
        const val NAME = "migration-test"
    }
}
