package io.fitgymlog.app.transfer

/** RFC 4180 CSV with the quirks real exports have: a BOM, CRLF or LF, no final newline, comma or semicolon. */
object Csv {
    private const val BOM = '\uFEFF'

    fun read(text: String): List<List<String>> {
        val body = text.removePrefix(BOM.toString())
        val delimiter = detectDelimiter(body)
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                inQuotes && c == '"' && body.getOrNull(i + 1) == '"' -> {
                    field.append('"')
                    i++
                }
                inQuotes && c == '"' -> inQuotes = false
                inQuotes -> field.append(c)
                c == '"' && startsField(body, i, delimiter) -> inQuotes = true
                c == delimiter -> {
                    row.add(field.toString())
                    field.clear()
                }
                c == '\r' || c == '\n' -> {
                    if (c == '\r' && body.getOrNull(i + 1) == '\n') i++
                    row.add(field.toString())
                    field.clear()
                    rows.add(row)
                    row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows.filterNot { it.size == 1 && it[0].isBlank() }
    }

    fun write(rows: List<List<String>>, delimiter: Char = ',', quoteAll: Boolean = false): String =
        rows.joinToString(separator = "\n", postfix = "\n") { row ->
            row.joinToString(delimiter.toString()) { quote(it, delimiter, quoteAll) }
        }

    private fun quote(value: String, delimiter: Char, quoteAll: Boolean): String {
        val needsQuotes = quoteAll || value.any { it == delimiter || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"${value.replace("\"", "\"\"")}\"" else value
    }

    private fun startsField(body: String, i: Int, delimiter: Char): Boolean =
        i == 0 || body[i - 1] == delimiter || body[i - 1] == '\n' || body[i - 1] == '\r'

    private fun detectDelimiter(text: String): Char {
        val header = text.lineSequence().firstOrNull().orEmpty()
        return if (header.count { it == ';' } > header.count { it == ',' }) ';' else ','
    }
}

/** A CSV row addressed by header name, case-insensitively. */
class CsvRecord(private val header: Map<String, Int>, private val values: List<String>, val rowNumber: Int) {
    operator fun get(column: String): String? = header[column.lowercase()]?.let { values.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
}

fun csvRecords(text: String): List<CsvRecord> {
    val rows = Csv.read(text)
    val header = rows.firstOrNull()?.mapIndexed { index, name -> name.trim().lowercase() to index }?.toMap() ?: return emptyList()
    return rows.drop(1).mapIndexed { index, values -> CsvRecord(header, values, rowNumber = index + 2) }
}
