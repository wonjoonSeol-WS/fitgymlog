package io.fitgymlog.app.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {
    @Test
    fun `reads quoted fields, escaped quotes, empty fields and a missing final newline`() {
        val rows = Csv.read("\"a\",b,,\"c, \"\"d\"\"\"\n1,2,3,4")

        assertEquals(listOf(listOf("a", "b", "", "c, \"d\""), listOf("1", "2", "3", "4")), rows)
    }

    @Test
    fun `reads CRLF line endings and strips a byte order mark`() {
        val rows = Csv.read("\uFEFFdate,weight_kg\r\n\"29 8월 2026, 00:00\",78\r\n")

        assertEquals(listOf(listOf("date", "weight_kg"), listOf("29 8월 2026, 00:00", "78")), rows)
    }

    @Test
    fun `detects semicolons from the header`() {
        val rows = Csv.read("\"Date\";\"Reps\"\n\"2026-10-19 19:38:00\";\"8\"")

        assertEquals(listOf("2026-10-19 19:38:00", "8"), rows[1])
    }

    @Test
    fun `keeps newlines inside quoted fields`() {
        assertEquals(listOf("line one\nline two", "x"), Csv.read("\"line one\nline two\",x")[0])
    }

    @Test
    fun `written rows read back unchanged`() {
        val rows = listOf(listOf("title", "notes"), listOf("Day A", "felt \"heavy\", slow"))

        assertEquals(rows, Csv.read(Csv.write(rows)))
        assertEquals(rows, Csv.read(Csv.write(rows, delimiter = ';', quoteAll = true)))
    }
}
