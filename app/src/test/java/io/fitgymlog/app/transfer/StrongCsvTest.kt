package io.fitgymlog.app.transfer

import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.WorkoutSet
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrongCsvTest {
    /** The layout Hevy's importer accepts, as published in the gymruntohevy converter's strong.csv. */
    private val newer = """
        "Workout #";"Date";"Workout Name";"Duration (sec)";"Exercise Name";"Set Order";"Weight (kg)";"Reps";"RPE";"Distance (meters)";"Seconds";"Notes";"Workout Notes"
        "1";"2025-04-29 12:00:00";"Chest and Triceps";"600";"Bench Press (Barbell)";"1";"100.0";"10";"";"";"";"";""
        "1";"2025-04-29 12:00:00";"Chest and Triceps";"600";"Bench Press (Barbell)";"2";"100.0";"8";"";"";"";"";""
        "2";"2025-05-01 18:00:00";"Legs";"3600";"Squat (Barbell)";"1";"120";"5";"8";"";"";"";""
    """.trimIndent()

    /** Older exports keep the unit in its own column and list rest timers as rows. */
    private val older = """
        Date;Workout Name;Duration;Exercise Name;Set Order;Weight;Reps;Distance;Seconds;Notes;Workout Notes;RPE;Weight Unit
        2024-08-01 18:23:30;Push;1h 5m;Bench Press (Barbell);W;95;10;0;0;;;;lbs
        2024-08-01 18:23:30;Push;1h 5m;Bench Press (Barbell);1;185;5;0;0;;;;lbs
        2024-08-01 18:23:30;Push;1h 5m;Bench Press (Barbell);Rest Timer;0;0;0;120;;;;lbs
        2024-08-01 18:23:30;Push;1h 5m;Plank;1;0;0;0;60;;;;lbs
    """.trimIndent()

    @Test
    fun `reads the newer layout and groups by workout number`() {
        val result = StrongCsv.parse(newer)

        assertTrue(result.errors.isEmpty())
        assertEquals(2, result.workouts.size)
        val chest = result.workouts[0]
        assertEquals(LocalDateTime.of(2025, 4, 29, 12, 10), chest.endedAt)
        assertEquals(listOf(10, 8), chest.exercises.single().sets.map { it.reps })
        assertEquals(8.0, result.workouts[1].exercises.single().sets.single().rpe)
    }

    @Test
    fun `reads the older layout with pounds, warm-ups, text durations and timed sets`() {
        val workout = StrongCsv.parse(older).workouts.single()

        assertEquals(LocalDateTime.of(2024, 8, 1, 19, 28, 30), workout.endedAt)
        val bench = workout.exercises[0]
        assertEquals(listOf(SetType.WARMUP, SetType.NORMAL), bench.sets.map { it.type })
        assertEquals(83.915, bench.sets[1].weightKg!!, 0.001)
        val plank = workout.exercises[1].sets.single()
        assertNull(plank.reps)
        assertEquals(60, plank.durationSeconds)
    }

    @Test
    fun `skips rest timer rows without reporting them as errors`() {
        val result = StrongCsv.parse(older)

        assertTrue(result.errors.isEmpty())
        assertEquals(2, result.workouts.single().exercises[0].sets.size)
    }

    @Test
    fun `workouts with the same date and name but different numbers stay apart`() {
        val twice = """
            "Workout #";"Date";"Workout Name";"Duration (sec)";"Exercise Name";"Set Order";"Weight (kg)";"Reps";"RPE";"Distance (meters)";"Seconds";"Notes";"Workout Notes"
            "1";"2025-04-29 12:00:00";"Legs";"600";"Squat (Barbell)";"1";"100";"5";"";"";"";"";""
            "2";"2025-04-29 12:00:00";"Legs";"600";"Squat (Barbell)";"1";"110";"5";"";"";"";"";""
        """.trimIndent()

        val workouts = StrongCsv.parse(twice).workouts

        assertEquals(listOf(100.0, 110.0), workouts.map { it.exercises.single().sets.single().weightKg })
    }

    @Test
    fun `keeps kilograms as they are when the older layout names kg as the unit`() {
        val kilos = """
            Date;Workout Name;Duration;Exercise Name;Set Order;Weight;Reps;Distance;Seconds;Notes;Workout Notes;RPE;Weight Unit
            2024-08-01 18:23:30;Push;1h 5m;Bench Press (Barbell);1;100;5;0;0;;;;kg
        """.trimIndent()

        assertEquals(100.0, StrongCsv.parse(kilos).workouts.single().exercises.single().sets.single().weightKg)
    }

    @Test
    fun `reads exercise notes and keeps each distinct note of its sets`() {
        val notes = """
            "Workout #";"Date";"Workout Name";"Duration (sec)";"Exercise Name";"Set Order";"Weight (kg)";"Reps";"RPE";"Distance (meters)";"Seconds";"Notes";"Workout Notes"
            "1";"2025-04-29 12:00:00";"Legs";"600";"Squat (Barbell)";"1";"100";"5";"";"";"";"";""
            "1";"2025-04-29 12:00:00";"Legs";"600";"Squat (Barbell)";"2";"100";"5";"";"";"";"belt";""
            "1";"2025-04-29 12:00:00";"Legs";"600";"Squat (Barbell)";"3";"100";"5";"";"";"";"belt";""
            "1";"2025-04-29 12:00:00";"Legs";"600";"Squat (Barbell)";"4";"100";"5";"";"";"";"knee sleeves";""
        """.trimIndent()

        assertEquals("belt\nknee sleeves", StrongCsv.parse(notes).workouts.single().exercises.single().notes)
    }

    @Test
    fun `warm-up, drop and failure sets and exercise notes survive a round trip`() {
        val workout = ExportWorkout(
            title = "Push",
            startedAt = LocalDateTime.of(2026, 10, 19, 19, 38),
            endedAt = LocalDateTime.of(2026, 10, 19, 21, 5),
            notes = "",
            exercises = listOf(
                ImportedExercise(
                    name = "Bench Press (Barbell)",
                    notes = "pause at the chest",
                    sets = listOf(
                        WorkoutSet(type = SetType.WARMUP, weightKg = 40.0, reps = 10),
                        WorkoutSet(weightKg = 80.0, reps = 5),
                        WorkoutSet(weightKg = 80.0, reps = 5),
                        WorkoutSet(type = SetType.DROP, weightKg = 60.0, reps = 8),
                        WorkoutSet(type = SetType.FAILURE, weightKg = 50.0, reps = 6),
                    ),
                ),
            ),
        )

        val written = StrongCsv.write(listOf(workout))
        val reread = StrongCsv.parse(written).workouts.single().exercises.single()

        assertEquals(listOf("W", "1", "2", "D", "F"), Csv.read(written).drop(1).map { it[5] })
        assertEquals(workout.exercises.single(), reread)
    }

    @Test
    fun `an exported file reads back with the same sets and the header Hevy expects`() {
        val workouts = StrongCsv.parse(newer).workouts.map { ExportWorkout(it.title, it.startedAt, it.endedAt, it.notes, it.exercises) }

        val written = StrongCsv.write(workouts)
        val reread = StrongCsv.parse(written).workouts

        assertEquals(newer.lineSequence().first(), written.lineSequence().first())
        assertEquals(workouts.map { it.exercises }, reread.map { it.exercises })
        assertEquals(workouts.map { it.endedAt }, reread.map { it.endedAt })
    }
}
