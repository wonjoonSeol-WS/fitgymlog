package io.fitgymlog.app.sync

import io.fitgymlog.app.transfer.BackupRoutine
import io.fitgymlog.app.transfer.BackupWorkout
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMergeTest {
    private val now = Instant.parse("2026-10-22T10:00:00Z")

    /** Change times a few minutes before [now], so deletion records are well inside their lifetime. */
    private fun at(minutesAgo: Int): Long = now.minusSeconds(minutesAgo * 60L).toEpochMilli()

    private fun workout(uuid: String, updatedAt: Long, title: String = "Day A", startedAt: String = "2026-10-19T10:38:00Z") =
        SyncWorkout(uuid, updatedAt, BackupWorkout(title, startedAt, "2026-10-19T13:05:00Z", "", "APP", emptyList()))

    private fun routine(uuid: String, updatedAt: Long, name: String) = SyncRoutine(uuid, updatedAt, BackupRoutine(name, emptyList()))

    private fun link(exerciseId: String, url: String, addedAt: Long) = SyncLink(exerciseId, url, addedAt)

    private fun linkRemoved(exerciseId: String, url: String, deletedAt: Long) =
        SyncTombstone(TombstoneKind.LINK, TombstoneKind.linkKey(exerciseId, url), deletedAt)

    private fun snapshot(
        workouts: List<SyncWorkout> = emptyList(),
        routines: List<SyncRoutine> = emptyList(),
        customExercises: List<SyncCustomExercise> = emptyList(),
        links: List<SyncLink> = emptyList(),
        tombstones: List<SyncTombstone> = emptyList(),
    ) = SyncSnapshot(workouts = workouts, routines = routines, customExercises = customExercises, links = links, tombstones = tombstones).sorted()

    /** What [SyncStore.apply] does to the database: one row per key, upserts replace the whole row. */
    private fun SyncSnapshot.after(changes: SyncChanges): SyncSnapshot {
        fun <T> replaced(rows: List<T>, upserts: List<T>, removed: Collection<String>, key: (T) -> String) =
            (rows.filterNot { key(it) in removed }.associateBy(key) + upserts.associateBy(key)).values.toList()
        fun linkKey(link: SyncLink) = TombstoneKind.linkKey(link.exerciseId, link.url)
        return copy(
            workouts = replaced(workouts, changes.upsertWorkouts, changes.deleteWorkouts) { it.uuid },
            routines = replaced(routines, changes.upsertRoutines, changes.deleteRoutines) { it.uuid },
            customExercises = replaced(customExercises, changes.upsertCustomExercises, emptyList()) { it.id },
            links = replaced(links, changes.upsertLinks, changes.removeLinks.map(::linkKey), ::linkKey),
            tombstones = replaced(tombstones.filterNot { SyncMerge.isExpired(it.deletedAt, now) }, changes.tombstones, emptyList()) { "${it.kind}|${it.key}" },
        ).sorted()
    }

    @Test
    fun `the newer edit wins whichever side it is on`() {
        val local = snapshot(routines = listOf(routine("r1", at(50), "Old name"), routine("r2", at(30), "Phone edit")))
        val remote = snapshot(routines = listOf(routine("r1", at(40), "Tablet edit"), routine("r2", at(35), "Older tablet edit")))

        val merged = SyncMerge.merge(local, remote, now)

        assertEquals(listOf("Tablet edit", "Phone edit"), merged.routines.map { it.routine.name })
        assertEquals(listOf("r1"), SyncMerge.changes(local, merged).upsertRoutines.map { it.uuid })
    }

    @Test
    fun `edits made at the same moment settle on the same copy whichever phone merges`() {
        val phone = snapshot(
            routines = listOf(routine("r", at(30), "Phone")),
            customExercises = listOf(SyncCustomExercise("custom-1", at(30), "Sled push", "WEIGHT_REPS")),
        )
        val tablet = snapshot(
            routines = listOf(routine("r", at(30), "Tablet")),
            customExercises = listOf(SyncCustomExercise("custom-1", at(30), "Sled", "DURATION")),
        )

        val onPhone = SyncMerge.merge(phone, tablet, now)
        val onTablet = SyncMerge.merge(tablet, phone, now)

        assertEquals(onPhone, onTablet)
        assertEquals(onPhone, phone.after(SyncMerge.changes(phone, onPhone)))
        assertEquals(onTablet, tablet.after(SyncMerge.changes(tablet, onTablet)))
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
    fun `history imported on two phones keeps the workout with the smaller id`() {
        val local = snapshot(
            workouts = listOf(
                workout("a-uuid", at(50), startedAt = "2026-10-19T10:38:00Z"),
                workout("c-uuid", at(50), title = "Day B", startedAt = "2026-10-19T10:38:10Z"),
            ),
        )
        val remote = snapshot(
            workouts = listOf(
                workout("b-uuid", at(45), startedAt = "2026-10-19T10:38:42Z"),
                workout("d-uuid", at(45), title = "Day C", startedAt = "2026-10-19T10:38:20Z"),
            ),
        )

        val merged = SyncMerge.merge(local, remote, now)

        assertEquals(listOf("a-uuid", "c-uuid", "d-uuid"), merged.workouts.map { it.uuid })
        assertEquals(listOf(SyncTombstone(TombstoneKind.WORKOUT, "b-uuid", now.toEpochMilli())), merged.tombstones)
        assertEquals(listOf("b-uuid"), SyncMerge.changes(remote, merged).deleteWorkouts)
        assertEquals(merged, SyncMerge.merge(remote, local, now))
    }

    @Test
    fun `a duplicate that already has an older deletion record ends up with one record`() {
        val local = snapshot(
            workouts = listOf(workout("a", at(50)), workout("b", at(30))),
            tombstones = listOf(SyncTombstone(TombstoneKind.WORKOUT, "b", at(40))),
        )

        val merged = SyncMerge.merge(local, snapshot(), now)

        assertEquals(listOf(SyncTombstone(TombstoneKind.WORKOUT, "b", now.toEpochMilli())), merged.tombstones)
        assertEquals(merged, local.after(SyncMerge.changes(local, merged)))
    }

    @Test
    fun `the same deletion recorded on both phones keeps the later time in either order`() {
        val earlier = SyncTombstone(TombstoneKind.ROUTINE, "r1", at(40))
        val later = earlier.copy(deletedAt = at(20))

        val laterLocal = SyncMerge.merge(snapshot(tombstones = listOf(later)), snapshot(tombstones = listOf(earlier)), now)
        val laterRemote = SyncMerge.merge(snapshot(tombstones = listOf(earlier)), snapshot(tombstones = listOf(later)), now)

        assertEquals(listOf(later), laterLocal.tombstones)
        assertEquals(laterLocal, laterRemote)
    }

    @Test
    fun `a link removed elsewhere goes, a link added again afterwards stays`() {
        val squatVideo = link("squat_barbell", "https://youtu.be/x", at(50))
        val removed = linkRemoved("squat_barbell", "https://youtu.be/x", at(40))

        assertTrue(SyncMerge.merge(snapshot(links = listOf(squatVideo)), snapshot(tombstones = listOf(removed)), now).links.isEmpty())
        assertEquals(1, SyncMerge.merge(snapshot(links = listOf(squatVideo.copy(addedAt = at(30)))), snapshot(tombstones = listOf(removed)), now).links.size)
    }

    @Test
    fun `only web links come in from the sync file`() {
        val remote = snapshot(
            links = listOf(
                link("squat_barbell", "https://youtu.be/x", at(30)),
                link("squat_barbell", "javascript:alert(1)", at(30)),
                link("bench_press", "intent://scan#Intent;scheme=zxing;end", at(30)),
                link("deadlift", "http://example.com/deadlift", at(30)),
            ),
        )

        val merged = SyncMerge.merge(snapshot(), remote, now)

        assertEquals(listOf("http://example.com/deadlift", "https://youtu.be/x"), merged.links.map { it.url })
    }

    @Test
    fun `applying the changes leaves each phone with exactly the merged data`() {
        val phone = snapshot(
            workouts = listOf(workout("a", at(50)), workout("gone", at(50), startedAt = "2026-10-18T09:00:00Z")),
            routines = listOf(routine("r1", at(40), "Phone")),
            customExercises = listOf(SyncCustomExercise("custom-1", at(40), "Sled", "WEIGHT_REPS")),
            links = listOf(link("squat_barbell", "https://youtu.be/x", at(50)), link("bench_press", "https://youtu.be/y", at(30))),
        )
        val tablet = snapshot(
            workouts = listOf(workout("b", at(45), startedAt = "2026-10-20T10:00:00Z")),
            routines = listOf(routine("r1", at(30), "Tablet")),
            customExercises = listOf(SyncCustomExercise("custom-1", at(35), "Sled push", "WEIGHT_REPS")),
            links = listOf(link("squat_barbell", "https://youtu.be/x", at(20)), link("deadlift", "https://youtu.be/z", at(25))),
            tombstones = listOf(SyncTombstone(TombstoneKind.WORKOUT, "gone", at(45)), linkRemoved("bench_press", "https://youtu.be/y", at(20))),
        )

        val merged = SyncMerge.merge(phone, tablet, now)

        assertEquals(at(20), merged.links.single { it.exerciseId == "squat_barbell" }.addedAt)
        assertEquals(merged, phone.after(SyncMerge.changes(phone, merged)))
        assertEquals(merged, tablet.after(SyncMerge.changes(tablet, merged)))
    }

    @Test
    fun `deletion records older than their lifetime are dropped`() {
        val old = SyncTombstone(TombstoneKind.WORKOUT, "gone", now.minus(SyncMerge.TOMBSTONE_LIFETIME).minus(Duration.ofDays(1)).toEpochMilli())

        assertTrue(SyncMerge.merge(snapshot(tombstones = listOf(old)), snapshot(), now).tombstones.isEmpty())
    }

    @Test
    fun `a deletion record exactly one lifetime old is kept, as the phone keeps it`() {
        val edge = SyncTombstone(TombstoneKind.WORKOUT, "gone", now.minus(SyncMerge.TOMBSTONE_LIFETIME).toEpochMilli())

        assertEquals(listOf(edge), SyncMerge.merge(snapshot(tombstones = listOf(edge)), snapshot(), now).tombstones)
        assertFalse(SyncMerge.isExpired(edge.deletedAt, now))
        assertTrue(SyncMerge.isExpired(edge.deletedAt - 1, now))
    }

    @Test
    fun `a synced copy replaces only an older or equal stored copy and must be newer than its deletion`() {
        assertTrue(SyncMerge.isNewest(changedAt = at(20), storedAt = at(30), deletedAt = null))
        assertTrue(SyncMerge.isNewest(changedAt = at(20), storedAt = at(20), deletedAt = null))
        assertFalse(SyncMerge.isNewest(changedAt = at(30), storedAt = at(20), deletedAt = null))
        assertTrue(SyncMerge.isNewest(changedAt = at(20), storedAt = null, deletedAt = at(30)))
        assertFalse(SyncMerge.isNewest(changedAt = at(20), storedAt = null, deletedAt = at(20)))
        assertFalse(SyncMerge.isNewest(changedAt = at(30), storedAt = null, deletedAt = at(20)))
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
