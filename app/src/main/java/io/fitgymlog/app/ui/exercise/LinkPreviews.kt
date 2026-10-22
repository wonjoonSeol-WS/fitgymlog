package io.fitgymlog.app.ui.exercise

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LinkPreview(val title: String?, val imageUrl: String?)

/** Small in-memory caches for link cards; the guide screen is opened often mid-workout. */
object LinkPreviews {
    private val images = object : LruCache<String, ImageBitmap>(IMAGE_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmap().byteCount
    }
    private val previews = LruCache<String, LinkPreview>(48)

    fun youtubeIdOf(url: String): String? = PreviewParser.youtubeIdOf(url)

    suspend fun image(url: String): ImageBitmap? = cached(url) { download(url) }

    /**
     * The sharpest still the video has. YouTube only makes the larger sizes for videos uploaded
     * in high resolution and answers 404 for the rest; its 4:3 sizes carry black bars that a 16:9 crop removes.
     */
    suspend fun youtubeStill(id: String): ImageBitmap? = cached("youtube:$id") {
        YOUTUBE_STILLS.firstNotNullOfOrNull { size -> download("https://i.ytimg.com/vi/$id/$size.jpg") }
    }

    private suspend fun cached(key: String, load: () -> ImageBitmap?): ImageBitmap? =
        images.get(key) ?: withContext(Dispatchers.IO) { load() }?.also { images.put(key, it) }

    /** Full size so stills stay sharp on an unfolded Fold; only images over twice [MAX_IMAGE_WIDTH] are halved down. */
    private fun download(url: String): ImageBitmap? = runCatching {
        val bytes = open(url).inputStream.use { it.readNBytes(MAX_IMAGE_BYTES) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= MAX_IMAGE_WIDTH) sampleSize *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize })?.asImageBitmap()
    }.getOrNull()

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

    private val YOUTUBE_STILLS = listOf("maxresdefault", "sddefault", "hqdefault")
    private const val IMAGE_CACHE_BYTES = 32 * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
    private const val MAX_IMAGE_WIDTH = 1280
    private const val MAX_HTML_BYTES = 200_000
    private const val TIMEOUT_MILLIS = 8_000
}

@Composable
fun rememberRemoteImage(url: String?): State<ImageBitmap?> = produceState<ImageBitmap?>(null, url) {
    value = url?.let { LinkPreviews.image(it) }
}

@Composable
fun rememberYoutubeStill(id: String): State<ImageBitmap?> = produceState<ImageBitmap?>(null, id) {
    value = LinkPreviews.youtubeStill(id)
}

@Composable
fun rememberLinkPreview(url: String): State<LinkPreview?> = produceState<LinkPreview?>(null, url) {
    value = LinkPreviews.preview(url)
}
