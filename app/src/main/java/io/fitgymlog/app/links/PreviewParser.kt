package io.fitgymlog.app.links

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.Charset

data class LinkPreview(val title: String?, val imageUrl: String?)

object PreviewParser {
    private val YOUTUBE_HOSTS = setOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "music.youtube.com",
        "youtube-nocookie.com",
        "www.youtube-nocookie.com",
    )
    private val VIDEO_PATHS = setOf("shorts", "embed", "live", "v")
    private val webUrl = Regex("""(?i:https?)://([^/?#\s]+)([^?#\s]*)(?:\?([^#\s]*))?(?:#\S*)?""")
    private val videoId = Regex("""[A-Za-z0-9_-]{11}""")
    private val metaTag = Regex("""<meta\s[^>]*>""", RegexOption.IGNORE_CASE)
    private val attribute = Regex("""([^\s=/>"']+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+)))?""")
    private val htmlTitle = Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE)
    private val oembedTitle = Regex(""""title"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val jsonEscape = Regex("""\\(u[0-9a-fA-F]{4}|.)""")
    private val entity = Regex("""&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]+);""")
    private val NAMED_ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ")
    private val headerCharset = Regex("""charset\s*=\s*["']?([A-Za-z0-9._:-]+)""", RegexOption.IGNORE_CASE)
    private val metaCharset = Regex("""<meta[^>]+charset\s*=\s*["']?\s*([A-Za-z0-9._:-]+)""", RegexOption.IGNORE_CASE)

    // Browsers look for <meta charset> only in the first 1024 bytes (HTML encoding sniffing prescan).
    private const val CHARSET_PRESCAN_BYTES = 1024

    fun youtubeIdOf(url: String): String? {
        val parts = webUrl.matchEntire(url.trim()) ?: return null
        val (authority, path, query) = parts.destructured
        if ('@' in authority) return null
        val host = authority.substringBefore(':').lowercase().removeSuffix(".")
        val segments = path.split('/').filter { it.isNotEmpty() }
        val candidate = when {
            host == "youtu.be" -> segments.firstOrNull()
            host !in YOUTUBE_HOSTS -> null
            segments == listOf("watch") -> queryParameter(query, "v")
            segments.size >= 2 && segments[0] in VIDEO_PATHS -> segments[1]
            else -> null
        }
        return candidate?.takeIf { videoId.matches(it) }
    }

    fun youtubeStillUrl(id: String, size: String): String = "https://i.ytimg.com/vi/$id/$size.jpg"

    /** YouTube serves a consent page to plain requests, so video titles come from its oEmbed endpoint instead. */
    fun youtubeOembedUrl(id: String): String =
        "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode("https://www.youtube.com/watch?v=$id", "UTF-8")

    fun page(html: String, pageUrl: String? = null): LinkPreview {
        var title: String? = null
        var image: String? = null
        metaTag.findAll(html).forEach { match ->
            val attributes = attributesOf(match.value)
            val content = attributes["content"]?.let(::unescapeHtml)?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            when ((attributes["property"] ?: attributes["name"])?.lowercase()) {
                "og:title" -> if (title == null) title = content
                "og:image" -> if (image == null) image = content
            }
        }
        return LinkPreview(title = title ?: titleTag(html), imageUrl = image?.let { resolveImage(it, pageUrl) })
    }

    private fun titleTag(html: String): String? =
        htmlTitle.find(html)?.groupValues?.get(1)?.let(::unescapeHtml)?.trim()?.takeIf { it.isNotEmpty() }

    fun oembedTitle(json: String): String? = oembedTitle.find(json)?.groupValues?.get(1)?.let(::unescapeJson)

    fun decode(body: ByteArray, contentType: String?): String {
        val declared = contentType?.let { headerCharset.find(it)?.groupValues?.get(1) }
            ?: metaCharset.find(String(body, 0, minOf(body.size, CHARSET_PRESCAN_BYTES), Charsets.ISO_8859_1))?.groupValues?.get(1)
        val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        return String(body, charset)
    }

    private fun queryParameter(query: String, name: String): String? =
        query.split('&').firstNotNullOfOrNull { pair ->
            val (key, value) = pair.split('=', limit = 2).takeIf { it.size == 2 } ?: return@firstNotNullOfOrNull null
            value.takeIf { key == name }
        }

    private fun attributesOf(tag: String): Map<String, String> {
        val attributes = mutableMapOf<String, String>()
        attribute.findAll(tag, startIndex = "<meta".length).forEach { match ->
            val value = match.groups[2]?.value ?: match.groups[3]?.value ?: match.groups[4]?.value ?: return@forEach
            attributes.putIfAbsent(match.groupValues[1].lowercase(), value)
        }
        return attributes
    }

    /** Images load over https only, since the app's cleartext traffic is blocked. */
    private fun resolveImage(src: String, pageUrl: String?): String? {
        val image = runCatching {
            val relative = URI(src.replace(" ", "%20"))
            when {
                relative.isAbsolute -> relative
                pageUrl == null -> return null
                else -> URI(pageUrl).let { base -> if (base.rawPath.isNullOrEmpty()) base.resolve("/") else base }.resolve(relative)
            }
        }.getOrNull() ?: return null
        val scheme = image.scheme?.lowercase()
        if ((scheme != "http" && scheme != "https") || image.host == null) return null
        return "https" + image.toString().substring(scheme.length)
    }

    private fun unescapeHtml(text: String): String = entity.replace(text) { match ->
        val name = match.groupValues[1]
        val decoded = when {
            name.startsWith("#x") || name.startsWith("#X") -> codePoint(name.drop(2).toInt(16))
            name.startsWith("#") -> codePoint(name.drop(1).toInt())
            else -> NAMED_ENTITIES[name]
        }
        decoded ?: match.value
    }

    private fun codePoint(value: Int): String? =
        value.takeIf { it > 0 && Character.isValidCodePoint(it) && it !in Char.MIN_SURROGATE.code..Char.MAX_SURROGATE.code }
            ?.let { String(Character.toChars(it)) }

    private fun unescapeJson(text: String): String = jsonEscape.replace(text) { match ->
        val escape = match.groupValues[1]
        when {
            escape.length == 5 -> escape.drop(1).toInt(16).toChar().toString()
            escape == "n" -> "\n"
            escape == "t" -> "\t"
            escape == "r" -> "\r"
            escape == "b" -> "\b"
            escape == "f" -> "\u000C"
            else -> escape
        }
    }
}
