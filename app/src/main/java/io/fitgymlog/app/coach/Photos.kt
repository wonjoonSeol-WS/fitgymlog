package io.fitgymlog.app.coach

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A JPEG data: URL no longer than [MAX_SIDE] on its long side: small enough to send, large enough for printed digits. */
suspend fun imageDataUrl(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > MAX_SIDE) {
            decoder.setTargetSize((info.size.width * MAX_SIDE / longest).coerceAtLeast(1), (info.size.height * MAX_SIDE / longest).coerceAtLeast(1))
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }.toByteArray()
    bitmap.recycle()
    "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
}

private const val MAX_SIDE = 2_000
private const val JPEG_QUALITY = 85
