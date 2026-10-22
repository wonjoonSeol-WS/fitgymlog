package io.fitgymlog.app.transfer

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
    fun `weekday names and short words are not read as months`() {
        assertEquals(expected, LocalizedDateTime.parse("Sat, Oct 19, 2026, 7:38 PM"))
        assertEquals(expected, LocalizedDateTime.parse("19 de octubre de 2026, 19:38"))
        assertEquals(expected, LocalizedDateTime.parse("di 19 okt. 2026 19:38"))
    }

    @Test
    fun `a month written in Chinese numerals still counts despite being two letters`() {
        assertEquals(expected, LocalizedDateTime.parse("2026 十月 19 19:38"))
    }

    @Test
    fun `refuses a date naming two different months`() {
        assertNull(LocalizedDateTime.parse("19 Oct Nov 2026, 19:38"))
    }

    @Test
    fun `reads Korean and Japanese twelve o'clock as noon and midnight`() {
        assertEquals(LocalDateTime.of(2026, 10, 19, 12, 5), LocalizedDateTime.parse("2026년 10월 19일 오후 12:05"))
        assertEquals(LocalDateTime.of(2026, 10, 19, 0, 5), LocalizedDateTime.parse("2026년 10월 19일 오전 12:05"))
        assertEquals(LocalDateTime.of(2026, 10, 19, 12, 5), LocalizedDateTime.parse("2026年10月19日 午後12:05"))
        assertEquals(LocalDateTime.of(2026, 10, 19, 0, 5), LocalizedDateTime.parse("2026年10月19日 午前12:05"))
    }

    @Test
    fun `keeps seconds and uses the first time when there are two`() {
        assertEquals(LocalDateTime.of(2026, 10, 19, 19, 38, 45), LocalizedDateTime.parse("19 Oct 2026, 19:38:45"))
        assertEquals(expected, LocalizedDateTime.parse("19 Oct 2026, 19:38 to 21:05"))
    }

    @Test
    fun `refuses dates whose day and month cannot be told apart`() {
        assertNull(LocalizedDateTime.parse("10/11/2026 19:38"))
        assertNull(LocalizedDateTime.parse("not a date"))
    }
}
