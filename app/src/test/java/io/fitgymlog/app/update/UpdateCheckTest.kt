package io.fitgymlog.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test
    fun `a higher number anywhere is newer, compared as numbers not text`() {
        assertTrue(isNewer("0.9.1", "0.9.0"))
        assertTrue(isNewer("0.10.0", "0.9.9"))
        assertTrue(isNewer("1.0", "0.9.9"))
    }

    @Test
    fun `the same or an older release is not an update`() {
        assertFalse(isNewer("0.9.0", "0.9.0"))
        assertFalse(isNewer("0.9", "0.9.0"))
        assertFalse(isNewer("0.8.1", "0.9.0"))
    }

    @Test
    fun `a suffix on either side is ignored`() {
        assertFalse(isNewer("0.9.0-beta", "0.9.0"))
        assertTrue(isNewer("0.9.1", "0.9.0-debug"))
    }
}
