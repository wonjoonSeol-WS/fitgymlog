package io.fitgymlog.app.workout

import org.junit.Assert.assertEquals
import org.junit.Test

class PlatesTest {
    private fun kg(total: Double, bar: Double = 20.0) = Plates.perSide(total, bar, Plates.KG_PLATES)

    @Test
    fun `loads the heaviest plates first`() {
        assertEquals(listOf(25.0, 15.0, 1.25), kg(102.5).side)
        assertEquals(listOf(20.0, 1.25), kg(62.5).side)
    }

    @Test
    fun `reports weight the plates cannot make`() {
        val load = kg(61.0)

        assertEquals(listOf(20.0), load.side)
        assertEquals(0.5, load.leftover, 0.0001)
    }

    @Test
    fun `the bar alone or less needs no plates`() {
        assertEquals(emptyList<Double>(), kg(20.0).side)
        assertEquals(emptyList<Double>(), kg(15.0).side)
    }

    @Test
    fun `a lighter bar changes the plates for the same total`() {
        assertEquals(listOf(20.0), kg(60.0, bar = 20.0).side)
        assertEquals(listOf(20.0, 2.5), kg(60.0, bar = 15.0).side)
    }

    @Test
    fun `pound plates and bars work the same way`() {
        assertEquals(listOf(45.0, 45.0), Plates.perSide(225.0, 45.0, Plates.LB_PLATES).side)
    }

    @Test
    fun `totals add both sides and the bar`() {
        assertEquals(62.5, Plates.total(20.0, listOf(20.0, 1.25)), 0.0001)
    }
}
