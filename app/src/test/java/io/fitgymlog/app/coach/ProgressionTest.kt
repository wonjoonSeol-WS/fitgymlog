package io.fitgymlog.app.coach

import androidx.health.connect.client.records.ExerciseSegment
import io.fitgymlog.app.model.Equipment
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.model.WorkoutSet
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressionTest {
    private val done = Instant.parse("2026-10-19T10:00:00Z")
    private val squat = Exercise("squat_barbell", "Squat (Barbell)", ExerciseKind.WEIGHT_REPS, ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT, Equipment.BARBELL)
    private val deadlift = squat.copy(id = "deadlift_barbell", largeIncrements = true)
    private val curl = squat.copy(id = "bicep_curl_dumbbell", equipment = Equipment.DUMBBELL)
    private val pushUp = squat.copy(id = "push_up", kind = ExerciseKind.BODYWEIGHT_REPS, equipment = Equipment.BODYWEIGHT)
    private val legPress = squat.copy(id = "leg_press_machine", equipment = Equipment.MACHINE)
    private val plank = squat.copy(id = "plank", kind = ExerciseKind.DURATION, equipment = Equipment.BODYWEIGHT)
    private val assisted = squat.copy(id = "pull_up_assisted", kind = ExerciseKind.ASSISTED_BODYWEIGHT, equipment = Equipment.MACHINE)

    private fun set(kg: Double?, reps: Int, target: Int? = 5, type: SetType = SetType.NORMAL) =
        WorkoutSet(type = type, weightKg = kg, reps = reps, targetReps = target, completedAt = done)

    private fun fives(kg: Double, vararg reps: Int) = reps.map { set(kg, it) }

    @Test
    fun `every planned rep earns one step, with room for the coach between 80 percent and two steps up`() {
        val target = Progression.target(squat, listOf(fives(60.0, 5, 5, 5, 5, 5)), WeightUnit.KG)

        assertEquals(TargetReason.ALL_REPS_DONE, target.reason)
        assertEquals(62.5, target.weightKg!!, 0.0)
        assertEquals(5, target.reps)
        assertEquals(47.5, target.minWeightKg!!, 0.0)
        assertEquals(65.0, target.maxWeightKg!!, 0.0)
    }

    @Test
    fun `every rep done but a top set at RPE 9_5 or more holds the weight, while easier RPE still goes up`() {
        val grind = fives(100.0, 5, 5, 5, 5, 5).mapIndexed { index, set -> if (index == 4) set.copy(rpe = 9.5) else set.copy(rpe = 8.0) }
        val comfortable = fives(100.0, 5, 5, 5, 5, 5).map { it.copy(rpe = 8.5) }

        val held = Progression.target(squat, listOf(grind), WeightUnit.KG)
        assertEquals(TargetReason.HARD_SETS, held.reason)
        assertEquals(100.0, held.weightKg!!, 0.0)
        assertEquals(TargetReason.ALL_REPS_DONE, Progression.target(squat, listOf(comfortable), WeightUnit.KG).reason)
    }

    @Test
    fun `a missed rep repeats the weight`() {
        val target = Progression.target(squat, listOf(fives(60.0, 5, 5, 5, 4, 3)), WeightUnit.KG)

        assertEquals(TargetReason.MISSED_REPS, target.reason)
        assertEquals(60.0, target.weightKg!!, 0.0)
    }

    @Test
    fun `the target keeps the planned reps, not the extra reps done`() {
        val target = Progression.target(squat, listOf(listOf(set(60.0, 7), set(60.0, 6), set(60.0, 6))), WeightUnit.KG)

        assertEquals(TargetReason.ALL_REPS_DONE, target.reason)
        assertEquals(5, target.reps)
    }

    @Test
    fun `three misses in a row at one weight drop it by ten percent, but a success among them does not`() {
        val missed = fives(60.0, 5, 5, 4, 4, 3)
        val stalled = Progression.target(squat, listOf(missed, missed, missed), WeightUnit.KG)
        val recovering = Progression.target(squat, listOf(missed, missed, fives(60.0, 5, 5, 5, 5, 5)), WeightUnit.KG)

        assertEquals(TargetReason.STALLED, stalled.reason)
        assertEquals(52.5, stalled.weightKg!!, 0.0)
        assertEquals(TargetReason.MISSED_REPS, recovering.reason)
        assertEquals(60.0, recovering.weightKg!!, 0.0)
    }

    @Test
    fun `misses at a lighter weight earlier do not count toward a stall at today's weight`() {
        val target = Progression.target(squat, listOf(fives(60.0, 5, 4), fives(60.0, 5, 4), fives(57.5, 5, 4)), WeightUnit.KG)

        assertEquals(TargetReason.MISSED_REPS, target.reason)
    }

    @Test
    fun `a weight entered in pounds matches the same weight applied from a target`() {
        val typed = 140 * 0.45359237
        val applied = 63.503
        val missed = { kg: Double -> listOf(set(kg, 5), set(kg, 4)) }

        val target = Progression.target(squat, listOf(missed(applied), missed(typed), missed(typed)), WeightUnit.LB)

        assertEquals(TargetReason.STALLED, target.reason)
    }

    @Test
    fun `only the top sets decide a stall, so missed back-off sets don't count against a good day`() {
        val missed = fives(60.0, 5, 5, 4)
        val goodTopSets = fives(60.0, 5, 5, 5) + set(50.0, 6, target = 8)

        val target = Progression.target(squat, listOf(missed, missed, goodTopSets), WeightUnit.KG)

        assertEquals(TargetReason.MISSED_REPS, target.reason)
    }

    @Test
    fun `the step depends on the lift and the unit`() {
        assertEquals(105.0, Progression.target(deadlift, listOf(listOf(set(100.0, 5))), WeightUnit.KG).weightKg!!, 0.0)
        assertEquals(105.0, Progression.target(legPress, listOf(listOf(set(100.0, 5))), WeightUnit.KG).weightKg!!, 0.0)
        assertEquals(14.0, Progression.target(curl, listOf(listOf(set(12.0, 10, target = 10))), WeightUnit.KG).weightKg!!, 0.0)
        val pounds = Progression.target(squat, listOf(fives(135 * 0.45359237, 5, 5, 5, 5, 5)), WeightUnit.LB)
        assertEquals(140.0, pounds.weightKg!! / 0.45359237, 0.001)
        val deadliftPounds = Progression.target(deadlift, listOf(fives(225 * 0.45359237, 5)), WeightUnit.LB)
        assertEquals(235.0, deadliftPounds.weightKg!! / 0.45359237, 0.001)
    }

    @Test
    fun `warm-up sets are not judged, and the top weight is the one that counts`() {
        val sets = listOf(set(20.0, 5, type = SetType.WARMUP), set(70.0, 2, type = SetType.WARMUP), set(60.0, 5), set(60.0, 5), set(55.0, 3))

        val target = Progression.target(squat, listOf(sets), WeightUnit.KG)

        assertEquals(60.0, target.lastWeightKg!!, 0.0)
        assertEquals(62.5, target.weightKg!!, 0.0)
    }

    @Test
    fun `a session with only warm-ups is passed over for the last real one`() {
        val warmUpsOnly = listOf(set(20.0, 5, type = SetType.WARMUP))

        assertEquals(62.5, Progression.target(squat, listOf(warmUpsOnly, fives(60.0, 5, 5)), WeightUnit.KG).weightKg!!, 0.0)
    }

    @Test
    fun `imported history without tick times counts like any finished workout`() {
        val imported = listOf(set(60.0, 5).copy(completedAt = null, targetReps = null), set(60.0, 5).copy(completedAt = null, targetReps = null))

        assertEquals(62.5, Progression.target(squat, listOf(imported), WeightUnit.KG).weightKg!!, 0.0)
    }

    @Test
    fun `without planned reps the first set is the bar, so an imported 5, 5, 4 is a miss`() {
        val imported = listOf(5, 5, 4).map { set(60.0, it).copy(completedAt = null, targetReps = null) }

        assertEquals(TargetReason.MISSED_REPS, Progression.target(squat, listOf(imported), WeightUnit.KG).reason)
    }

    @Test
    fun `a set's own plan decides, so a set planned for fewer reps can still pass`() {
        val sets = listOf(set(60.0, 5), set(60.0, 5), set(60.0, 3, target = 3))

        val target = Progression.target(squat, listOf(sets), WeightUnit.KG)

        assertEquals(TargetReason.ALL_REPS_DONE, target.reason)
        assertEquals(5, target.reps)
    }

    @Test
    fun `bodyweight work adds a rep to the plan, and a miss keeps the plan`() {
        val passed = Progression.target(pushUp, listOf(listOf(set(null, 10, target = 10), set(null, 8, target = 8))), WeightUnit.KG)
        val missed = Progression.target(pushUp, listOf(listOf(set(null, 8, target = 10), set(null, 10, target = 10))), WeightUnit.KG)

        assertEquals(11, passed.reps)
        assertEquals(TargetReason.MISSED_REPS, missed.reason)
        assertEquals(10, missed.reps)
    }

    @Test
    fun `assisted work progresses by taking help away, and a stall gives some back`() {
        val passed = Progression.target(assisted, listOf(listOf(set(35.0, 8, target = 8), set(30.0, 8, target = 8))), WeightUnit.KG)
        val missed = sets30(6)
        val stalled = Progression.target(assisted, listOf(missed, missed, missed), WeightUnit.KG)

        assertEquals(TargetReason.ALL_REPS_DONE, passed.reason)
        assertEquals(30.0, passed.lastWeightKg!!, 0.0)
        assertEquals(25.0, passed.weightKg!!, 0.0)
        assertEquals(20.0, passed.minWeightKg!!, 0.0)
        assertEquals(40.0, passed.maxWeightKg!!, 0.0)
        assertEquals(TargetReason.STALLED, stalled.reason)
        assertEquals(35.0, stalled.weightKg!!, 0.0)
    }

    @Test
    fun `assistance never goes below none`() {
        val target = Progression.target(assisted, listOf(listOf(set(2.5, 8, target = 8))), WeightUnit.KG)

        assertEquals(0.0, target.weightKg!!, 0.0)
        assertEquals(0.0, target.minWeightKg!!, 0.0)
    }

    private fun sets30(reps: Int) = listOf(set(30.0, reps, target = 8), set(30.0, reps, target = 8))

    @Test
    fun `nothing logged yet, or timed work, gets no target`() {
        assertEquals(TargetReason.FIRST_TIME, Progression.target(squat, emptyList(), WeightUnit.KG).reason)
        val timed = Progression.target(plank, listOf(listOf(WorkoutSet(durationSeconds = 60, completedAt = done))), WeightUnit.KG)
        assertEquals(TargetReason.SAME_AS_LAST, timed.reason)
        assertNull(timed.weightKg)
    }
}
