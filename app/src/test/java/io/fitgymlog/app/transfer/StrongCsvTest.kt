package io.fitgymlog.app.transfer

import io.fitgymlog.app.model.SetType
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
    fun `an exported file reads back with the same sets and the header Hevy expects`() {
        val workouts = StrongCsv.parse(newer).workouts.map { ExportWorkout(it.title, it.startedAt, it.endedAt, it.notes, it.exercises) }

        val written = StrongCsv.write(workouts)
        val reread = StrongCsv.parse(written).workouts

        assertEquals(newer.lineSequence().first(), written.lineSequence().first())
        assertEquals(workouts.map { it.exercises }, reread.map { it.exercises })
        assertEquals(workouts.map { it.endedAt }, reread.map { it.endedAt })
    }
}
