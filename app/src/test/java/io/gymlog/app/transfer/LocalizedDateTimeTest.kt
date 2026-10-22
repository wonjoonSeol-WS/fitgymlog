package io.gymlog.app.transfer

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalizedDateTimeTest {
    private val expected = LocalDateTime.of(2026, 10, 19, 19, 38)

    @Test
    fun `reads Hevy's Korean export format`() {
        assertEquals(expected, LocalizedDateTime.parse("19 10월 2026, 19:38"))
    }

    @Test
    fun `reads English day-first and month-first layouts with a 12 hour clock`() {
        assertEquals(expected, LocalizedDateTime.parse("19 Oct 2026, 19:38"))
        assertEquals(expected, LocalizedDateTime.parse("Oct 19, 2026, 7:38 PM"))
        assertEquals(expected, LocalizedDateTime.parse("October 19, 2026 7:38 pm"))
    }

    @Test
    fun `reads Korean, Japanese and German month forms`() {
        assertEquals(expected, LocalizedDateTime.parse("2026년 10월 19일 오후 7:38"))
        assertEquals(expected, LocalizedDateTime.parse("2026年10月19日 19:38"))
        assertEquals(expected, LocalizedDateTime.parse("19. Okt. 2026, 19:38"))
    }

    @Test
    fun `twelve o'clock maps to noon and midnight`() {
        assertEquals(12, LocalizedDateTime.parse("19 Oct 2026, 12:05 PM")!!.hour)
        assertEquals(0, LocalizedDateTime.parse("19 Oct 2026, 12:05 AM")!!.hour)
    }

    @Test
    fun `reads ISO dates written by Strong`() {
        assertEquals(expected, LocalizedDateTime.parse("2026-10-19 19:38:00"))
    }

    @Test
    fun `refuses dates whose day and month cannot be told apart`() {
        assertNull(LocalizedDateTime.parse("10/11/2026 19:38"))
        assertNull(LocalizedDateTime.parse("not a date"))
    }
}
