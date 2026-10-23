package io.fitgymlog.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService

/** Started by Google Play services when the phone publishes, so the ongoing workout shows even with the app closed. */
class StateListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { event ->
            when (event.type) {
                DataEvent.TYPE_CHANGED -> PhoneLink.received(event.dataItem, live = true)
                DataEvent.TYPE_DELETED -> PhoneLink.cleared()
            }
        }
        OngoingWorkout.sync(this, PhoneLink.state.value?.workout)
    }
}
