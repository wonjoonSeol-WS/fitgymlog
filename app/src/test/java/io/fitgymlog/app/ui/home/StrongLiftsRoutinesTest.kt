package io.fitgymlog.app.ui.home

import io.fitgymlog.app.model.Routine
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class StrongLiftsRoutinesTest {
    @Test
    fun `two quick taps add each routine once`() = runTest {
        val saved = mutableListOf<Routine>()
        val existing: suspend () -> List<Routine> = { yield(); saved.toList() }
        val save: suspend (Routine) -> Unit = { yield(); saved += it }

        val first = async { StrongLiftsRoutines.addMissing(existing, save, restSeconds = 120) }
        val second = async { StrongLiftsRoutines.addMissing(existing, save, restSeconds = 120) }

        assertEquals(listOf(2, 0), listOf(first.await(), second.await()))
        assertEquals(listOf("StrongLifts A", "StrongLifts B"), saved.map { it.name })
    }

    @Test
    fun `adds only the missing routine`() = runTest {
        val saved = mutableListOf(Routine(id = 1, name = "StrongLifts A", exercises = emptyList()))

        val added = StrongLiftsRoutines.addMissing({ saved.toList() }, { saved += it }, restSeconds = 90)

        assertEquals(1, added)
        assertEquals(listOf("StrongLifts A", "StrongLifts B"), saved.map { it.name })
        assertEquals(listOf(90, 90, 90), saved.last().exercises.map { it.restSeconds })
    }
}
