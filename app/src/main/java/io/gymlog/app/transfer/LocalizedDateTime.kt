package io.gymlog.app.transfer

import java.text.DateFormatSymbols
import java.time.LocalDateTime
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
    private val priorityLocales = listOf(
        Locale.ENGLISH, Locale.KOREAN, Locale.JAPANESE, Locale.CHINESE, Locale.GERMAN, Locale.FRENCH,
        Locale.ITALIAN, Locale.forLanguageTag("es"), Locale.forLanguageTag("pt"), Locale.forLanguageTag("nl"),
        Locale.forLanguageTag("pl"), Locale.forLanguageTag("tr"), Locale.forLanguageTag("ru"),
    )

    private val monthsByName: Map<String, Int> by lazy {
        val names = mutableMapOf<String, Int>()
        (priorityLocales + Locale.getAvailableLocales()).forEach { locale ->
            val symbols = DateFormatSymbols.getInstance(locale)
            listOf(symbols.months, symbols.shortMonths).forEach { monthNames ->
                monthNames.take(12).forEachIndexed { index, name ->
                    val key = normalize(name)
                    if (key.isNotEmpty()) names.putIfAbsent(key, index + 1)
                }
            }
        }
        names
    }

    fun parse(text: String): LocalDateTime? {
        var year: Int? = null
        var yearFirst = false
        var month: Int? = null
        val plainNumbers = mutableListOf<Int>()
        var hour: Int? = null
        var minute: Int? = null
        var second = 0
        var meridiem: Boolean? = null

        for (match in token.findAll(text.lowercase())) {
            val value = match.value
            val suffixedMonth = monthSuffix.matchEntire(value)
            when {
                suffixedMonth != null -> month = suffixedMonth.groupValues[1].toInt()
                ':' in value -> {
                    val parts = value.split(':').map(String::toInt)
                    hour = parts[0]
                    minute = parts[1]
                    second = parts.getOrElse(2) { 0 }
                }
                value.all(Char::isDigit) && value.length == 4 -> {
                    year = value.toInt()
                    yearFirst = plainNumbers.isEmpty() && month == null
                }
                value.all(Char::isDigit) -> plainNumbers += value.toInt()
                value in morning -> meridiem = false
                value in afternoon -> meridiem = true
                else -> monthsByName[normalize(value)]?.let { if (month == null) month = it }
            }
        }

        if (month == null && yearFirst && plainNumbers.size == 2) {
            month = plainNumbers.removeAt(0)
        }
        val day = plainNumbers.singleOrNull() ?: return null
        val h = hour ?: return null
        val hour24 = when (meridiem) {
            null -> h
            true -> if (h == 12) 12 else h + 12
            false -> if (h == 12) 0 else h
        }
        return runCatching { LocalDateTime.of(year ?: return null, month ?: return null, day, hour24, minute ?: 0, second) }.getOrNull()
    }

    private fun normalize(name: String): String = name.lowercase().trim().trimEnd('.')
}
