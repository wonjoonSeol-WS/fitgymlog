package io.fitgymlog.app.ui.home

import io.fitgymlog.app.R
import io.fitgymlog.app.catalog.ExerciseCatalog
import io.fitgymlog.app.model.Routine
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgramsTest {
    private val english = stringsIn("values")

    private fun name(id: Int): String = english.getValue(resourceName(id))

    @Test
    fun `two quick taps add each routine once`() = runTest {
        val saved = mutableListOf<Routine>()
        val existing: suspend () -> List<Routine> = { yield(); saved.toList() }
        val save: suspend (Routine) -> Unit = { yield(); saved += it }
        val wanted = Program.STRONGLIFTS.routines(::name, restSeconds = 120)

        val first = async { Programs.addMissing(wanted, existing, save) }
        val second = async { Programs.addMissing(wanted, existing, save) }

        assertEquals(listOf(2, 0), listOf(first.await(), second.await()))
        assertEquals(listOf("StrongLifts A", "StrongLifts B"), saved.map { it.name })
    }

    @Test
    fun `adds only the missing routine`() = runTest {
        val saved = mutableListOf(Routine(id = 1, name = "StrongLifts A", exercises = emptyList()))

        val added = Programs.addMissing(Program.STRONGLIFTS.routines(::name, restSeconds = 90), { saved.toList() }, { saved += it })

        assertEquals(1, added)
        assertEquals(listOf("StrongLifts A", "StrongLifts B"), saved.map { it.name })
        assertEquals(listOf(90, 90, 90), saved.last().exercises.map { it.restSeconds })
    }

    @Test
    fun `every program uses only built-in exercises`() {
        val builtIn = ExerciseCatalog.all.map { it.id }.toSet()
        val used = Program.entries.flatMap { it.routines(::name, restSeconds = 90) }.flatMap { it.exercises }.map { it.exerciseId }

        assertEquals(emptyList<String>(), used.filterNot { it in builtIn })
    }

    @Test
    fun `routine names stay distinct in each language, so adding one program never skips another's routine`() {
        val korean = english + stringsIn("values-ko")
        listOf(english, korean).forEach { strings ->
            val names = Program.entries.flatMap { program -> program.routines({ strings.getValue(resourceName(it)) }, restSeconds = 90) }.map { it.name }

            assertEquals(names.distinct(), names)
        }
    }

    private fun resourceName(id: Int): String = R.string::class.java.fields.first { it.getInt(null) == id }.name

    private fun stringsIn(directory: String): Map<String, String> =
        File("src/main/res/$directory").listFiles { file -> file.extension == "xml" }.orEmpty().flatMap { file ->
            val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            (0 until strings.length).map { strings.item(it) }.map { it.attributes.getNamedItem("name").nodeValue to it.textContent }
        }.toMap()
}
