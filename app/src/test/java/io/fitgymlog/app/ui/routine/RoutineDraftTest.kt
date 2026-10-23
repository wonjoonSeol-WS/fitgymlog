package io.fitgymlog.app.ui.routine

import io.fitgymlog.app.model.Routine
import io.fitgymlog.app.model.RoutineExercise
import io.fitgymlog.app.model.WeightUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineDraftTest {
    private val squat = RoutineExercise("squat_barbell", sets = 5, reps = 5, weightKg = 100.0, restSeconds = 180)
    private val bench = RoutineExercise("bench_press_barbell", sets = 3, reps = null, weightKg = null, restSeconds = 120)

    @Test
    fun `clamps typed sets into range instead of dropping them`() {
        assertEquals(20, plannedSets("25"))
        assertEquals(1, plannedSets("0"))
        assertEquals(7, plannedSets("7"))
        assertNull(plannedSets(""))
    }

    @Test
    fun `typed speed keeps what was typed, to a tenth of the shown unit, and the range applies when the routine is saved`() {
        assertEquals(35.0, plannedSpeed("35", WeightUnit.KG)!!, 0.0)
        assertEquals(0.0, plannedSpeed("0", WeightUnit.KG)!!, 0.0)
        assertEquals(6.3, plannedSpeed("6.25", WeightUnit.KG)!!, 1e-9)
        assertEquals(5 * 1.609344, plannedSpeed("4.98", WeightUnit.LB)!!, 1e-9)
        assertNull(plannedSpeed("", WeightUnit.KG))
        val treadmill = RoutineExercise("treadmill", sets = 1, reps = null, weightKg = null, restSeconds = 0)
        val draft = RoutineDraft().add(treadmill).let { it.update(it.planned.single().key) { planned -> planned.copy(level = 35, speedKmh = 40.0) } }
        val low = RoutineDraft().add(treadmill).let { it.update(it.planned.single().key) { planned -> planned.copy(level = -2, speedKmh = 0.1) } }

        assertEquals(listOf<Any?>(20, 30.0), draft.toRoutine(id = 1).exercises.single().let { listOf(it.level, it.speedKmh) })
        assertEquals(listOf<Any?>(0, 0.5), low.toRoutine(id = 1).exercises.single().let { listOf(it.level, it.speedKmh) })
    }

    @Test
    fun `gives the same exercise planned twice different keys`() {
        val draft = RoutineDraft().add(squat).add(squat).add(bench)
        val again = draft.remove(draft.planned[0].key).add(squat)

        assertEquals(3, draft.planned.map { it.key }.distinct().size)
        assertEquals(3, again.planned.map { it.key }.distinct().size)
    }

    @Test
    fun `edits and moves only the row with the given key`() {
        val draft = RoutineDraft().add(squat).add(squat).add(bench)
        val second = draft.planned[1].key

        val edited = draft.update(second) { it.copy(sets = 2) }.move(second, -1)

        assertEquals(listOf(2, 5, 3), edited.planned.map { it.sets })
        assertEquals(second, edited.planned[0].key)
    }

    @Test
    fun `has changes only while the plan differs from the saved routine`() {
        val loaded = RoutineDraft.of(Routine(id = 4, name = "Legs", exercises = listOf(squat, bench)))
        val key = loaded.planned[0].key

        assertFalse(loaded.hasChanges)
        assertTrue(loaded.update(key) { it.copy(sets = 4) }.hasChanges)
        assertFalse(loaded.update(key) { it.copy(sets = 4) }.update(key) { it.copy(sets = 5) }.hasChanges)
        assertTrue(loaded.rename("Legs day").hasChanges)
        assertTrue(RoutineDraft().rename("New").hasChanges)
    }

    @Test
    fun `survives saving and restoring with its keys and saved routine`() {
        val draft = RoutineDraft.of(Routine(id = 4, name = "Legs", exercises = listOf(squat, bench))).add(squat).rename("Legs B")

        val restored = RoutineDraft.decode(draft.encode())!!

        assertEquals(draft, restored)
        assertTrue(restored.hasChanges)
        assertEquals(3, restored.add(bench).planned.last().key)
    }

    @Test
    fun `a cardio plan's time, level and speed survive the draft, saving and restoring it`() {
        val treadmill = RoutineExercise("treadmill", sets = 2, reps = null, weightKg = null, restSeconds = 30, durationSeconds = 1_200, level = 12, speedKmh = 6.5)
        val draft = RoutineDraft().add(treadmill)

        val saved = draft.toRoutine(id = 3).exercises.single()
        val restored = RoutineDraft.decode(draft.encode())!!.toRoutine(id = 3).exercises.single()

        assertEquals(treadmill, saved)
        assertEquals(treadmill, restored)
    }
}
