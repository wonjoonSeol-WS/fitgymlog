package io.fitgymlog.app.transfer

import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransferFailureTest {
    @Test
    fun `reads a file of exactly the limit and refuses one byte more as too large`() {
        assertEquals(10, ByteArrayInputStream(ByteArray(10)).readAtMost(10).size)

        val error = assertThrows(TransferException::class.java) { ByteArrayInputStream(ByteArray(11)).readAtMost(10) }

        assertEquals(TransferFailure.TOO_LARGE, error.failure)
    }

    @Test
    fun `file access problems are reported as I-O failures`() {
        assertEquals(TransferFailure.IO, FileNotFoundException("content://x").transferFailure())
        assertEquals(TransferFailure.IO, SecurityException("no permission").transferFailure())
        assertEquals(TransferFailure.UNKNOWN, IllegalStateException("bug").transferFailure())
    }
}
