package io.fitgymlog.app.sync

import io.fitgymlog.app.transfer.BackupRoutine
import io.fitgymlog.app.transfer.BackupWorkout
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {
    private val now = Instant.parse("2026-10-22T10:00:00Z")

    /** Change times a few minutes before [now], so deletion records are well inside their lifetime. */
    private fun at(minutesAgo: Int): Long = now.minusSeconds(minutesAgo * 60L).toEpochMilli()

    private fun workout(uuid: String, updatedAt: Long, title: String = "Day A", startedAt: String = "2026-10-19T10:38:00Z") =
        SyncWorkout(uuid, updatedAt, BackupWorkout(title, startedAt, "2026-10-19T13:05:00Z", "", "APP", emptyList()))

    private fun routine(uuid: String, updatedAt: Long, name: String) = SyncRoutine(uuid, updatedAt, BackupRoutine(name, emptyList()))

    private fun snapshot(
        workouts: List<SyncWorkout> = emptyList(),
        routines: List<SyncRoutine> = emptyList(),
        links: List<SyncLink> = emptyList(),
        tombstones: List<SyncTombstone> = emptyList(),
    ) = SyncSnapshot(workouts = workouts, routines = routines, links = links, tombstones = tombstones).sorted()

    @Test
    fun `the newer edit wins whichever side it is on`() {
        val local = snapshot(routines = listOf(routine("r1", at(50), "Old name"), routine("r2", at(30), "Phone edit")))
        val remote = snapshot(routines = listOf(routine("r1", at(40), "Tablet edit"), routine("r2", at(35), "Older tablet edit")))

        val merged = SyncMerge.merge(local, remote, now)

        assertEquals(listOf("Tablet edit", "Phone edit"), merged.routines.map { it.routine.name })
        assertEquals(listOf("r1"), SyncMerge.changes(local, merged).upsertRoutines.map { it.uuid })
    }

    @Test
    fun `items only one side has are kept`() {
        val merged = SyncMerge.merge(snapshot(workouts = listOf(workout("a", at(60)))), snapshot(workouts = listOf(workout("b", at(60), startedAt = "2026-10-20T10:00:00Z"))), now)

        assertEquals(listOf("a", "b"), merged.workouts.map { it.uuid })
    }

    @Test
    fun `a deletion on another phone removes the older local copy`() {
        val local = snapshot(workouts = listOf(workout("a", at(50))))
        val remote = snapshot(tombstones = listOf(SyncTombstone(TombstoneKind.WORKOUT, "a", at(40))))

        val merged = SyncMerge.merge(local, remote, now)

        assertTrue(merged.workouts.isEmpty())
        assertEquals(listOf("a"), SyncMerge.changes(local, merged).deleteWorkouts)
    }

    @Test
    fun `an edit made after the deletion survives it`() {
        val local = snapshot(routines = listOf(routine("r1", at(30), "Edited later")))
        val remote = snapshot(tombstones = listOf(SyncTombstone(TombstoneKind.ROUTINE, "r1", at(40))))

        assertEquals(listOf("r1"), SyncMerge.merge(local, remote, now).routines.map { it.uuid })
    }

    @Test
    fun `history imported on two phones collapses to one workout`() {
        val local = snapshot(workouts = listOf(workout("b-uuid", at(50))))
        val remote = snapshot(workouts = listOf(workout("a-uuid", at(50))))

        val merged = SyncMerge.merge(local, remote, now)

        assertEquals(listOf("a-uuid"), merged.workouts.map { it.uuid })
        assertTrue(merged.tombstones.any { it.key == "b-uuid" })
        assertEquals(listOf("b-uuid"), SyncMerge.changes(local, merged).deleteWorkouts)
    }

    @Test
    fun `a link removed elsewhere goes, a link added again afterwards stays`() {
        val squatVideo = SyncLink("squat_barbell", "https://youtu.be/x", at(50))
        val removed = SyncTombstone(TombstoneKind.LINK, TombstoneKind.linkKey("squat_barbell", "https://youtu.be/x"), at(40))

        assertTrue(SyncMerge.merge(snapshot(links = listOf(squatVideo)), snapshot(tombstones = listOf(removed)), now).links.isEmpty())
        assertEquals(1, SyncMerge.merge(snapshot(links = listOf(squatVideo.copy(addedAt = at(30)))), snapshot(tombstones = listOf(removed)), now).links.size)
    }

    @Test
    fun `deletion records older than their lifetime are dropped`() {
        val old = SyncTombstone(TombstoneKind.WORKOUT, "gone", now.minus(SyncMerge.TOMBSTONE_LIFETIME).minus(Duration.ofDays(1)).toEpochMilli())

        assertTrue(SyncMerge.merge(snapshot(tombstones = listOf(old)), snapshot(), now).tombstones.isEmpty())
    }

    @Test
    fun `phones syncing in turn end up with the same data`() {
        val phone = snapshot(workouts = listOf(workout("p", at(58))), routines = listOf(routine("r", at(56), "Phone")))
        val tablet = snapshot(workouts = listOf(workout("t", at(57), startedAt = "2026-10-21T10:00:00Z")), routines = listOf(routine("r", at(55), "Tablet")))

        val afterPhone = SyncMerge.merge(phone, snapshot(), now)
        val afterTablet = SyncMerge.merge(tablet, afterPhone, now)
        val phoneAgain = SyncMerge.merge(afterPhone, afterTablet, now)

        assertEquals(afterTablet, phoneAgain)
        assertEquals(listOf("p", "t"), phoneAgain.workouts.map { it.uuid })
        assertEquals("Tablet", phoneAgain.routines.single().routine.name)
    }

    @Test
    fun `the sync file reads back unchanged`() {
        val merged = SyncMerge.merge(snapshot(workouts = listOf(workout("a", at(60))), routines = listOf(routine("r", at(59), "A"))), snapshot(), now)

        assertEquals(merged, SyncSnapshot.decode(merged.encode()))
    }
}
