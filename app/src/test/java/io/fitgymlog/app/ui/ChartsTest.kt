package io.fitgymlog.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartsTest {
    @Test
    fun `axis values are round steps that cover every point`() {
        assertEquals(listOf(80.0, 79.5, 79.0), axisTicks(79.02, 79.6))
        assertEquals(listOf(100.0, 80.0, 60.0), axisTicks(62.5, 92.5))
        assertEquals(listOf(18.5, 18.0, 17.5), axisTicks(17.9, 18.4))
        assertEquals(listOf(81.0, 80.0, 79.0), axisTicks(80.0, 80.0))
    }

    @Test
    fun `counts step by whole numbers, values that are never negative stay at or above zero, and huge ranges still get a step`() {
        assertEquals(listOf(12.0, 11.0, 10.0), axisTicks(10.0, 11.0, minStep = 1.0))
        assertEquals(listOf(1.0, 0.5, 0.0), axisTicks(0.0, 0.0))
        assertEquals(listOf(2.0E7, 1.0E7, 0.0), axisTicks(1.0, 1.5E7))
        assertEquals(listOf(6.0, 3.0, 0.0), axisTicks(0.0, 5.0, minStep = 1.0))
        assertEquals(listOf(5.0, 0.0, -5.0), axisTicks(-3.0, 2.0))
        assertEquals(listOf(4.0E6, 2.0E6, 0.0), axisTicks(1.9E6, 3.9E6))
    }
}
