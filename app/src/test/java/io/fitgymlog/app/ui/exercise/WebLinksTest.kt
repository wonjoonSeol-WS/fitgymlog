package io.fitgymlog.app.ui.exercise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebLinksTest {
    @Test
    fun `accepts http and https in any case and stores https`() {
        assertEquals("https://stronglifts.com/squat/", WebLinks.normalize(" https://stronglifts.com/squat/ "))
        assertEquals("https://stronglifts.com/squat/", WebLinks.normalize("HTTPS://StrongLifts.com/squat/"))
        assertEquals("https://stronglifts.com/squat/?a=1#top", WebLinks.normalize("http://stronglifts.com/squat/?a=1#top"))
    }

    @Test
    fun `adds https when the scheme is missing`() {
        assertEquals("https://youtube.com/watch?v=VnV7vEi7Sz8", WebLinks.normalize("youtube.com/watch?v=VnV7vEi7Sz8"))
        assertEquals("https://cdn.example.com/a", WebLinks.normalize("//cdn.example.com/a"))
    }

    @Test
    fun `escapes characters browsers accept but URI parsing rejects`() {
        assertEquals("https://example.com/search?q=squat%7Cbench", WebLinks.normalize("https://example.com/search?q=squat|bench"))
    }

    @Test
    fun `rejects what is not a web link`() {
        assertNull(WebLinks.normalize("httpfoo"))
        assertNull(WebLinks.normalize("httpfoo://example.com"))
        assertNull(WebLinks.normalize("ftp://example.com/a"))
        assertNull(WebLinks.normalize("javascript:alert(1)"))
        assertNull(WebLinks.normalize("https://"))
        assertNull(WebLinks.normalize("https://localhost/a"))
        assertNull(WebLinks.normalize("https://user@example.com/a"))
        assertNull(WebLinks.normalize("https://example.com/a b"))
        assertNull(WebLinks.normalize(""))
    }

    @Test
    fun `treats two links to the same video or page as duplicates`() {
        val builtIn = listOf("https://www.youtube.com/watch?v=VnV7vEi7Sz8", "https://stronglifts.com/squat/")

        assertTrue(WebLinks.isDuplicate("https://youtu.be/VnV7vEi7Sz8", builtIn))
        assertTrue(WebLinks.isDuplicate("https://stronglifts.com/squat/", builtIn))
        assertFalse(WebLinks.isDuplicate("https://stronglifts.com/deadlift/", builtIn))
    }

    @Test
    fun `shows a user copy of a built-in link once, keeping the built-in`() {
        val all = listOf("https://www.youtube.com/watch?v=VnV7vEi7Sz8", "https://stronglifts.com/squat/", "https://youtu.be/VnV7vEi7Sz8", "http://StrongLifts.com/squat/")

        assertEquals(listOf("https://www.youtube.com/watch?v=VnV7vEi7Sz8", "https://stronglifts.com/squat/"), WebLinks.distinct(all))
    }
}
