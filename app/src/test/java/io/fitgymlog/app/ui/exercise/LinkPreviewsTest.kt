package io.fitgymlog.app.ui.exercise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPreviewsTest {
    @Test
    fun `keeps ordinary images at full size`() {
        assertEquals(1, imageSampleSize(1280, 720))
        assertEquals(1, imageSampleSize(2559, 1440))
    }

    @Test
    fun `samples a tall narrow image down by its height`() {
        val sample = imageSampleSize(1000, 30_000)!!

        assertTrue(30_000 / sample < 2560)
        assertEquals(16, sample)
    }

    @Test
    fun `samples a wide image down by its width`() {
        assertEquals(4, imageSampleSize(6000, 400))
    }

    @Test
    fun `rejects images too large to decode and empty bounds`() {
        assertNull(imageSampleSize(10_000, 10_000))
        assertNull(imageSampleSize(-1, -1))
    }

    @Test
    fun `remembers a failure for a short while only`() {
        var now = 0L
        val failures = RecentFailures(ttlMillis = 60_000, capacity = 8, now = { now })

        failures.record("page:a")
        now = 59_999
        assertTrue(failures.isRecent("page:a"))
        assertFalse(failures.isRecent("page:b"))
        now = 60_000
        assertFalse(failures.isRecent("page:a"))
    }

    @Test
    fun `forgets a failure once the key loads`() {
        val failures = RecentFailures(ttlMillis = 60_000, capacity = 8, now = { 0L })

        failures.record("page:a")
        failures.forget("page:a")

        assertFalse(failures.isRecent("page:a"))
    }
}
