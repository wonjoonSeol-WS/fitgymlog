package io.fitgymlog.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationTest {
    @Test
    fun `every English string and plural has a Korean one, and Korean has none English lacks`() {
        val english = translatableIn("values")
        val korean = translatableIn("values-ko")

        assertEquals(emptySet<String>() to emptySet<String>(), (english - korean) to (korean - english))
    }
}
