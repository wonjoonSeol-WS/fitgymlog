package io.fitgymlog.app.ui.exercise

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewParserTest {
    @Test
    fun `finds youtube ids in watch, short and shorts links`() {
        assertEquals("VnV7vEi7Sz8", PreviewParser.youtubeIdOf("https://www.youtube.com/watch?v=VnV7vEi7Sz8"))
        assertEquals("VnV7vEi7Sz8", PreviewParser.youtubeIdOf("https://www.youtube.com/watch?feature=share&v=VnV7vEi7Sz8"))
        assertEquals("VnV7vEi7Sz8", PreviewParser.youtubeIdOf("https://youtu.be/VnV7vEi7Sz8?t=30"))
        assertEquals("VnV7vEi7Sz8", PreviewParser.youtubeIdOf("https://youtube.com/shorts/VnV7vEi7Sz8"))
        assertNull(PreviewParser.youtubeIdOf("https://stronglifts.com/squat/"))
    }

    @Test
    fun `reads open graph tags in either attribute order`() {
        val stronglifts = """<meta property="og:title" content="How to Squat with Proper Form: The Definitive Guide | Stronglifts">
            <meta property="og:image" content="https://stronglifts.com/wp-content/uploads/stronglifts-squat.jpg">"""
        val reversed = """<meta content="Bench &amp; Press" property='og:title' /><meta content="https://x.test/b.jpg" property="og:image">"""

        assertEquals(
            LinkPreview("How to Squat with Proper Form: The Definitive Guide | Stronglifts", "https://stronglifts.com/wp-content/uploads/stronglifts-squat.jpg"),
            PreviewParser.page(stronglifts),
        )
        assertEquals(LinkPreview("Bench & Press", "https://x.test/b.jpg"), PreviewParser.page(reversed))
    }

    @Test
    fun `falls back to the page title`() {
        assertEquals(LinkPreview("Lat pulldown guide", null), PreviewParser.page("<html><title> Lat pulldown guide </title></html>"))
    }

    @Test
    fun `reads the oembed title with escapes`() {
        val json = """{"title":"How To Squat: StrongLifts Shows \"Proper\" Squat Form & more","author_name":"StrongLifts"}"""

        assertEquals("How To Squat: StrongLifts Shows \"Proper\" Squat Form & more", PreviewParser.oembedTitle(json))
    }
}
