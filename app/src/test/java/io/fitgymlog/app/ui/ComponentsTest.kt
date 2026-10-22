package io.fitgymlog.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentsTest {
    @Test
    fun `typed text that means the same number is kept`() {
        assertTrue(sameNumber("60,5", "60.5"))
        assertTrue(sameNumber("60.", "60"))
        assertTrue(sameNumber("", ""))
    }

    @Test
    fun `typed text that means another number is replaced`() {
        assertFalse(sameNumber("6", "60"))
        assertFalse(sameNumber("60,5", "61"))
        assertFalse(sameNumber("", "60"))
    }

    @Test
    fun `typed numbers read with either separator`() {
        assertEquals(62.5, parseDecimal("62.5")!!, 0.0)
        assertEquals(62.5, parseDecimal("62,5")!!, 0.0)
        assertEquals(60.0, parseDecimal("60.")!!, 0.0)
        assertEquals(0.5, parseDecimal(".5")!!, 0.0)
    }

    @Test
    fun `nothing typed yet or a lone separator is no number`() {
        assertNull(parseDecimal(""))
        assertNull(parseDecimal("."))
        assertNull(parseDecimal(","))
    }

    @Test
    fun `input that cannot become a number is refused`() {
        assertTrue(isDecimalInput("61.235"))
        assertFalse(isDecimalInput("12345"))
        assertFalse(isDecimalInput("1.2345"))
        assertFalse(isDecimalInput("1.2.3"))
        assertFalse(isDecimalInput("-5"))
    }
}
