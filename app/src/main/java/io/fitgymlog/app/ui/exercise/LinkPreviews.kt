package io.fitgymlog.app.ui.exercise

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.fitgymlog.app.links.LinkPreview
import io.fitgymlog.app.links.PreviewParser
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/** Small in-memory caches for link cards; the guide screen is opened often mid-workout. */
object LinkPreviews {
    private val images = object : LruCache<String, ImageBitmap>(IMAGE_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmap().byteCount
    }
    private val previews = LruCache<String, LinkPreview>(48)
    private val failures = RecentFailures(FAILURE_MEMORY_MILLIS, capacity = 128, now = SystemClock::elapsedRealtime)

    fun youtubeIdOf(url: String): String? = PreviewParser.youtubeIdOf(url)

    suspend fun image(url: String): ImageBitmap? =
        images.get(url) ?: remembered("image:$url") { download(url) }?.also { images.put(url, it) }

    /**
     * The sharpest still the video has. YouTube only makes the larger sizes for videos uploaded
     * in high resolution and answers 404 for the rest; its 4:3 sizes carry black bars that a 16:9 crop removes.
     */
    suspend fun youtubeStill(id: String): ImageBitmap? {
        val key = "youtube:$id"
        return images.get(key) ?: remembered(key) {
            YOUTUBE_STILLS.firstNotNullOfOrNull { size -> download(PreviewParser.youtubeStillUrl(id, size)) }
        }?.also { images.put(key, it) }
    }

    suspend fun preview(url: String): LinkPreview = previews.get(url) ?: remembered("page:$url") {
        val videoId = youtubeIdOf(url)
        if (videoId != null) {
            fetch(PreviewParser.youtubeOembedUrl(videoId)) { LinkPreview(PreviewParser.oembedTitle(it.text()), null) }
        } else {
            fetch(url) { PreviewParser.page(it.text(), it.url.toString()) }
        }
    }?.also { previews.put(url, it) } ?: LinkPreview(null, null)

    /** Offline, every card would otherwise wait out its timeouts again on each visit. */
    private suspend fun <T : Any> remembered(key: String, load: suspend () -> T?): T? {
        if (failures.isRecent(key)) return null
        val result = try {
            load()
        } catch (e: Exception) {
            null
        }
        currentCoroutineContext().ensureActive()
        if (result == null) failures.record(key) else failures.forget(key)
        return result
    }

    /** Blocking socket reads ignore cancellation, so closing the connection is what ends them. */
    private suspend fun <T> fetch(url: String, read: (HttpURLConnection) -> T): T? = withTimeoutOrNull(REQUEST_DEADLINE_MILLIS) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) FitGymLog")
        }
        val closer = launch(Dispatchers.IO) {
            try {
                awaitCancellation()
            } finally {
                connection.disconnect()
            }
        }
        try {
            runInterruptible(Dispatchers.IO) {
                if (connection.responseCode !in 200..299) null else read(connection)
            }
        } finally {
            closer.cancel()
        }
    }

    private suspend fun download(url: String): ImageBitmap? = fetch(url) { connection ->
        val bytes = connection.inputStream.use { it.readNBytes(MAX_IMAGE_BYTES) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        imageSampleSize(bounds.outWidth, bounds.outHeight)?.let { sample ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }
    }

    private fun HttpURLConnection.text(): String =
        PreviewParser.decode(inputStream.use { it.readNBytes(MAX_HTML_BYTES) }, contentType)

    private val YOUTUBE_STILLS = listOf("maxresdefault", "sddefault", "hqdefault")
    private const val IMAGE_CACHE_BYTES = 32 * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
    private const val MAX_HTML_BYTES = 200_000
    private const val TIMEOUT_MILLIS = 8_000
    private const val REQUEST_DEADLINE_MILLIS = 15_000L
    private const val FAILURE_MEMORY_MILLIS = 60_000L
}

/**
 * Full size up to twice [IMAGE_TARGET_SIDE] on each side so stills stay sharp on an unfolded Fold.
 * Null for sources too large to decode safely.
 */
internal fun imageSampleSize(width: Int, height: Int): Int? {
    if (width <= 0 || height <= 0 || width.toLong() * height > MAX_SOURCE_PIXELS) return null
    var sample = 1
    while (width / (sample * 2) >= IMAGE_TARGET_SIDE || height / (sample * 2) >= IMAGE_TARGET_SIDE) sample *= 2
    return sample
}

private const val IMAGE_TARGET_SIDE = 1280
private const val MAX_SOURCE_PIXELS = 40_000_000L

internal class RecentFailures(private val ttlMillis: Long, private val capacity: Int, private val now: () -> Long) {
    private val failedAt = object : LinkedHashMap<String, Long>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > capacity
    }

    @Synchronized
    fun isRecent(key: String): Boolean {
        val at = failedAt[key] ?: return false
        if (now() - at < ttlMillis) return true
        failedAt.remove(key)
        return false
    }

    @Synchronized
    fun record(key: String) {
        failedAt[key] = now()
    }

    @Synchronized
    fun forget(key: String) {
        failedAt.remove(key)
    }
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
