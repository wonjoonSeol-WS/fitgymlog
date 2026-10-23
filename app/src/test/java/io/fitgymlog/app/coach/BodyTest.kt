package io.fitgymlog.app.coach

import io.fitgymlog.app.health.dailyReadings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyTest {
    private val today = LocalDate.parse("2026-10-23")

    private fun health(date: String, weight: Double? = null, fat: Double? = null) =
        BodyMeasurement(date, weightKg = weight, bodyFatPercent = fat, source = MeasurementSource.HEALTH_CONNECT)

    @Test
    fun `a saved measurement wins its day, and Health Connect fills only what it left out`() {
        val saved = listOf(
            BodyMeasurement("2026-10-20", bodyFatPercent = 18.0, skeletalMuscleKg = 35.0, source = MeasurementSource.PHOTO),
            BodyMeasurement("2026-10-21", weightKg = 79.2),
        )
        val fromHealth = listOf(health("2026-10-20", weight = 80.0, fat = 21.0), health("2026-10-21", weight = 79.5), health("2026-10-22", weight = 79.4))

        val history = bodyHistory(saved, fromHealth)

        assertEquals(listOf("2026-10-20", "2026-10-21", "2026-10-22"), history.map { it.date })
        assertEquals(BodyMeasurement("2026-10-20", 80.0, 18.0, 35.0, source = MeasurementSource.PHOTO), history[0])
        assertEquals(79.2, history[1].weightKg!!, 0.0)
        assertEquals(MeasurementSource.HEALTH_CONNECT, history[2].source)
    }

    @Test
    fun `a day the lifter removed stays removed though Health Connect has a reading for it`() {
        val history = bodyHistory(emptyList(), listOf(health("2026-10-20", weight = 80.0), health("2026-10-21", weight = 79.5)), removed = setOf("2026-10-20"))

        assertEquals(listOf("2026-10-21"), history.map { it.date })
    }

    @Test
    fun `the newest reading is found by its date, whatever order the history is in`() {
        val shuffled = listOf(BodyMeasurement("2026-10-20", weightKg = 79.0, bodyFatPercent = 20.0), BodyMeasurement("2026-09-01", weightKg = 82.0, bodyFatPercent = 25.0), BodyMeasurement("2026-10-01", weightKg = 80.5))

        assertEquals(79.0, currentBodyweight(shuffled, setting = null, today = today)!!, 0.0)
        assertEquals(63.2, recentLeanMassKg(shuffled, today)!!, 1e-9)
    }

    @Test
    fun `lean mass is weight less fat, and needs both`() {
        assertEquals(57.442, leanMassKg(BodyMeasurement("2026-10-23", weightKg = 77.0, bodyFatPercent = 25.4))!!, 1e-9)
        assertEquals(null, leanMassKg(BodyMeasurement("2026-10-23", weightKg = 77.0)))
    }

    @Test
    fun `lean mass comes from the newest reading of the last ninety days with both weight and body fat`() {
        val history = listOf(
            BodyMeasurement("2026-07-01", weightKg = 85.0, bodyFatPercent = 25.0),
            BodyMeasurement("2026-08-15", weightKg = 82.0, bodyFatPercent = 25.0),
            BodyMeasurement("2026-09-01", weightKg = 80.0, bodyFatPercent = 20.0),
            BodyMeasurement("2026-10-20", weightKg = 79.0),
            BodyMeasurement("2026-10-21", bodyFatPercent = 18.0, visceralFatLevel = 7),
            BodyMeasurement("2026-10-30", weightKg = 78.0, bodyFatPercent = 10.0),
        )

        assertEquals(64.0, recentLeanMassKg(history, today)!!, 1e-9)
        assertEquals(null, recentLeanMassKg(history.take(1), today))
    }

    @Test
    fun `the newest weight of the last sixty days beats the one typed in settings, and older or future ones do not`() {
        val history = listOf(
            BodyMeasurement("2026-08-23", weightKg = 82.0),
            BodyMeasurement("2026-09-01", weightKg = 79.0),
            BodyMeasurement("2026-10-01", weightKg = 78.0),
            BodyMeasurement("2026-10-10", bodyFatPercent = 18.0),
            BodyMeasurement("2026-10-30", weightKg = 70.0),
        )

        assertEquals(78.0, currentBodyweight(history, setting = 80.0, today = today)!!, 0.0)
        assertEquals(80.0, currentBodyweight(history.take(1), setting = 80.0, today = today)!!, 0.0)
        assertEquals(82.0, currentBodyweight(listOf(BodyMeasurement("2026-08-24", weightKg = 82.0)), setting = 80.0, today = today)!!, 0.0)
    }

    @Test
    fun `the coach sees the newest entry of each two-week span of the past four months, even from daily weigh-ins`() {
        val daily = (0..150L).map { BodyMeasurement(today.minusDays(it).toString(), weightKg = 80.0 - it / 10.0) }.sortedBy { it.date } +
            BodyMeasurement(today.plusDays(3).toString(), weightKg = 60.0)

        val sent = recentBody(daily, today)

        assertEquals(9, sent.size)
        assertEquals(today.toString(), sent.last().date)
        assertEquals(listOf(14L), sent.zipWithNext { a, b -> java.time.LocalDate.parse(b.date).toEpochDay() - java.time.LocalDate.parse(a.date).toEpochDay() }.distinct())
        assertTrue(sent.first().date >= today.minusDays(120).toString())
    }

    @Test
    fun `several readings in a day keep the last believable one, counted in the phone's own day`() {
        val seoul = ZoneId.of("Asia/Seoul")
        val weights = listOf(
            Instant.parse("2026-10-22T14:30:00Z") to 80.2,
            Instant.parse("2026-10-22T15:30:00Z") to 79.9,
            Instant.parse("2026-10-22T22:00:00Z") to 79.6,
            Instant.parse("2026-10-22T23:00:00Z") to 0.0,
        )

        val days = dailyReadings(weights, listOf(Instant.parse("2026-10-22T14:00:00Z") to 19.5), seoul)

        assertEquals(listOf("2026-10-22" to 80.2, "2026-10-23" to 79.6), days.map { it.date to it.weightKg })
        assertEquals(listOf(19.5, null), days.map { it.bodyFatPercent })
    }
}
