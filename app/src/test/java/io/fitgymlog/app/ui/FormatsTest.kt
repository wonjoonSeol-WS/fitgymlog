package io.fitgymlog.app.ui

import io.fitgymlog.app.model.WeightUnit
import java.time.Duration
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatsTest {
    private val defaultLocale = Locale.getDefault()

    @After
    fun restoreLocale() = Locale.setDefault(defaultLocale)

    @Test
    fun `kilograms drop trailing zeros and keep up to two decimals`() {
        assertEquals("60", formatWeightNumber(60.0, WeightUnit.KG))
        assertEquals("62.5", formatWeightNumber(62.5, WeightUnit.KG))
        assertEquals("61.25", formatWeightNumber(61.25, WeightUnit.KG))
        assertEquals("0", formatWeightNumber(0.0, WeightUnit.KG))
    }

    @Test
    fun `speed shows in km per hour, or in miles per hour with pounds, to one decimal`() {
        assertEquals("8", formatSpeedNumber(8.0, WeightUnit.KG))
        assertEquals("8.5", formatSpeedNumber(8.46, WeightUnit.KG))
        assertEquals("6.2", formatSpeedNumber(10.0, WeightUnit.LB))
        assertEquals(16.09344, fromDisplaySpeed(10.0, WeightUnit.LB), 1e-9)
        assertEquals(10.0, toDisplaySpeed(fromDisplaySpeed(10.0, WeightUnit.LB), WeightUnit.LB), 1e-9)
        assertEquals("12.5", formatKmh(12.5))
    }

    @Test
    fun `pounds are converted and rounded to one decimal`() {
        assertEquals("44.1", formatWeightNumber(20.0, WeightUnit.LB))
        assertEquals("220.5", formatWeightNumber(100.0, WeightUnit.LB))
        assertEquals("45", formatWeightNumber(20.41165665, WeightUnit.LB))
    }

    @Test
    fun `the clock shows hours only when there are some`() {
        assertEquals("0:00", formatClock(Duration.ZERO))
        assertEquals("0:59", formatClock(Duration.ofSeconds(59)))
        assertEquals("1:01", formatClock(Duration.ofSeconds(61)))
        assertEquals("59:59", formatClock(Duration.ofSeconds(3599)))
        assertEquals("1:01:01", formatClock(Duration.ofSeconds(3661)))
    }

    @Test
    fun `a negative duration shows as zero`() {
        assertEquals("0:00", formatClock(Duration.ofSeconds(-5)))
    }

    @Test
    fun `rest is minutes and padded seconds`() {
        assertEquals("0:00", formatRest(0))
        assertEquals("0:45", formatRest(45))
        assertEquals("1:30", formatRest(90))
        assertEquals("2:00", formatRest(120))
        assertEquals("5:00", formatRest(300))
    }

    @Test
    fun `numbers stay in ASCII digits and dots whatever the phone language`() {
        Locale.setDefault(Locale.forLanguageTag("ar-EG-u-nu-arab"))
        assertEquals("1:01:01", formatClock(Duration.ofSeconds(3661)))
        assertEquals("1:30", formatRest(90))

        Locale.setDefault(Locale.GERMANY)
        assertEquals("61.25", formatWeightNumber(61.25, WeightUnit.KG))
    }
}
