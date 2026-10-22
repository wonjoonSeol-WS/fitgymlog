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
import org.junit.Test

class ProgressionTest {
    private val done = Instant.parse("2026-10-19T10:00:00Z")
    private val squat = Exercise("squat_barbell", "Squat (Barbell)", ExerciseKind.WEIGHT_REPS, ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT, Equipment.BARBELL)
    private val deadlift = squat.copy(id = "deadlift_barbell")
    private val curl = squat.copy(id = "bicep_curl_dumbbell", equipment = Equipment.DUMBBELL)
    private val pushUp = squat.copy(id = "push_up", kind = ExerciseKind.BODYWEIGHT_REPS, equipment = Equipment.BODYWEIGHT)

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
    fun `a missed rep repeats the weight`() {
        val target = Progression.target(squat, listOf(fives(60.0, 5, 5, 5, 4, 3)), WeightUnit.KG)

        assertEquals(TargetReason.MISSED_REPS, target.reason)
        assertEquals(60.0, target.weightKg!!, 0.0)
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
    fun `the step depends on the lift and the unit`() {
        assertEquals(105.0, Progression.target(deadlift, listOf(listOf(set(100.0, 5))), WeightUnit.KG).weightKg!!, 0.0)
        assertEquals(14.0, Progression.target(curl, listOf(listOf(set(12.0, 10, target = 10))), WeightUnit.KG).weightKg!!, 0.0)
        val pounds = Progression.target(squat, listOf(fives(135 * 0.45359237, 5, 5, 5, 5, 5)), WeightUnit.LB)
        assertEquals(140.0, pounds.weightKg!! / 0.45359237, 1e-9)
    }

    @Test
    fun `warm-up and unfinished sets are not judged, and the top weight is the one that counts`() {
        val sets = listOf(set(20.0, 5, type = SetType.WARMUP), set(40.0, 5, type = SetType.WARMUP), set(60.0, 5), set(60.0, 5), set(60.0, 5).copy(completedAt = null))

        val target = Progression.target(squat, listOf(sets), WeightUnit.KG)

        assertEquals(60.0, target.lastWeightKg!!, 0.0)
        assertEquals(62.5, target.weightKg!!, 0.0)
    }

    @Test
    fun `a set's own plan decides, so a set planned for fewer reps can still pass`() {
        val sets = listOf(set(60.0, 5), set(60.0, 5), set(60.0, 3, target = 3))

        assertEquals(TargetReason.ALL_REPS_DONE, Progression.target(squat, listOf(sets), WeightUnit.KG).reason)
    }

    @Test
    fun `bodyweight work adds a rep, and nothing logged yet means no target`() {
        assertEquals(11, Progression.target(pushUp, listOf(listOf(set(null, 10, target = 10), set(null, 10, target = 10))), WeightUnit.KG).reps)
        assertEquals(TargetReason.FIRST_TIME, Progression.target(squat, emptyList(), WeightUnit.KG).reason)
    }
}
