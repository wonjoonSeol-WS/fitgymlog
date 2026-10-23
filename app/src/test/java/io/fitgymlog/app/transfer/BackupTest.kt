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
                    WorkoutSet(weightKg = 60.0, reps = 4, targetReps = 5, completedAt = Instant.parse("2026-10-19T10:55:00Z")),
                ),
            ),
            WorkoutExercise(
                key = 2,
                exerciseId = "treadmill",
                restSeconds = 0,
                sets = listOf(WorkoutSet(durationSeconds = 300, level = 8, speedKmh = 7.5, completedAt = Instant.parse("2026-10-19T13:00:00Z"))),
            ),
        ),
    )
    private val routine = Routine(
        name = "Day A",
        exercises = listOf(RoutineExercise("squat_barbell", 5, 8, 60.0, 180), RoutineExercise("treadmill", 1, null, null, 0, durationSeconds = 1_200, level = 12, speedKmh = 6.5)),
    )

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

    @Test
    fun `a backup from before planned reps were kept reads them as unknown`() {
        val older = BackupFile.encode(BackupFile(exportedAt = "x", customExercises = emptyList(), routines = emptyList(), workouts = listOf(workout.toBackup())))
            .replace(Regex(""",\s*"targetReps":\s*\d+"""), "")

        val sets = BackupFile.decode(older).workouts.single().toWorkout().exercises.first().sets

        assertEquals(listOf<Int?>(null, null, null), sets.map { it.targetReps })
        assertEquals(listOf(8, 8, 4), sets.map { it.reps })
    }

    @Test
    fun `a backup from before routines planned cardio reads it as open`() {
        val older = BackupFile.encode(BackupFile(exportedAt = "x", customExercises = emptyList(), routines = listOf(routine.toBackup()), workouts = emptyList()))
            .replace(Regex(""",\s*"(durationSeconds|level|speedKmh)":\s*([\d.]+|null)"""), "")

        val planned = BackupFile.decode(older).routines.single().toRoutine().exercises

        assertEquals(listOf(null, null), planned.map { it.durationSeconds })
        assertEquals(listOf(null, null), planned.map { it.level })
        assertEquals(listOf(null, null), planned.map { it.speedKmh })
        assertEquals(listOf(5, 1), planned.map { it.sets })
    }

    @Test
    fun `reads a version 2 file by its field names, and a version 1 file too`() {
        val file = """
            {"format":"fitgymlog-backup","version":2,"exportedAt":"2026-10-22T00:00:00Z","customExercises":[],
             "routines":[{"name":"Run","exercises":[
               {"exerciseId":"treadmill","sets":2,"reps":null,"weightKg":null,"restSeconds":30,"durationSeconds":1200,"level":12,"speedKmh":6.5}]}],
             "workouts":[{"title":"Day A","startedAt":"2026-10-19T10:38:00Z","endedAt":"2026-10-19T11:00:00Z","notes":"","source":"APP","exercises":[
               {"exerciseId":"treadmill","restSeconds":0,"notes":"","sets":[
                 {"type":"NORMAL","durationSeconds":600,"completedAt":"2026-10-19T10:50:00Z","level":4,"targetReps":null,"speedKmh":9.5}]}]}]}
        """

        val decoded = BackupFile.decode(file)
        val older = BackupFile.decode(file.replace("\"version\":2", "\"version\":1"))

        assertEquals(
            RoutineExercise("treadmill", sets = 2, reps = null, weightKg = null, restSeconds = 30, durationSeconds = 1_200, level = 12, speedKmh = 6.5),
            decoded.routines.single().toRoutine().exercises.single(),
        )
        assertEquals(
            WorkoutSet(durationSeconds = 600, level = 4, speedKmh = 9.5, completedAt = Instant.parse("2026-10-19T10:50:00Z")),
            decoded.workouts.single().toWorkout().exercises.single().sets.single(),
        )
        assertEquals(decoded.copy(version = 1), older)
        assertEquals(2, BackupFile.VERSION)
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
