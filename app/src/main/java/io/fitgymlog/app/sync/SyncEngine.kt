package io.fitgymlog.app.sync

import android.content.Context
import android.content.Intent
import io.fitgymlog.app.data.SettingsRepository
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SyncStatus {
    data object Off : SyncStatus
    data object Syncing : SyncStatus
    data object NeedsSignIn : SyncStatus
    data class Synced(val at: Instant?) : SyncStatus
    data class Failed(val reason: String) : SyncStatus
}

/**
 * Keeps this phone and the Drive copy in step: pulls when the app opens, pushes shortly after
 * anything syncable changes, and never blocks the workout screen.
 */
@OptIn(FlowPreview::class)
class SyncEngine(
    context: Context,
    private val store: SyncStore,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val auth = DriveAuth(context.applicationContext)
    private val mutex = Mutex()
    private var pending: Job? = null
    private var lastSyncedFingerprint: String? = null

    private val _status = MutableStateFlow(
        if (settings.settings.value.syncEnabled) SyncStatus.Synced(settings.settings.value.lastSyncAt?.let(Instant::ofEpochMilli)) else SyncStatus.Off,
    )
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    init {
        scope.launch {
            store.changes.drop(1).debounce(LOCAL_CHANGE_DEBOUNCE_MILLIS).collect {
                if (settings.settings.value.syncEnabled && fingerprint(store.snapshot()) != lastSyncedFingerprint) requestSync(0)
            }
        }
    }

    fun requestSync(delayMillis: Long = REQUEST_DELAY_MILLIS) {
        if (!settings.settings.value.syncEnabled) return
        pending?.cancel()
        pending = scope.launch {
            delay(delayMillis)
            sync()
        }
    }

    /** First step of turning sync on: either a token straight away or a consent screen to show. */
    suspend fun connect(): DriveAuth.Start = auth.start()

    suspend fun finishConnect(data: Intent?): Boolean {
        val token = auth.finish(data) ?: return false
        enableAndSync(token)
        return true
    }

    suspend fun enableAndSync(token: String) {
        settings.update { it.copy(syncEnabled = true) }
        sync(token)
    }

    fun disconnect() {
        pending?.cancel()
        settings.update { it.copy(syncEnabled = false, syncAccount = null, lastSyncAt = null) }
        lastSyncedFingerprint = null
        _status.value = SyncStatus.Off
    }

    suspend fun sync(knownToken: String? = null) {
        mutex.withLock {
            if (!settings.settings.value.syncEnabled) return
            _status.value = SyncStatus.Syncing
            val token = knownToken ?: auth.silentToken() ?: run {
                _status.value = SyncStatus.NeedsSignIn
                return
            }
            _status.value = try {
                syncWith(DriveAppData(token))
            } catch (rejected: DriveAuthException) {
                auth.forget(token)
                auth.silentToken()?.let { retryToken -> runCatching { syncWith(DriveAppData(retryToken)) }.getOrElse { failure(it) } }
                    ?: SyncStatus.NeedsSignIn
            } catch (error: Exception) {
                failure(error)
            }
        }
    }

    private suspend fun syncWith(drive: DriveAppData): SyncStatus {
        val now = Instant.now()
        val remoteFile = drive.find()
        val remote = remoteFile?.let { SyncSnapshot.decode(drive.download(it)) } ?: SyncSnapshot()
        val local = store.snapshot()
        val merged = SyncMerge.merge(local, remote, now)
        val changes = SyncMerge.changes(local, merged)
        if (changes.touchesData || merged.tombstones != local.tombstones) store.apply(changes, now)
        if (merged != remote) {
            if (remoteFile == null) drive.create(merged.encode()) else drive.update(remoteFile, merged.encode())
        }
        lastSyncedFingerprint = fingerprint(merged)
        val email = settings.settings.value.syncAccount ?: drive.accountEmail()
        settings.update { it.copy(lastSyncAt = now.toEpochMilli(), syncAccount = email) }
        return SyncStatus.Synced(now)
    }

    private fun failure(error: Throwable) = SyncStatus.Failed(error.message ?: error.javaClass.simpleName)

    private fun fingerprint(snapshot: SyncSnapshot): String =
        MessageDigest.getInstance("SHA-256").digest(snapshot.encode().toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val REQUEST_DELAY_MILLIS = 1_500L
        const val LOCAL_CHANGE_DEBOUNCE_MILLIS = 3_000L
    }
}
