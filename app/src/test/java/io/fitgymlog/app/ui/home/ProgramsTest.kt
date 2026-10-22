package io.fitgymlog.app.ui.home

import io.fitgymlog.app.catalog.ExerciseCatalog
import io.fitgymlog.app.model.Routine
import io.fitgymlog.app.model.isTimed
import io.fitgymlog.app.resourceName
import io.fitgymlog.app.stringsIn
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgramsTest {
    private fun englishFirst(id: Int) = listOf(english.getValue(resourceName(id)), korean.getValue(resourceName(id))).distinct()

    private fun koreanFirst(id: Int) = englishFirst(id).reversed()

    @Test
    fun `two quick taps add each routine once`() = runTest {
        val saved = mutableListOf<Routine>()
        val existing: suspend () -> List<Routine> = { yield(); saved.toList() }
        val save: suspend (Routine) -> Unit = { yield(); saved += it }
        val wanted = Program.STRONGLIFTS.routines(restSeconds = 120)

        val first = async { Programs.addMissing(wanted, ::englishFirst, existing, save) }
        val second = async { Programs.addMissing(wanted, ::englishFirst, existing, save) }

        assertEquals(listOf(2, 0), listOf(first.await(), second.await()))
        assertEquals(listOf("StrongLifts A", "StrongLifts B"), saved.map { it.name })
    }

    @Test
    fun `adds only the missing routine`() = runTest {
        val saved = mutableListOf(Routine(id = 1, name = "StrongLifts A", exercises = emptyList()))

        val added = Programs.addMissing(Program.STRONGLIFTS.routines(restSeconds = 90), ::englishFirst, { saved.toList() }, { saved += it })

        assertEquals(1, added)
        assertEquals(listOf("StrongLifts A", "StrongLifts B"), saved.map { it.name })
        assertEquals(listOf(90, 90, 90), saved.last().exercises.map { it.restSeconds })
    }

    @Test
    fun `a program added in English is not added again after the app switches to Korean`() = runTest {
        val saved = mutableListOf<Routine>()
        Programs.addMissing(Program.UPPER_LOWER.routines(restSeconds = 90), ::englishFirst, { saved.toList() }, { saved += it })

        val addedInKorean = Programs.addMissing(Program.UPPER_LOWER.routines(restSeconds = 90), ::koreanFirst, { saved.toList() }, { saved += it })

        assertEquals(0, addedInKorean)
        assertEquals(listOf("Upper", "Lower"), saved.map { it.name })
    }

    @Test
    fun `StrongLifts keeps the names it had before programs existed, in Korean too`() {
        val names = Program.STRONGLIFTS.routines(restSeconds = 90).map { koreanFirst(it.name) }

        assertEquals(listOf(listOf("StrongLifts A"), listOf("StrongLifts B")), names)
    }

    @Test
    fun `every program plans reps of built-in exercises`() {
        val catalog = ExerciseCatalog.all.associateBy { it.id }
        val used = Program.entries.flatMap { it.routines(restSeconds = 90) }.flatMap { it.exercises }.map { it.exerciseId }

        assertEquals(emptyList<String>(), used.filterNot { it in catalog })
        assertTrue(used.none { catalog.getValue(it).kind.isTimed })
    }

    @Test
    fun `routine names stay distinct in each language, so adding one program never skips another's routine`() {
        listOf(english, english + korean).forEach { strings ->
            val names = Program.entries.flatMap { it.routines(restSeconds = 90) }.map { strings.getValue(resourceName(it.name)) }

            assertEquals(names.distinct(), names)
        }
    }

    private companion object {
        val english = stringsIn("values")
        val korean = english + stringsIn("values-ko")
    }
}
