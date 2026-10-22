package io.fitgymlog.app.transfer

import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.model.WorkoutSource
import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvImportTest {
    private val weighted = listOf(WorkoutSet(weightKg = 60.0, reps = 8))
    private val repsOnly = listOf(WorkoutSet(weightKg = 0.0, reps = 12), WorkoutSet(reps = 10))
    private val timed = listOf(WorkoutSet(durationSeconds = 60))

    private fun workout(title: String, vararg exercises: Pair<String, List<WorkoutSet>>) = ImportedWorkout(
        title = title,
        startedAt = LocalDateTime.of(2026, 10, 19, 19, 38),
        endedAt = null,
        notes = "",
        exercises = exercises.map { (name, sets) -> ImportedExercise(name, "", sets) },
    )

    private fun pending(vararg workouts: ImportedWorkout, unmatched: List<String>) = PendingImport.Csv(
        source = WorkoutSource.HEVY,
        workouts = workouts.toList(),
        errors = emptyList(),
        matched = mapOf("Squat (Barbell)" to "squat_barbell"),
        unmatched = unmatched,
    )

    private class Recorder {
        val created = mutableListOf<NewCustomExercise>()
        var saved: Map<String, String> = emptyMap()
    }

    private suspend fun runImport(
        pending: PendingImport.Csv,
        choices: Map<String, ExerciseChoice> = emptyMap(),
        alreadySaved: Set<String> = emptySet(),
    ): Pair<ImportSummary, Recorder> {
        val recorder = Recorder()
        val summary = importWorkouts(
            pending,
            choices,
            keepNew = { workouts -> workouts.filter { it.title !in alreadySaved } },
            createCustom = { recorder.created += it; "custom-${recorder.created.size}" },
            save = { workouts, ids ->
                recorder.saved = workouts.flatMap { it.exercises }.associate { it.name to ids.getValue(it.name) }
                workouts.size
            },
        )
        return summary to recorder
    }

    @Test
    fun `names that differ only in case or spacing become one custom exercise`() = runTest {
        val file = pending(
            workout("Day A", "Bench press" to weighted),
            workout("Day B", "Bench Press" to weighted, "bench-press" to weighted),
            unmatched = listOf("Bench press"),
        )

        val (summary, recorder) = runImport(file)

        assertEquals(listOf(NewCustomExercise("Bench press", ExerciseKind.WEIGHT_REPS, emptyList())), recorder.created)
        assertEquals(setOf("custom-1"), recorder.saved.values.toSet())
        assertEquals(1, summary.newExercises)
    }

    @Test
    fun `only workouts that will be added create custom exercises`() = runTest {
        val file = pending(
            workout("Old", "Zercher Squat" to weighted),
            workout("New", "Jefferson Curl" to weighted, "Squat (Barbell)" to weighted),
            unmatched = listOf("Zercher Squat", "Jefferson Curl"),
        )

        val (summary, recorder) = runImport(file, alreadySaved = setOf("Old"))

        assertEquals(listOf("Jefferson Curl"), recorder.created.map { it.name })
        assertEquals(mapOf("Jefferson Curl" to "custom-1", "Squat (Barbell)" to "squat_barbell"), recorder.saved)
        assertEquals(ImportSummary(added = 1, skipped = 1, newExercises = 1), summary)
    }

    @Test
    fun `infers the kind of a new exercise from its sets`() = runTest {
        val file = pending(
            workout("Day A", "Sled Push" to weighted, "Ring Dip" to repsOnly, "Hollow Hold" to timed),
            unmatched = listOf("Sled Push", "Ring Dip", "Hollow Hold"),
        )

        val (_, recorder) = runImport(file)

        assertEquals(
            listOf(ExerciseKind.WEIGHT_REPS, ExerciseKind.BODYWEIGHT_REPS, ExerciseKind.DURATION),
            recorder.created.map { it.kind },
        )
    }

    @Test
    fun `the name, kind and link entered in the dialog win over the imported ones`() = runTest {
        val file = pending(workout("Day A", "Hollow Hold" to timed), unmatched = listOf("Hollow Hold"))
        val choice = ExerciseChoice.Create("Hollow Body Hold", ExerciseKind.WEIGHT_REPS, "https://example.com/hollow")

        val (_, recorder) = runImport(file, choices = mapOf("Hollow Hold" to choice))

        assertEquals(
            listOf(NewCustomExercise("Hollow Body Hold", ExerciseKind.WEIGHT_REPS, listOf("https://example.com/hollow"))),
            recorder.created,
        )
    }

    @Test
    fun `a name matched to an existing exercise creates nothing`() = runTest {
        val file = pending(workout("Day A", "Bench press" to weighted, "Bench Press" to weighted), unmatched = listOf("Bench press"))

        val (summary, recorder) = runImport(file, choices = mapOf("Bench press" to ExerciseChoice.Existing("bench_press_barbell")))

        assertEquals(emptyList<NewCustomExercise>(), recorder.created)
        assertEquals(mapOf("Bench press" to "bench_press_barbell", "Bench Press" to "bench_press_barbell"), recorder.saved)
        assertEquals(0, summary.newExercises)
    }

    @Test
    fun `lists each unrecognized spelling group once for review`() {
        val matcher = ExerciseMatcher(mapOf("squat_barbell" to listOf("Squat (Barbell)")))
        val result = ParseResult(
            listOf(workout("Day A", "Bench press" to weighted, "squat (barbell)" to weighted), workout("Day B", "Bench Press" to weighted)),
            emptyList(),
        )

        val pending = pendingCsv(WorkoutSource.HEVY, result, matcher)

        assertEquals(mapOf("squat (barbell)" to "squat_barbell"), pending.matched)
        assertEquals(listOf("Bench press"), pending.unmatched)
    }
}
