package io.fitgymlog.app.coach

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoachSyncMergeTest {
    private val now = Instant.parse("2026-10-22T12:00:00Z")

    private fun note(key: String, text: String, at: Long) = Memory(key, text, "2026-10-22", updatedAt = at)

    @Test
    fun `the newer note under a key wins, from either side`() {
        val local = CoachData(memories = listOf(note("health/left-knee", "Left knee sore", 1), note("food/lactose", "Lactose intolerant", 5)))
        val remote = CoachSyncFile(memories = listOf(note("health/left-knee", "Knee fine now", 3), note("food/lactose", "old", 2)))

        val merged = CoachSyncMerge.merge(local, remote, now)

        assertEquals(listOf("Lactose intolerant", "Knee fine now"), merged.memories.map { it.text })
    }

    @Test
    fun `a forget beats edits made before it but not one made after`() {
        val day = Duration.ofDays(1).toMillis()
        val start = now.toEpochMilli() - 10 * day
        val local = CoachData(memories = listOf(note("health/left-knee", "Left knee sore", start + day), note("goals/squat", "Squat 100", start + 9 * day)))
        val remote = CoachSyncFile(forgotten = mapOf(forgetKey("health/left-knee") to start + 4 * day, forgetKey("goals/squat") to start + 6 * day))

        val merged = CoachSyncMerge.merge(local, remote, now)

        assertEquals(listOf("goals/squat"), merged.memories.map { it.key })
        assertEquals(mapOf(forgetKey("health/left-knee") to start + 4 * day, forgetKey("goals/squat") to start + 6 * day), merged.forgotten)
    }

    @Test
    fun `two phones merging each other's copy end up with the same notes, even on a tie`() {
        val a = CoachData(memories = listOf(note("health/back", "Lower back tight", 7), note("food/pork", "No pork", 2)))
        val b = CoachData(memories = listOf(note("health/back", "Back is fine", 7), note("schedule/days", "Mon Wed Fri", 3)))

        val onA = CoachSyncMerge.merge(a, CoachSyncMerge.fileOf(b), now)
        val onB = CoachSyncMerge.merge(b, CoachSyncMerge.fileOf(a), now)

        assertEquals(onA.memories, onB.memories)
        assertEquals(3, onA.memories.size)
    }

    @Test
    fun `reviews keep the newest per workout, in time order, and the local chat is left as it is`() {
        val chat = listOf(ChatTurn(fromUser = true, text = "hi"))
        val local = CoachData(chat = chat, reviews = listOf(Review("w-1", "old notes", updatedAt = 1)))
        val remote = CoachSyncFile(reviews = listOf(Review("w-1", "new notes", updatedAt = 2), Review("w-2", "other", updatedAt = 1)))

        val merged = CoachSyncMerge.merge(local, remote, now)

        assertEquals(chat, merged.chat)
        assertEquals(listOf("w-2" to "other", "w-1" to "new notes"), merged.reviews.map { it.workoutUuid to it.notes })
    }

    @Test
    fun `the later forget of a key wins, whichever side holds it`() {
        val day = Duration.ofDays(1).toMillis()
        val earlier = now.toEpochMilli() - 5 * day
        val later = now.toEpochMilli() - 3 * day

        val fromLocal = CoachSyncMerge.merge(CoachData(forgotten = mapOf(forgetKey("food/pork") to later)), CoachSyncFile(forgotten = mapOf(forgetKey("food/pork") to earlier)), now)
        val fromRemote = CoachSyncMerge.merge(CoachData(forgotten = mapOf(forgetKey("food/pork") to earlier)), CoachSyncFile(forgotten = mapOf(forgetKey("food/pork") to later)), now)

        assertEquals(later, fromLocal.forgotten.getValue(forgetKey("food/pork")))
        assertEquals(later, fromRemote.forgotten.getValue(forgetKey("food/pork")))
    }

    @Test
    fun `versions saved in the same millisecond still agree on both phones, down to the end date and food`() {
        val a = CoachData(
            memories = listOf(note("health/knee", "Knee sore", 7).copy(until = "2026-10-30")),
            reviews = listOf(Review("w-1", "Good", updatedAt = 4)),
        )
        val b = CoachData(
            memories = listOf(note("health/knee", "Knee sore", 7).copy(until = "2026-11-05")),
            reviews = listOf(Review("w-1", "Good", Food(120, 30, "eat", emptyList()), updatedAt = 4)),
        )

        val onA = CoachSyncMerge.merge(a, CoachSyncMerge.fileOf(b), now)
        val onB = CoachSyncMerge.merge(b, CoachSyncMerge.fileOf(a), now)

        assertEquals(CoachSyncMerge.fileOf(onA), CoachSyncMerge.fileOf(onB))
    }

    @Test
    fun `body measurements keep the newest per day, and a removal beats only what came before it`() {
        val base = now.toEpochMilli() - 1_000
        val local = CoachData(
            measurements = listOf(BodyMeasurement("2026-10-20", weightKg = 80.0, updatedAt = base + 5), BodyMeasurement("2026-10-21", weightKg = 79.0, updatedAt = base + 1)),
            removedMeasurements = mapOf("2026-10-22" to base + 2),
        )
        val remote = CoachSyncFile(
            measurements = listOf(BodyMeasurement("2026-10-20", weightKg = 81.0, updatedAt = base + 3), BodyMeasurement("2026-10-22", weightKg = 78.5, updatedAt = base + 4)),
            removedMeasurements = mapOf("2026-10-21" to base + 2),
        )

        val merged = CoachSyncMerge.merge(local, remote, now)

        assertEquals(listOf("2026-10-20" to 80.0, "2026-10-22" to 78.5), merged.measurements.map { it.date to it.weightKg })
        assertEquals(mapOf("2026-10-21" to base + 2, "2026-10-22" to base + 2), merged.removedMeasurements)
        assertEquals(merged.measurements, CoachSyncMerge.fileOf(merged).measurements)
    }

    @Test
    fun `a day's newest reading keeps what an older one on the other phone had and it leaves blank, on both phones, but not from before a removal`() {
        val base = now.toEpochMilli() - 1_000
        val typed = BodyMeasurement("2026-10-21", weightKg = 79.0, updatedAt = base + 1)
        val scanned = BodyMeasurement("2026-10-21", bodyFatPercent = 18.0, skeletalMuscleKg = 35.0, source = MeasurementSource.PHOTO, updatedAt = base + 3)
        val a = CoachData(measurements = listOf(typed, BodyMeasurement("2026-10-22", weightKg = 78.0, updatedAt = base + 1)))
        val b = CoachData(measurements = listOf(scanned, BodyMeasurement("2026-10-22", bodyFatPercent = 17.0, updatedAt = base + 4)), removedMeasurements = mapOf("2026-10-22" to base + 2))

        val onA = CoachSyncMerge.merge(a, CoachSyncMerge.fileOf(b), now)
        val onB = CoachSyncMerge.merge(b, CoachSyncMerge.fileOf(a), now)

        assertEquals(
            listOf(scanned.copy(weightKg = 79.0), BodyMeasurement("2026-10-22", bodyFatPercent = 17.0, updatedAt = base + 4)),
            onA.measurements,
        )
        assertEquals(onA.measurements, onB.measurements)
    }

    @Test
    fun `a file from a newer app or one that does not read is left alone`() {
        val current = CoachSyncMerge.encode(CoachSyncFile(memories = listOf(note("food/pork", "No pork", 1))))

        assertEquals(listOf("food/pork"), CoachSyncMerge.decode(current)?.memories?.map { it.key })
        assertNull(CoachSyncMerge.decode(current.replace("\"version\":1", "\"version\":2")))
        assertNull(CoachSyncMerge.decode(current.replace("fitgymlog-coach", "something-else")))
        assertNull(CoachSyncMerge.decode("{not json"))
    }

    @Test
    fun `forgets older than a year are dropped`() {
        val old = now.minus(Duration.ofDays(400)).toEpochMilli()
        val recent = now.minus(Duration.ofDays(10)).toEpochMilli()

        val merged = CoachSyncMerge.merge(CoachData(), CoachSyncFile(forgotten = mapOf(forgetKey("health/a") to old, forgetKey("health/b") to recent)), now)

        assertEquals(setOf(forgetKey("health/b")), merged.forgotten.keys)
    }

    @Test
    fun `a synced measurement whose date does not read is dropped`() {
        val text = CoachSyncMerge.encode(CoachSyncFile(measurements = listOf(BodyMeasurement("2026-10-21", weightKg = 79.0), BodyMeasurement("yesterday", weightKg = 80.0))))

        assertEquals(listOf("2026-10-21"), CoachSyncMerge.decode(text)?.measurements?.map { it.date })
    }
}
