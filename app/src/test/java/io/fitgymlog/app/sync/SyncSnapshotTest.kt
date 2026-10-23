package io.fitgymlog.app.sync

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncSnapshotTest {
    private fun fieldsOf(descriptor: SerialDescriptor, prefix: String = ""): List<String> = (0 until descriptor.elementsCount).flatMap { index ->
        val name = prefix + descriptor.getElementName(index)
        val child = descriptor.getElementDescriptor(index).let { if (it.kind == StructureKind.LIST) it.getElementDescriptor(0) else it }
        listOf(name) + if (child.kind == StructureKind.CLASS) fieldsOf(child, "$name.") else emptyList()
    }

    @Test
    fun `a synced field added to workouts or routines comes with a new sync version, so an older phone's copy cannot win a tie and erase it`() {
        val sets = "workout.exercises.sets"
        val planned = "routine.exercises"

        assertEquals(
            3 to listOf(
                "uuid", "updatedAt", "workout", "workout.title", "workout.startedAt", "workout.endedAt", "workout.notes", "workout.source",
                "workout.exercises", "workout.exercises.exerciseId", "workout.exercises.restSeconds", "workout.exercises.notes", sets,
                "$sets.type", "$sets.weightKg", "$sets.reps", "$sets.rpe", "$sets.distanceMeters", "$sets.durationSeconds", "$sets.completedAt",
                "$sets.level", "$sets.targetReps", "$sets.speedKmh", "workout.effort",
                "uuid", "updatedAt", "routine", "routine.name", planned, "$planned.exerciseId", "$planned.sets", "$planned.reps", "$planned.weightKg",
                "$planned.restSeconds", "$planned.durationSeconds", "$planned.level", "$planned.speedKmh",
            ),
            SyncSnapshot.VERSION to fieldsOf(SyncWorkout.serializer().descriptor) + fieldsOf(SyncRoutine.serializer().descriptor),
        )
    }

    @Test
    fun `a file from a newer app is reported as newer even when its layout changed`() {
        val newer = """{"format":"fitgymlog-sync","version":${SyncSnapshot.VERSION + 1},"workouts":{"renamed":true}}"""

        assertThrows(NewerSyncFormatException::class.java) { SyncSnapshot.decode(newer) }
    }

    @Test
    fun `a file keeps the version it was written with, which decides who wins a tie`() {
        assertEquals(1, SyncSnapshot.decode("""{"format":"fitgymlog-sync","version":1}""").version)
        assertEquals(0, SyncSnapshot.decode("""{"format":"fitgymlog-sync"}""").version)
    }

    @Test
    fun `a file that is not a sync file is reported as corrupt`() {
        assertThrows(CorruptSyncFileException::class.java) { SyncSnapshot.decode("{\"format\":\"fitgymlog-backup\",\"version\":1}") }
        assertThrows(CorruptSyncFileException::class.java) { SyncSnapshot.decode("{\"format\":\"fitgymlog-sync\",\"workouts\":") }
        assertThrows(CorruptSyncFileException::class.java) { SyncSnapshot.decode("<html>quota exceeded</html>") }
    }

    @Test
    fun `a current file decodes`() {
        val snapshot = SyncSnapshot(links = listOf(SyncLink("squat_barbell", "https://youtu.be/x", 1L)))

        assertEquals(snapshot, SyncSnapshot.decode(snapshot.encode()))
    }
}
