package io.fitgymlog.app.sync

import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** Data that syncs through a Drive file of its own, only while the user has it switched on. */
interface SideSync {
    val fileName: String
    val enabled: Boolean
    val changes: Flow<Unit>

    suspend fun changedSinceSync(): Boolean

    /** Merges the Drive copy, null when there is none yet, into this phone; returns what Drive should now hold, or null to leave it. */
    suspend fun merge(remote: String?, now: Instant): String?
}
