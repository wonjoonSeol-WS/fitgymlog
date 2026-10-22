package io.fitgymlog.app.catalog

import io.fitgymlog.app.resourceName
import io.fitgymlog.app.stringsIn
import org.junit.Assert.assertEquals
import org.junit.Test

class ExerciseCatalogTest {
    private val english = stringsIn("values")
    private val korean = stringsIn("values-ko")

    @Test
    fun `English names match Hevy's library, so exports import cleanly there`() {
        val mismatched = ExerciseCatalog.all.filter { english[resourceName(it.nameRes)] != it.hevyName }

        assertEquals(emptyList<String>(), mismatched.map { "${it.id}: ${english[resourceName(it.nameRes)]} vs ${it.hevyName}" })
    }

    @Test
    fun `every built-in exercise has a Korean name and a unique id`() {
        assertEquals(emptyList<String>(), ExerciseCatalog.all.filter { resourceName(it.nameRes) !in korean }.map { it.id })
        assertEquals(ExerciseCatalog.all.map { it.id }.distinct(), ExerciseCatalog.all.map { it.id })
    }
}
