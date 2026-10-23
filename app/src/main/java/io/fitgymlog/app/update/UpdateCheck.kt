package io.fitgymlog.app.update

import io.fitgymlog.app.catching
import io.fitgymlog.app.exchangeCancellable
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

sealed interface UpdateCheck {
    data class UpToDate(val version: String) : UpdateCheck

    data class Available(val version: String, val url: String) : UpdateCheck

    data object Failed : UpdateCheck
}

/**
 * Asks GitHub for the latest release, only when the user taps to check: the app makes no other request to GitHub,
 * and installs from the APK, so nothing else would tell the user a new version is out.
 */
object Updates {
    private const val LATEST = "https://api.github.com/repos/wonjoonSeol/fitgymlog/releases/latest"
    private const val TIMEOUT_MILLIS = 10_000
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Release(@SerialName("tag_name") val tag: String, @SerialName("html_url") val url: String)

    suspend fun check(current: String): UpdateCheck = catching {
        val connection = URL(LATEST).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        // GitHub turns away requests without a User-Agent.
        connection.setRequestProperty("User-Agent", "FitGymLog/$current")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        val release = connection.exchangeCancellable { json.decodeFromString(Release.serializer(), it.inputStream.bufferedReader().readText()) }
        val latest = release.tag.removePrefix("v")
        if (isNewer(latest, current)) UpdateCheck.Available(latest, release.url) else UpdateCheck.UpToDate(current)
    }.getOrDefault(UpdateCheck.Failed)
}

/** Compares dotted versions number by number, so 0.10.0 is newer than 0.9.1; a suffix such as -beta is ignored. */
internal fun isNewer(latest: String, current: String): Boolean {
    fun parts(version: String) = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val a = parts(latest)
    val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}
