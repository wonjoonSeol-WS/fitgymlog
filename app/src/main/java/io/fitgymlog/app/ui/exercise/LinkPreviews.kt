package io.fitgymlog.app.ui.exercise

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LinkPreview(val title: String?, val imageUrl: String?)

/** Small in-memory caches for link cards; the guide screen is opened often mid-workout. */
object LinkPreviews {
    private val images = LruCache<String, ImageBitmap>(24)
    private val previews = LruCache<String, LinkPreview>(48)

    fun youtubeIdOf(url: String): String? = PreviewParser.youtubeIdOf(url)

    fun youtubeThumbnail(id: String) = "https://img.youtube.com/vi/$id/hqdefault.jpg"

    suspend fun image(url: String): ImageBitmap? = images.get(url) ?: withContext(Dispatchers.IO) {
        runCatching {
            open(url).inputStream.use { stream ->
                val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeStream(stream, null, options)?.asImageBitmap()
            }
        }.getOrNull()?.also { images.put(url, it) }
    }

    /** YouTube serves a consent page to plain requests, so video titles come from its oEmbed endpoint instead. */
    suspend fun preview(url: String): LinkPreview = previews.get(url) ?: withContext(Dispatchers.IO) {
        runCatching {
            if (youtubeIdOf(url) != null) {
                val json = read("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(url, "UTF-8"))
                LinkPreview(PreviewParser.oembedTitle(json), null)
            } else {
                PreviewParser.page(read(url))
            }
        }.getOrNull()?.also { previews.put(url, it) } ?: LinkPreview(null, null)
    }

    private fun read(url: String): String = open(url).inputStream.use { it.readNBytes(MAX_HTML_BYTES).decodeToString() }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MILLIS
        readTimeout = TIMEOUT_MILLIS
        setRequestProperty("User-Agent", "Mozilla/5.0 (Android) FitGymLog")
    }

    private const val MAX_HTML_BYTES = 200_000
    private const val TIMEOUT_MILLIS = 8_000
}

@Composable
fun rememberRemoteImage(url: String?): State<ImageBitmap?> = produceState<ImageBitmap?>(null, url) {
    value = url?.let { LinkPreviews.image(it) }
}

@Composable
fun rememberLinkPreview(url: String): State<LinkPreview?> = produceState<LinkPreview?>(null, url) {
    value = LinkPreviews.preview(url)
}
