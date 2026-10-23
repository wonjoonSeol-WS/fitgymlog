package io.fitgymlog.app.coach

import java.net.URI

/**
 * An address on this phone or the local network, where servers like Ollama or LM Studio answer over plain http.
 * Tailscale's 100.64.0.0/10 counts, as its traffic is already encrypted.
 */
internal fun isLocalHost(host: String): Boolean {
    val name = host.lowercase().removePrefix("[").removeSuffix("]").removeSuffix(".")
    if (name.isEmpty()) return false
    if (':' in name) return name == "::1" || name.startsWith("fc") || name.startsWith("fd") || name.take(3) in setOf("fe8", "fe9", "fea", "feb")
    val octets = name.split('.').map { it.toIntOrNull() }
    if (octets.size == 4 && octets.all { it != null && it in 0..255 }) {
        val (a, b) = octets.requireNoNulls()
        return a == 127 || a == 10 || a == 172 && b in 16..31 || a == 192 && b == 168 || a == 169 && b == 254 || a == 100 && b in 64..127
    }
    if (name.all { it.isDigit() || it == '.' }) return false
    return '.' !in name || name == "localhost" || LOCAL_SUFFIXES.any { name.endsWith(it) }
}

/** The local address as typed, keeping http or https and defaulting to http; null when it is not local or not an address. */
internal fun localBaseUrlOf(input: String): String? {
    val text = input.trim()
    if (text.isEmpty() || text.any { it.isWhitespace() }) return null
    val withScheme = if ("://" in text) text else "http://$text"
    val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
    val host = uri.host ?: return null
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || !isLocalHost(host)) return null
    val port = if (uri.port == -1) "" else ":${uri.port}"
    return "$scheme://${host.lowercase()}$port${uri.rawPath.orEmpty()}".trimEnd('/')
}

private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".lan", ".home.arpa", ".internal")
