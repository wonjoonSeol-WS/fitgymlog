package io.fitgymlog.app.transfer

import java.io.IOException
import java.io.InputStream

enum class TransferFailure { UNRECOGNIZED, NEWER_BACKUP, MALFORMED_BACKUP, TOO_LARGE, IO, UNKNOWN }

class TransferException(val failure: TransferFailure, message: String, cause: Throwable? = null) : Exception(message, cause)

fun Throwable.transferFailure(): TransferFailure = when (this) {
    is TransferException -> failure
    is IOException, is SecurityException -> TransferFailure.IO
    else -> TransferFailure.UNKNOWN
}

const val MAX_IMPORT_MEGABYTES = 20
internal const val MAX_IMPORT_BYTES = MAX_IMPORT_MEGABYTES * 1024 * 1024

internal fun InputStream.readAtMost(limit: Int): ByteArray {
    val bytes = readNBytes(limit + 1)
    if (bytes.size > limit) throw TransferException(TransferFailure.TOO_LARGE, "file is larger than $limit bytes")
    return bytes
}
