package io.fitgymlog.app.sync

import java.io.IOException
import java.net.UnknownHostException
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncFailureTest {
    @Test
    fun `each kind of failure gets its own reason`() {
        assertEquals(SyncFailure.SIGN_IN_REJECTED, SyncFailure.of(DriveAuthException()))
        assertEquals(SyncFailure.NEWER_APP_FORMAT, SyncFailure.of(NewerSyncFormatException(2)))
        assertEquals(SyncFailure.CORRUPT_SYNC_FILE, SyncFailure.of(CorruptSyncFileException()))
        assertEquals(SyncFailure.NETWORK, SyncFailure.of(UnknownHostException("www.googleapis.com")))
        assertEquals(SyncFailure.NETWORK, SyncFailure.of(IOException("timeout")))
        assertEquals(SyncFailure.OTHER, SyncFailure.of(SerializationException("bad listing")))
    }

    @Test
    fun `drive errors are network trouble only when retrying later can help`() {
        assertEquals(SyncFailure.NETWORK, SyncFailure.of(DriveHttpException(503, "backend error")))
        assertEquals(SyncFailure.NETWORK, SyncFailure.of(DriveHttpException(429, "rate limit")))
        assertEquals(SyncFailure.OTHER, SyncFailure.of(DriveHttpException(403, "{\"error\":{\"message\":\"storageQuotaExceeded\"}}")))
        assertEquals(SyncFailure.OTHER, SyncFailure.of(DriveHttpException(400, "invalid query")))
    }
}
