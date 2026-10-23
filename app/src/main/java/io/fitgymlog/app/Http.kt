package io.fitgymlog.app

import java.net.HttpURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/** Blocking socket reads ignore cancellation, so closing the connection ends them; the error that follows is reported as the cancellation. */
suspend fun <T> HttpURLConnection.exchangeCancellable(exchange: (HttpURLConnection) -> T): T = coroutineScope {
    val closer = launch(Dispatchers.IO) {
        try {
            awaitCancellation()
        } finally {
            disconnect()
        }
    }
    try {
        runInterruptible(Dispatchers.IO) { exchange(this@exchangeCancellable) }
    } catch (error: Exception) {
        ensureActive()
        throw error
    } finally {
        closer.cancel()
    }
}
