package io.fitgymlog.app.coach

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class CoachDataTest {
    private val now = Instant.parse("2026-10-22T12:00:00Z")
    private val today = LocalDate.parse("2026-10-22")
    private val millis = now.toEpochMilli()

    @Test
    fun `remembering replaces the note under its key and takes back an earlier forget`() {
        val data = CoachData(
            memories = listOf(Memory("health/left-knee", "Left knee sore", "2026-10-01", updatedAt = 1), Memory("food/pork", "No pork", "2026-10-01", updatedAt = 1)),
            forgotten = mapOf(forgetKey("health/left-knee") to 5L, forgetKey("goals/old-goal") to 7L),
        )

        val saved = data.remembering("health/left-knee", " Knee fine now ", until = "2026-11-01", nowMillis = millis, today = today)

        assertEquals(listOf("food/pork" to "No pork", "health/left-knee" to "Knee fine now"), saved.memories.map { it.key to it.text })
        assertEquals(Memory("health/left-knee", "Knee fine now", "2026-10-22", millis, "2026-11-01"), saved.memories.last())
        assertEquals(mapOf(forgetKey("goals/old-goal") to 7L), saved.forgotten)
    }

    @Test
    fun `forgetting drops the note and records when`() {
        val data = CoachData(memories = listOf(Memory("food/pork", "No pork", "2026-10-01", updatedAt = 1)))

        val forgotten = data.forgetting("food/pork", millis)

        assertEquals(emptyList<Memory>(), forgotten.memories)
        assertEquals(mapOf(forgetKey("food/pork") to millis), forgotten.forgotten)
    }

    @Test
    fun `a new review of a workout replaces the old one`() {
        val data = CoachData(reviews = listOf(Review("w-1", "old", updatedAt = 1), Review("w-2", "other", updatedAt = 2)))

        val kept = data.withReview(Review("w-1", "new"), millis)

        assertEquals(listOf("w-2" to "other", "w-1" to "new"), kept.reviews.map { it.workoutUuid to it.notes })
        assertEquals(millis, kept.reviews.last().updatedAt)
    }

    @Test
    fun `trimming keeps the newest 200 turns and 60 reviews, and forgets older than a year`() {
        val data = CoachData(
            chat = (1..250L).map { ChatTurn(fromUser = true, text = "m$it", at = it) },
            reviews = (1..70).map { Review("w-$it", "n$it") },
            forgotten = mapOf("health/old" to now.minus(Duration.ofDays(400)).toEpochMilli(), "health/new" to now.minus(Duration.ofDays(10)).toEpochMilli()),
        )

        val trimmed = data.trimmed(now)

        assertEquals((51..250L).toList(), trimmed.chat.map { it.at })
        assertEquals((11..70).map { "w-$it" }, trimmed.reviews.map { it.workoutUuid })
        assertEquals(setOf("health/new"), trimmed.forgotten.keys)
    }

    @Test
    fun `turns already folded into the summary are not sent again`() {
        val data = CoachData(chat = (1..6L).map { ChatTurn(fromUser = it % 2 == 1L, text = "m$it", at = it) }, summarizedUntil = 4)

        assertEquals(listOf(5L, 6L), data.unsummarized().map { it.at })
    }

    @Test
    fun `a reply is kept only while its question is still the last turn`() {
        val asked = ChatTurn(fromUser = true, text = "q", at = 1)
        val reply = ChatTurn(fromUser = false, text = "a", at = 2)
        val newer = ChatTurn(fromUser = true, text = "q2", at = 3)

        assertEquals(listOf(asked, reply), CoachData(chat = listOf(asked)).answered(asked, reply).chat)
        assertEquals(emptyList<ChatTurn>(), CoachData().answered(asked, reply).chat)
        assertEquals(listOf(asked, newer), CoachData(chat = listOf(asked, newer)).answered(asked, reply).chat)
    }

    @Test
    fun `a summary is kept only if nothing moved the summary since it started`() {
        val data = CoachData(chatSummary = "old", summarizedUntil = 10)

        assertEquals("new" to 20L, data.summarized(from = 10, summary = "new", until = 20).let { it.chatSummary to it.summarizedUntil })
        assertEquals("old" to 10L, data.summarized(from = 5, summary = "stale", until = 20).let { it.chatSummary to it.summarizedUntil })
    }

    @Test
    fun `a measurement replaces that day's, and removing one remembers when, until it is measured again`() {
        val data = CoachData(measurements = listOf(BodyMeasurement("2026-10-20", weightKg = 80.0, updatedAt = 1), BodyMeasurement("2026-10-21", weightKg = 79.0, updatedAt = 1)))

        val replaced = data.measured(BodyMeasurement("2026-10-20", weightKg = 79.5), millis)
        val removed = replaced.unmeasured("2026-10-21", millis)
        val again = removed.measured(BodyMeasurement("2026-10-21", weightKg = 78.8), millis + 1)

        assertEquals(listOf("2026-10-20" to 79.5, "2026-10-21" to 79.0), replaced.measurements.map { it.date to it.weightKg })
        assertEquals(millis, replaced.measurements.first().updatedAt)
        assertEquals(mapOf("2026-10-21" to millis), removed.removedMeasurements)
        assertEquals(emptyMap<String, Long>(), again.removedMeasurements)
        assertEquals(listOf("2026-10-20", "2026-10-21"), again.measurements.map { it.date })
    }

    @Test
    fun `a note kept for one day ends the same day`() {
        assertEquals("2026-10-22", untilFor(1, today))
        assertEquals("2026-11-04", untilFor(14, today))
    }

    @Test
    fun `a week's look back replaces the earlier one for that week, marks only the changes that applied, and twelve weeks are kept`() {
        val first = WeekReview("2026-10-19", "first", listOf(RoutineChange(4, "squat_barbell", sets = 3), RoutineChange(4, "bench_press_barbell", sets = 2)))
        val again = first.copy(text = "again")
        val weeks = (1..13).map { WeekReview(LocalDate.parse("2026-07-20").plusWeeks(it.toLong()).toString(), "week $it") }

        val data = CoachData().withWeek(first, millis).withWeek(again, millis + 1).weekApplied("2026-10-19", setOf(1), millis + 2)
        val trimmed = weeks.fold(CoachData()) { kept, week -> kept.withWeek(week, millis) }.trimmed(now)

        assertEquals(listOf("again"), data.weeks.map { it.text })
        assertEquals(listOf(false, true), data.weeks.single().changes.map { it.applied })
        assertEquals(millis + 2, data.weeks.single().updatedAt)
        assertEquals(12, trimmed.weeks.size)
        assertEquals("week 2", trimmed.weeks.first().text)
    }

    @Test
    fun `a new reading for a saved day keeps that day's other numbers, and an edit moved to that day does too`() {
        val data = CoachData(measurements = listOf(BodyMeasurement("2026-10-21", weightKg = 79.0, bodyFatPercent = 20.0, skeletalMuscleKg = 35.0, visceralFatLevel = 8, updatedAt = 1)))

        val added = data.measuredAlongside(BodyMeasurement("2026-10-21", bodyFatPercent = 18.0), millis)
        val moved = CoachData(measurements = listOf(BodyMeasurement("2026-10-20", bodyFatPercent = 19.0), BodyMeasurement("2026-10-21", weightKg = 79.0)))
            .remeasured(from = "2026-10-20", entry = BodyMeasurement("2026-10-21", bodyFatPercent = 19.0), nowMillis = millis)

        assertEquals(BodyMeasurement("2026-10-21", 79.0, 18.0, 35.0, visceralFatLevel = 8, updatedAt = millis), added.measurements.single())
        assertEquals(listOf(BodyMeasurement("2026-10-21", 79.0, 19.0, updatedAt = millis)), moved.measurements)
        assertEquals(setOf("2026-10-20"), moved.removedMeasurements.keys)
    }

    @Test
    fun `editing a day in place can clear a number`() {
        val data = CoachData(measurements = listOf(BodyMeasurement("2026-10-21", weightKg = 79.0, bodyFatPercent = 18.0)))

        val edited = data.remeasured(from = "2026-10-21", entry = BodyMeasurement("2026-10-21", weightKg = 79.0), nowMillis = millis)

        assertEquals(null, edited.measurements.single().bodyFatPercent)
    }
}
