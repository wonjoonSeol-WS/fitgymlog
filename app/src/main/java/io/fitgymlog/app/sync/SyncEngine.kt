package io.fitgymlog.app.sync

import android.content.Context
import android.content.Intent
import android.util.Log
import io.fitgymlog.app.LOG_TAG
import io.fitgymlog.app.catching
import io.fitgymlog.app.data.SettingsRepository
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SyncStatus {
    data object Off : SyncStatus
    data object Syncing : SyncStatus
    data object NeedsSignIn : SyncStatus
    data class Synced(val at: Instant?) : SyncStatus
    data class Failed(val reason: SyncFailure) : SyncStatus
}

enum class SyncFailure {
    NETWORK,
    SIGN_IN_REJECTED,
    NEWER_APP_FORMAT,
    CORRUPT_SYNC_FILE,
    OTHER,
    ;

    companion object {
        fun of(error: Throwable): SyncFailure = when (error) {
            is DriveAuthException -> SIGN_IN_REJECTED
            is NewerSyncFormatException -> NEWER_APP_FORMAT
            is CorruptSyncFileException -> CORRUPT_SYNC_FILE
            is DriveHttpException -> if (error.code == TOO_MANY_REQUESTS || error.code >= SERVER_ERROR) NETWORK else OTHER
            is IOException -> NETWORK
            else -> OTHER
        }

        private const val TOO_MANY_REQUESTS = 429
        private const val SERVER_ERROR = 500
    }
}

/**
 * Keeps this phone and the Drive copy in step: pulls when the app opens, pushes shortly after
 * anything syncable changes, and never blocks the workout screen.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SyncEngine(
    context: Context,
    private val store: SyncStore,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val auth = DriveAuth(context.applicationContext)
    private val mutex = Mutex()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val waiting = AtomicReference<Job?>(null)

    @Volatile
    private var lastSyncedFingerprint: String? = null

    private val _status = MutableStateFlow(
        if (settings.settings.value.syncEnabled) SyncStatus.Synced(settings.settings.value.lastSyncAt?.let(Instant::ofEpochMilli)) else SyncStatus.Off,
    )
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val enabled: Boolean get() = settings.settings.value.syncEnabled

    init {
        scope.launch { requests.consumeEach { sync() } }
        scope.launch {
            settings.settings.map { it.syncEnabled }.distinctUntilChanged()
                .flatMapLatest { on -> if (on) store.changes.drop(1).debounce(LOCAL_CHANGE_DEBOUNCE_MILLIS) else emptyFlow() }
                .collect { if (fingerprint(store.snapshot()) != lastSyncedFingerprint) requestSync(0) }
        }
    }

    fun requestSync(delayMillis: Long) {
        if (!enabled) return
        val next = scope.launch(start = CoroutineStart.LAZY) {
            delay(delayMillis)
            requests.send(Unit)
        }
        waiting.getAndSet(next)?.cancel()
        next.start()
    }

    /** First step of turning sync on: either a token straight away or a consent screen to show. */
    suspend fun connect(): DriveAuth.Start = auth.start()

    fun finishConnect(data: Intent?): Boolean {
        val token = auth.finish(data) ?: return false
        enableAndSync(token)
        return true
    }

    fun enableAndSync(token: String) {
        settings.update { it.copy(syncEnabled = true, syncAccount = null, lastSyncAt = null) }
        _status.value = SyncStatus.Syncing
        scope.launch { sync(token) }
    }

    /** Turns sync off at once; a sync already running finishes first, then its results are cleared. */
    fun disconnect() {
        waiting.getAndSet(null)?.cancel()
        settings.update { it.copy(syncEnabled = false) }
        _status.value = SyncStatus.Off
        scope.launch {
            mutex.withLock {
                if (enabled) return@withLock
                settings.update { if (it.syncEnabled) it else it.copy(syncAccount = null, lastSyncAt = null) }
                lastSyncedFingerprint = null
                _status.value = SyncStatus.Off
            }
        }
    }

    private suspend fun sync(connectedToken: String? = null) {
        mutex.withLock {
            if (!enabled) return
            _status.value = SyncStatus.Syncing
            val token = connectedToken ?: auth.silentToken()
            val result = if (token == null) SyncStatus.NeedsSignIn else attempt(token, freshAccount = connectedToken != null)
            _status.value = if (enabled) result else SyncStatus.Off
        }
    }

    private suspend fun attempt(token: String, freshAccount: Boolean): SyncStatus =
        catching { syncWith(DriveAppData(token), freshAccount) }.getOrElse { error ->
            if (error !is DriveAuthException) return failure(error)
            auth.forget(token)
            val retryToken = auth.silentToken() ?: return SyncStatus.NeedsSignIn
            catching { syncWith(DriveAppData(retryToken), freshAccount) }.getOrElse(::failure)
        }

    private suspend fun syncWith(drive: DriveAppData, freshAccount: Boolean): SyncStatus {
        val now = Instant.now()
        val remoteFile = drive.find()
        val remote = remoteFile?.let { SyncSnapshot.decode(drive.download(it)) } ?: SyncSnapshot()
        val local = store.snapshot()
        val merged = SyncMerge.merge(local, remote, now)
        val changes = SyncMerge.changes(local, merged)
        if (!enabled) return SyncStatus.Off
        if (changes.touchesData || merged.tombstones != local.tombstones) store.apply(changes, now)
        if (merged != remote) {
            if (remoteFile == null) drive.create(merged.encode()) else drive.update(remoteFile, merged.encode())
        }
        lastSyncedFingerprint = fingerprint(merged)
        val email = settings.settings.value.syncAccount.takeUnless { freshAccount } ?: drive.accountEmail()
        settings.update { if (it.syncEnabled) it.copy(lastSyncAt = now.toEpochMilli(), syncAccount = email) else it }
        return SyncStatus.Synced(now)
    }

    private fun failure(error: Throwable): SyncStatus.Failed {
        Log.w(LOG_TAG, "Sync failed", error)
        return SyncStatus.Failed(SyncFailure.of(error))
    }

    private fun fingerprint(snapshot: SyncSnapshot): String =
        MessageDigest.getInstance("SHA-256").digest(snapshot.encode().toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val LOCAL_CHANGE_DEBOUNCE_MILLIS = 3_000L
    }
}
