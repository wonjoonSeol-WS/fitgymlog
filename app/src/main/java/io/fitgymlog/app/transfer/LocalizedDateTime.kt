package io.fitgymlog.app.transfer

import java.time.LocalDateTime
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/**
 * Reads the dates Hevy writes in the app's display language, such as "19 10월 2026, 19:38" or
 * "Oct 19, 2026, 7:38 PM". The order of day, month and year varies by language, so the month is
 * found by name (or by the 월/月 suffix) and the remaining small number is the day. Two bare numbers
 * after a leading year read as month then day, as in "2026-10-19 19:38".
 */
object LocalizedDateTime {
    private val token = Regex("""\d{1,2}:\d{2}(?::\d{2})?|\d+\s?(?:월|月)|\d+|[\p{L}.]+""")
    private val monthSuffix = Regex("""(\d+)\s?(?:월|月)""")
    private val morning = setOf("am", "a.m.", "오전", "午前", "上午")
    private val afternoon = setOf("pm", "p.m.", "오후", "午後", "下午")
    private val hevyLocales = listOf(
        Locale.ENGLISH, Locale.KOREAN, Locale.JAPANESE, Locale.CHINESE, Locale.GERMAN, Locale.FRENCH,
        Locale.ITALIAN, Locale.forLanguageTag("es"), Locale.forLanguageTag("pt"), Locale.forLanguageTag("nl"),
        Locale.forLanguageTag("pl"), Locale.forLanguageTag("tr"), Locale.forLanguageTag("ru"),
    )
    private val monthStyles = listOf(TextStyle.FULL, TextStyle.SHORT, TextStyle.FULL_STANDALONE, TextStyle.SHORT_STANDALONE)

    private val monthsByName: Map<String, Int> by lazy {
        buildMap {
            hevyLocales.forEach { locale ->
                Month.entries.forEach { month ->
                    monthStyles.forEach { style -> putIfAbsent(normalize(month.getDisplayName(style, locale)), month.value) }
                }
            }
        }
    }

    fun parse(text: String): LocalDateTime? {
        var year: Int? = null
        var yearFirst = false
        val months = mutableSetOf<Int>()
        val plainNumbers = mutableListOf<Int>()
        var time: List<Int>? = null
        var meridiem: Boolean? = null

        for (match in token.findAll(text.lowercase())) {
            val value = match.value
            val suffixedMonth = monthSuffix.matchEntire(value)
            when {
                suffixedMonth != null -> months += suffixedMonth.groupValues[1].toIntOrNull() ?: return null
                ':' in value -> if (time == null) time = value.split(':').map(String::toInt)
                value.all(Char::isDigit) && value.length == 4 -> {
                    year = value.toInt()
                    yearFirst = plainNumbers.isEmpty() && months.isEmpty()
                }
                value.all(Char::isDigit) -> plainNumbers += value.toIntOrNull() ?: return null
                value in morning -> meridiem = false
                value in afternoon -> meridiem = true
                else -> monthNamed(value)?.let(months::add)
            }
        }

        val month = when {
            months.isNotEmpty() -> months.singleOrNull() ?: return null
            yearFirst && plainNumbers.size == 2 -> plainNumbers.removeAt(0)
            else -> return null
        }
        val day = plainNumbers.singleOrNull() ?: return null
        val clock = time ?: return null
        val (hour, minute) = clock
        val hour24 = when (meridiem) {
            null -> hour
            true -> if (hour == 12) 12 else hour + 12
            false -> if (hour == 12) 0 else hour
        }
        return runCatching { LocalDateTime.of(year ?: return null, month, day, hour24, minute, clock.getOrElse(2) { 0 }) }.getOrNull()
    }

    /** Words under three letters, such as "de" or "di", are too often something else to count as a month without 월/月. */
    private fun monthNamed(word: String): Int? {
        val key = normalize(word)
        val counts = key.length >= 3 || key.endsWith('월') || key.endsWith('月')
        return if (counts) monthsByName[key] else null
    }

    private fun normalize(name: String): String = name.lowercase().trim().trimEnd('.')
}
