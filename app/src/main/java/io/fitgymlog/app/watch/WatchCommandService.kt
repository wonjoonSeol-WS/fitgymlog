package io.fitgymlog.app.watch

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import io.fitgymlog.app.LOG_TAG
import io.fitgymlog.app.catching
import io.fitgymlog.app.container
import io.fitgymlog.watchlink.WatchCodec
import io.fitgymlog.watchlink.WatchPaths
import kotlinx.coroutines.runBlocking

/** Google Play services starts this for each tap on the watch, even when the app isn't running. */
class WatchCommandService : WearableListenerService() {
    // Messages arrive on a background thread, one at a time, so waiting here keeps the watch's taps in order.
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchPaths.COMMAND) return
        val command = WatchCodec.decodeCommand(event.data) ?: return
        val container = container
        catching { runBlocking { container.inAppScope { container.watch.handle(command) } } }
            .onFailure { Log.w(LOG_TAG, "Watch command failed: $command", it) }
    }
}
