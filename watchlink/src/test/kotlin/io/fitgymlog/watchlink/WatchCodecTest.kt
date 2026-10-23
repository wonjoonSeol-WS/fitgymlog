package io.fitgymlog.watchlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchCodecTest {
    private val kg = WatchUnit("kg", 1.0)
    private val squat = WatchExercise(
        name = "Squat",
        hasWeight = true,
        hasReps = true,
        weightStep = 2.5,
        sets = listOf(WatchSetRow("60 kg x 5", 60.0, 5, done = true), WatchSetRow("60 kg x 5", 60.0, 5)),
    )

    @Test
    fun stateSurvivesTheTrip() {
        val state = WatchState(
            workout = WatchWorkout("Workout A", 1_000, listOf(squat), kg, focus = 0, next = WatchSetRef(0, 1), restEndsAtMillis = 61_000, restLengthMillis = 90_000),
            routines = listOf(WatchRoutine(4, "Workout B", "Squat, Overhead press, Deadlift")),
            sentAtMillis = 1_792_775_265_000,
        )
        assertEquals(state, WatchCodec.decodeState(WatchCodec.encode(state)))
    }

    @Test
    fun everyCommandSurvivesTheTrip() {
        listOf(
            WatchCommand.Log(WatchSetRef(1, 2), weightKg = 62.5, reps = 4, complete = true),
            WatchCommand.Log(WatchSetRef(0, 0), complete = false),
            WatchCommand.Focus(2),
            WatchCommand.AdjustRest(-15),
            WatchCommand.SkipRest,
            WatchCommand.StartRoutine(7),
            WatchCommand.Finish,
            WatchCommand.Hello,
        ).forEach { assertEquals(it, WatchCodec.decodeCommand(WatchCodec.encode(it))) }
    }

    @Test
    fun aCommandFromANewerWatchIsIgnored() {
        assertNull(WatchCodec.decodeCommand("""{"type":"heart_rate","bpm":120}""".encodeToByteArray()))
    }

    @Test
    fun aNewerPhoneCanAddFields() {
        val decoded = WatchCodec.decodeState("""{"routines":[{"id":1,"name":"A","summary":"","colour":"red"}],"extra":true}""".encodeToByteArray())
        assertEquals(listOf(WatchRoutine(1, "A", "")), decoded?.routines)
    }

    @Test
    fun countsAndRestComeFromTheSets() {
        val workout = WatchWorkout("A", 0, listOf(squat), kg, focus = 0, restEndsAtMillis = 10_000, restLengthMillis = 10_000)
        assertEquals(1, workout.setsDone)
        assertEquals(2, workout.setsTotal)
        assertEquals(4_000L, workout.restLeftMillis(6_000))
        assertEquals(0L, workout.restLeftMillis(12_000))
    }

    @Test
    fun weightStopsKeepAnOffStepWeight() {
        val stops = weightStops(current = 61.25, step = 2.5, ceiling = 100.0)
        assertEquals(0.0, stops.first(), 0.0)
        assertEquals(listOf(60.0, 61.25, 62.5), stops.filter { it in 59.0..63.0 })
    }

    @Test
    fun weightStopsReachPastAHeavyWeight() {
        assertEquals(400.0, weightStops(current = 200.0, step = 2.5, ceiling = 300.0).last(), 0.0)
        assertEquals(300.0, weightStops(current = null, step = 2.5, ceiling = 300.0).last(), 0.0)
    }

    @Test
    fun poundsConvertBothWays() {
        val lb = WatchUnit("lb", 0.45359237)
        assertEquals(135.0, lb.toDisplay(lb.toKg(135.0)), 1e-9)
    }
}
