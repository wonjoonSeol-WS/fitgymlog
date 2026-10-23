package io.fitgymlog.wear

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import io.fitgymlog.watchlink.WatchCodec
import io.fitgymlog.watchlink.WatchCommand
import io.fitgymlog.watchlink.WatchPaths
import io.fitgymlog.watchlink.WatchState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/** What the phone last published, and the way back to it. One per process, shared by the screen and the listener. */
object PhoneLink {
    private const val TAG = "FitGymLogWatch"

    private val _state = MutableStateFlow<WatchState?>(null)

    /** Null until the phone has published anything, such as right after installing. */
    val state: StateFlow<WatchState?> = _state.asStateFlow()

    private val _unreachable = MutableStateFlow(false)
    val unreachable: StateFlow<Boolean> = _unreachable.asStateFlow()

    /** How far the phone's clock is ahead of this one; rest ends and workout starts are in the phone's time. */
    @Volatile
    private var phoneAheadMillis = 0L

    fun phoneNow(): Long = System.currentTimeMillis() + phoneAheadMillis

    /**
     * [live] for an update that has just arrived, whose send time is a fair reading of the phone's clock; an item read
     * back from storage may be hours old, so it leaves the reading alone.
     */
    fun received(item: DataItem, live: Boolean) {
        if (item.uri.path != WatchPaths.STATE) return
        val state = item.data?.let(WatchCodec::decodeState) ?: return
        if (live && state.sentAtMillis > 0) phoneAheadMillis = state.sentAtMillis - System.currentTimeMillis()
        _state.value = state
    }

    /** The phone left Wear OS mode and took its item back. */
    fun cleared() {
        _state.value = null
    }

    /** The Data Layer keeps the last item on the watch, so this works with the phone out of reach too. */
    suspend fun load(context: Context) {
        val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(WatchPaths.STATE).build()
        safely {
            val items = Wearable.getDataClient(context).getDataItems(uri, DataClient.FILTER_LITERAL).await()
            try {
                if (items.count == 0) cleared() else items.forEach { received(it, live = false) }
            } finally {
                items.release()
            }
        }
    }

    /** The phone is looked up once and remembered, so a tap goes straight out; a failed send looks again and retries. */
    @Volatile
    private var phoneId: String? = null

    suspend fun send(context: Context, command: WatchCommand) {
        val bytes = WatchCodec.encode(command)
        val sent = safely { sendTo(context, phoneId ?: findPhone(context), bytes) }
            ?: safely { phoneId = null; sendTo(context, findPhone(context), bytes) }
        _unreachable.value = sent != true
    }

    /**
     * The phone that declares FitGymLog's capability, or failing that the nearby connected phone: capability lists
     * can take minutes to reach the watch after an install, and a phone without the app just drops the message.
     */
    private suspend fun findPhone(context: Context): String? {
        val declared = Wearable.getCapabilityClient(context).getCapability(WatchPaths.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes
        val nodes = declared.ifEmpty { Wearable.getNodeClient(context).connectedNodes.await().toSet() }
        return (nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull())?.id.also { phoneId = it }
    }

    private suspend fun sendTo(context: Context, phone: String?, bytes: ByteArray): Boolean {
        if (phone == null) return false
        Wearable.getMessageClient(context).sendMessage(phone, WatchPaths.COMMAND, bytes).await()
        return true
    }

    private inline fun <T> safely(block: () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(TAG, "Data Layer call failed", error)
        null
    }
}
