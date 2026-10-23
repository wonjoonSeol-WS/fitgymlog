package io.fitgymlog.app.coach

import java.time.LocalDate

/** One row per day: what the lifter saved wins, Health Connect fills in what they left out, and a day they removed stays removed. */
internal fun bodyHistory(saved: List<BodyMeasurement>, fromHealth: List<BodyMeasurement>, removed: Set<String> = emptySet()): List<BodyMeasurement> {
    val healthByDate = fromHealth.filterNot { it.date in removed }.associateBy { it.date }
    val own = saved.associateBy { it.date }.mapValues { (date, entry) ->
        val health = healthByDate[date]
        entry.copy(weightKg = entry.weightKg ?: health?.weightKg, bodyFatPercent = entry.bodyFatPercent ?: health?.bodyFatPercent)
    }
    return (healthByDate + own).values.sortedBy { it.date }
}

/** The newest weigh-in of the last [RECENT_WEIGHT_DAYS] days, which beats the bodyweight typed in settings since that carries no date. */
internal fun currentWeighIn(history: List<BodyMeasurement>, today: LocalDate): BodyMeasurement? {
    val since = today.minusDays(RECENT_WEIGHT_DAYS).toString()
    return history.filter { it.weightKg != null && it.date >= since && it.date <= today.toString() }.maxByOrNull { it.date }
}

internal fun currentBodyweight(history: List<BodyMeasurement>, setting: Double?, today: LocalDate): Double? =
    currentWeighIn(history, today)?.weightKg ?: setting

internal fun fatMassKg(entry: BodyMeasurement): Double? {
    val weight = entry.weightKg ?: return null
    val fat = entry.bodyFatPercent ?: return null
    return weight * fat / 100
}

/** Weight less fat, which every body-fat scale and InBody agree on, unlike their muscle figures. */
internal fun leanMassKg(entry: BodyMeasurement): Double? {
    val weight = entry.weightKg ?: return null
    return fatMassKg(entry)?.let { weight - it }
}

/** From the newest reading of the last [RECENT_LEAN_MASS_DAYS] days with both weight and body fat; lean mass moves slowly enough to last that long. */
internal fun recentLeanMassKg(history: List<BodyMeasurement>, today: LocalDate): Double? {
    val since = today.minusDays(RECENT_LEAN_MASS_DAYS).toString()
    return history.filter { it.date >= since && it.date <= today.toString() && leanMassKg(it) != null }.maxByOrNull { it.date }?.let(::leanMassKg)
}

/** The newest entry of each two-week span of the past months, so daily weigh-ins still show the coach a trend. */
internal fun recentBody(history: List<BodyMeasurement>, today: LocalDate): List<BodyMeasurement> {
    val since = today.minusDays(BODY_CONTEXT_DAYS)
    return history.filter { it.date >= since.toString() && it.date <= today.toString() }
        .groupBy { (today.toEpochDay() - LocalDate.parse(it.date).toEpochDay()) / BODY_CONTEXT_SPAN_DAYS }
        .values.map { span -> span.maxBy { it.date } }
        .sortedBy { it.date }
        .takeLast(MAX_BODY_CONTEXT)
}

internal const val RECENT_WEIGHT_DAYS = 60L
private const val RECENT_LEAN_MASS_DAYS = 90L
private const val BODY_CONTEXT_DAYS = 120L
private const val BODY_CONTEXT_SPAN_DAYS = 14L
private const val MAX_BODY_CONTEXT = 9

internal val BODYWEIGHT_KG = 25.0..300.0
internal val BODY_FAT_PERCENT = 2.0..75.0
internal val SKELETAL_MUSCLE_KG = 10.0..100.0
internal val VISCERAL_FAT_LEVEL = 1..59
