package io.fitgymlog.app.ui

import org.junit.Assert.assertFalse
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
}
