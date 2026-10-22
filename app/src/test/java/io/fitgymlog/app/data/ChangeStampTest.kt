package io.fitgymlog.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ChangeStampTest {
    @Test
    fun `a change on a phone whose clock is behind still sorts after the one it replaces`() {
        assertEquals(5_001L, stampAfter(previous = 5_000L, now = 4_000L))
    }

    @Test
    fun `a phone with a correct clock stamps the current time`() {
        assertEquals(9_000L, stampAfter(previous = 5_000L, now = 9_000L))
        assertEquals(4_000L, stampAfter(previous = null, now = 4_000L))
    }
}
