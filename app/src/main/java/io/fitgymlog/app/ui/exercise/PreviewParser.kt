package io.fitgymlog.app.ui.exercise

/** Pure parsing for link cards, kept free of Android so it can be tested on the JVM. */
object PreviewParser {
    private val youtubeId = Regex("""(?:youtube\.com/(?:watch\?(?:.*&)?v=|shorts/|embed/)|youtu\.be/)([A-Za-z0-9_-]{11})""")
    private val metaTag = Regex("""<meta\s[^>]*>""", RegexOption.IGNORE_CASE)
    private val htmlTitle = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
    private val oembedTitle = Regex(""""title"\s*:\s*"((?:[^"\\]|\\.)*)"""")

    fun youtubeIdOf(url: String): String? = youtubeId.find(url)?.groupValues?.get(1)

    /** Open Graph title and image, whichever order a site writes the attributes in, falling back to the page title. */
    fun page(html: String): LinkPreview {
        var title: String? = null
        var image: String? = null
        metaTag.findAll(html).forEach { match ->
            val tag = match.value
            val content = attribute(tag, "content") ?: return@forEach
            when ((attribute(tag, "property") ?: attribute(tag, "name"))?.lowercase()) {
                "og:title" -> if (title == null) title = content
                "og:image" -> if (image == null) image = content
            }
        }
        return LinkPreview(
            title = (title ?: htmlTitle.find(html)?.groupValues?.get(1))?.let(::unescapeHtml)?.trim(),
            imageUrl = image?.let(::unescapeHtml),
        )
    }

    /** The video title from YouTube's oEmbed JSON. */
    fun oembedTitle(json: String): String? = oembedTitle.find(json)?.groupValues?.get(1)?.let(::unescapeJson)

    private fun attribute(tag: String, name: String): String? =
        Regex("""\b$name\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(2)

    private fun unescapeHtml(text: String) = text
        .replace("&amp;", "&").replace("&#39;", "'").replace("&#039;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">")

    private fun unescapeJson(text: String): String = Regex("""\\(u[0-9a-fA-F]{4}|.)""").replace(text) { match ->
        val escape = match.groupValues[1]
        when {
            escape.startsWith("u") -> escape.drop(1).toInt(16).toChar().toString()
            escape == "n" -> "\n"
            else -> escape
        }
    }
}
