package io.fitgymlog.app

import kotlin.coroutines.cancellation.CancellationException

/** Like runCatching, but lets coroutine cancellation through instead of reporting it as a failure. */
inline fun <T> catching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
