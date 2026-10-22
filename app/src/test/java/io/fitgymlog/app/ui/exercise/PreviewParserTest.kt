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
    fun `finds youtube ids on every youtube host and in embed and live paths`() {
        assertEquals("VnV7vEi7Sz8", PreviewParser.youtubeIdOf("https://m.youtube.com/watch?v=VnV7vEi7Sz8"))
        assertEquals("VnV7vEi7Sz8", PreviewParser.youtubeIdOf("https://music.youtube.com/watch?v=VnV7vEi7Sz8&list=RD"))
        assertEquals("VnV7vEi7Sz8", PreviewParser.youtubeIdOf("https://www.youtube-nocookie.com/embed/VnV7vEi7Sz8"))
        assertEquals("VnV7vEi7Sz8", PreviewParser.youtubeIdOf("https://www.youtube.com/live/VnV7vEi7Sz8?si=abc"))
        assertEquals("VnV7vEi7Sz8", PreviewParser.youtubeIdOf("HTTPS://YOUTU.BE/VnV7vEi7Sz8"))
    }

    @Test
    fun `ignores youtube-looking paths on other hosts`() {
        assertNull(PreviewParser.youtubeIdOf("https://notyoutube.com/watch?v=VnV7vEi7Sz8"))
        assertNull(PreviewParser.youtubeIdOf("https://evil.test/?u=youtu.be/VnV7vEi7Sz8"))
        assertNull(PreviewParser.youtubeIdOf("https://evil.test/youtube.com/shorts/VnV7vEi7Sz8"))
        assertNull(PreviewParser.youtubeIdOf("https://youtube.com@evil.test/watch?v=VnV7vEi7Sz8"))
        assertNull(PreviewParser.youtubeIdOf("https://www.youtube.com/watch?v=VnV7vEi7Sz8extra"))
    }

    @Test
    fun `builds the oembed request from the video id rather than the pasted link`() {
        assertEquals(
            "https://www.youtube.com/oembed?format=json&url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3DVnV7vEi7Sz8",
            PreviewParser.youtubeOembedUrl(PreviewParser.youtubeIdOf("https://youtube.com/shorts/VnV7vEi7Sz8?si=tracking")!!),
        )
    }

    @Test
    fun `serves every youtube still from one host`() {
        assertEquals("https://i.ytimg.com/vi/VnV7vEi7Sz8/mqdefault.jpg", PreviewParser.youtubeStillUrl("VnV7vEi7Sz8", "mqdefault"))
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
    fun `falls back to the page title when og title is empty`() {
        val html = """<meta property="og:title" content="  "><title>Lat pulldown guide</title>"""

        assertEquals("Lat pulldown guide", PreviewParser.page(html).title)
    }

    @Test
    fun `unescapes entities once, including numeric ones`() {
        val html = """<meta property="og:title" content="Squat &amp;lt;3 &#8211; Rack &#x27;Pull&#39; &amp;amp;">"""

        assertEquals("Squat &lt;3 – Rack 'Pull' &amp;", PreviewParser.page(html).title)
    }

    @Test
    fun `does not read data-content as the content attribute`() {
        val html = """<meta data-content="Wrong" property="og:title" content="Right">"""

        assertEquals("Right", PreviewParser.page(html).title)
    }

    @Test
    fun `resolves relative and protocol-relative images against the page`() {
        val page = "https://example.com/guides/squat"

        assertEquals("https://example.com/img/squat.jpg", PreviewParser.page("""<meta property="og:image" content="/img/squat.jpg">""", page).imageUrl)
        assertEquals("https://example.com/guides/squat.jpg", PreviewParser.page("""<meta property="og:image" content="squat.jpg">""", page).imageUrl)
        assertEquals("https://cdn.example.com/squat.jpg", PreviewParser.page("""<meta property="og:image" content="//cdn.example.com/squat.jpg">""", page).imageUrl)
        assertEquals("https://example.com/squat.jpg", PreviewParser.page("""<meta property="og:image" content="squat.jpg">""", "https://example.com").imageUrl)
    }

    @Test
    fun `loads images over https only`() {
        assertEquals("https://example.com/a.jpg", PreviewParser.page("""<meta property="og:image" content="http://example.com/a.jpg">""").imageUrl)
        assertNull(PreviewParser.page("""<meta property="og:image" content="data:image/png;base64,AAAA">""").imageUrl)
        assertNull(PreviewParser.page("""<meta property="og:image" content="/img/squat.jpg">""").imageUrl)
    }

    @Test
    fun `reads the oembed title with escapes`() {
        val json = """{"title":"How To Squat: StrongLifts Shows \"Proper\" Squat Form & more","author_name":"StrongLifts"}"""

        assertEquals("How To Squat: StrongLifts Shows \"Proper\" Squat Form & more", PreviewParser.oembedTitle(json))
    }

    @Test
    fun `reads tab, return, backspace and form feed escapes in the oembed title`() {
        val json = """{"title":"Squat\tDay\r\nPart\b1\f2 é"}"""

        assertEquals("Squat\tDay\r\nPart\b1\u000C2 é", PreviewParser.oembedTitle(json))
    }

    @Test
    fun `decodes a page in the charset its server declares`() {
        val body = "<title>스쿼트 자세</title>".toByteArray(charset("EUC-KR"))

        assertEquals("스쿼트 자세", PreviewParser.page(PreviewParser.decode(body, "text/html; charset=EUC-KR")).title)
    }

    @Test
    fun `decodes a page in the charset its meta tag declares`() {
        val body = """<meta http-equiv="Content-Type" content="text/html; charset=euc-kr"><title>벤치 프레스</title>""".toByteArray(charset("EUC-KR"))

        assertEquals("벤치 프레스", PreviewParser.page(PreviewParser.decode(body, "text/html")).title)
        assertEquals("벤치", PreviewParser.decode("<meta charset=\"utf-8\">벤치".toByteArray(), null).substringAfter('>'))
    }
}
