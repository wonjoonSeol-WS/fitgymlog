package io.fitgymlog.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriveAppDataTest {
    @Test
    fun `the oldest of several sync files is used wherever it is listed`() {
        val listing = """
            {"files": [
                {"id": "made-second", "createdTime": "2026-10-22T10:00:01.500Z"},
                {"id": "made-first", "createdTime": "2026-10-22T10:00:01.200Z"},
                {"id": "made-third", "createdTime": "2026-10-22T10:05:00Z"}
            ]}
        """.trimIndent()

        assertEquals(DriveAppData.RemoteFile("made-first"), DriveAppData.oldestOf(listing))
    }

    @Test
    fun `no file yet`() {
        assertNull(DriveAppData.oldestOf("""{"files": []}"""))
    }
}
