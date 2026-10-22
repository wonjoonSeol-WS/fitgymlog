package io.fitgymlog.app.links

import java.net.URI

object WebLinks {
    /**
     * The https form of a typed or pasted web link, or null when it is not one. The app blocks
     * cleartext traffic, so an http link is upgraded rather than stored as a card that never loads.
     */
    fun normalize(input: String): String? {
        val text = input.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        val withScheme = when {
            "://" in text -> text
            text.startsWith("//") -> "https:$text"
            else -> "https://$text"
        }
        val escaped = withScheme.map { if (it in UNSAFE) "%%%02X".format(it.code) else it.toString() }.joinToString("")
        val uri = runCatching { URI(escaped) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (uri.rawUserInfo != null) return null
        val host = uri.host?.lowercase()?.removeSuffix(".") ?: return null
        if ('.' !in host || host.startsWith('.')) return null
        return buildString {
            append("https://").append(host)
            if (uri.port != -1) append(':').append(uri.port)
            append(uri.rawPath.orEmpty())
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        }
    }

    fun isDuplicate(link: String, existing: List<String>): Boolean = existing.any { sameTarget(it, link) }

    /** Built-in links come first in allLinks, so a user copy of one is the copy dropped. */
    fun distinct(links: List<String>): List<String> = links.distinctBy(::target)

    private const val UNSAFE = "\"<>\\^`{|}"

    private fun sameTarget(a: String, b: String) = target(a) == target(b)

    private fun target(url: String): String =
        PreviewParser.youtubeIdOf(url)?.let { "youtube:$it" } ?: normalize(url) ?: url.trim()
}
