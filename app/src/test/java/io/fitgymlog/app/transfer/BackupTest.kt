package io.fitgymlog.app.transfer

import io.fitgymlog.app.model.Routine
import io.fitgymlog.app.model.RoutineExercise
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutExercise
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.model.WorkoutSource
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private val workout = Workout(
        title = "Day A",
        startedAt = Instant.parse("2026-10-19T10:38:00Z"),
        endedAt = Instant.parse("2026-10-19T13:05:00Z"),
        notes = "felt strong",
        source = WorkoutSource.HEVY,
        exercises = listOf(
            WorkoutExercise(
                key = 1,
                exerciseId = "squat_barbell",
                restSeconds = 180,
                notes = "belt",
                sets = listOf(
                    WorkoutSet(type = SetType.WARMUP, weightKg = 40.0, reps = 8, completedAt = Instant.parse("2026-10-19T10:45:00Z")),
                    WorkoutSet(weightKg = null, reps = 8, rpe = 8.5),
                ),
            ),
            WorkoutExercise(
                key = 2,
                exerciseId = "treadmill",
                restSeconds = 0,
                sets = listOf(WorkoutSet(durationSeconds = 300, level = 8, completedAt = Instant.parse("2026-10-19T13:00:00Z"))),
            ),
        ),
    )
    private val routine = Routine(name = "Day A", exercises = listOf(RoutineExercise("squat_barbell", 5, 8, 60.0, 180)))

    @Test
    fun `workouts and routines survive a round trip unchanged`() {
        val backup = BackupFile(
            exportedAt = "2026-10-22T00:00:00Z",
            customExercises = listOf(BackupCustomExercise("custom-1", "Gym machine", "WEIGHT_REPS", listOf("https://example.com"))),
            routines = listOf(routine.toBackup()),
            workouts = listOf(workout.toBackup()),
        )

        val decoded = BackupFile.decode(BackupFile.encode(backup))

        assertEquals(workout, decoded.workouts.single().toWorkout())
        assertEquals(routine, decoded.routines.single().toRoutine())
        assertEquals(backup.customExercises, decoded.customExercises)
    }

    private val empty = BackupFile.encode(BackupFile(exportedAt = "x", customExercises = emptyList(), routines = emptyList(), workouts = emptyList()))

    @Test
    fun `recognizes its own files, with or without a byte order mark`() {
        assertTrue(BackupFile.looksLike(empty))
        assertTrue(BackupFile.looksLike("\uFEFF" + empty))
        assertEquals("x", BackupFile.decode("\uFEFF" + empty).exportedAt)
    }

    @Test
    fun `reports a backup from a newer app version as newer, not as damaged`() {
        val newer = empty.replace("\"version\": ${BackupFile.VERSION}", "\"version\": ${BackupFile.VERSION + 1}")

        val error = assertThrows(TransferException::class.java) { BackupFile.decode(newer) }

        assertEquals(TransferFailure.NEWER_BACKUP, error.failure)
    }

    @Test
    fun `reports truncated or mistyped files as damaged backups`() {
        val truncated = assertThrows(TransferException::class.java) { BackupFile.decode(empty.dropLast(10)) }
        val mistyped = assertThrows(TransferException::class.java) { BackupFile.decode(empty.replace("\"routines\": []", "\"routines\": 3")) }

        assertEquals(TransferFailure.MALFORMED_BACKUP, truncated.failure)
        assertEquals(TransferFailure.MALFORMED_BACKUP, mistyped.failure)
    }
}
