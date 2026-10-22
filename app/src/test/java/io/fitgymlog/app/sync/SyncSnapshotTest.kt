package io.fitgymlog.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncSnapshotTest {
    @Test
    fun `a file from a newer app is reported as newer even when its layout changed`() {
        val newer = """{"format":"fitgymlog-sync","version":2,"workouts":{"renamed":true}}"""

        assertThrows(NewerSyncFormatException::class.java) { SyncSnapshot.decode(newer) }
    }

    @Test
    fun `a file that is not a sync file is reported as corrupt`() {
        assertThrows(CorruptSyncFileException::class.java) { SyncSnapshot.decode("{\"format\":\"fitgymlog-backup\",\"version\":1}") }
        assertThrows(CorruptSyncFileException::class.java) { SyncSnapshot.decode("{\"format\":\"fitgymlog-sync\",\"workouts\":") }
        assertThrows(CorruptSyncFileException::class.java) { SyncSnapshot.decode("<html>quota exceeded</html>") }
    }

    @Test
    fun `a current file decodes`() {
        val snapshot = SyncSnapshot(links = listOf(SyncLink("squat_barbell", "https://youtu.be/x", 1L)))

        assertEquals(snapshot, SyncSnapshot.decode(snapshot.encode()))
    }
}
