package io.fitgymlog.app.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.Workout
import java.time.Instant
import java.time.ZoneId

/**
 * Writes finished workouts to Health Connect, where Samsung Health picks them up. Samsung Health
 * keeps only type, time and calories, so the set details stay in this app.
 */
class HealthConnectWriter(private val context: Context) {
    val permissions: Set<String> = setOf(HealthPermission.getWritePermission(ExerciseSessionRecord::class))

    val isAvailable: Boolean
        get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun hasPermissions(): Boolean =
        isAvailable && client.permissionController.getGrantedPermissions().containsAll(permissions)

    suspend fun write(workout: Workout, sessionType: Int, exerciseOf: (String) -> Exercise, nameOf: (Exercise) -> String) {
        val endedAt = workout.endedAt ?: return
        val zone = ZoneId.systemDefault()
        val segments = segmentsOf(workout, exerciseOf)
        val device = Device(type = Device.TYPE_PHONE)
        val metadata = clientRecordIdOf(workout)?.let { Metadata.activelyRecorded(device, clientRecordId = it) } ?: Metadata.activelyRecorded(device)
        fun record(withSegments: List<ExerciseSegment>) = ExerciseSessionRecord(
            startTime = workout.startedAt,
            startZoneOffset = zone.rules.getOffset(workout.startedAt),
            endTime = endedAt,
            endZoneOffset = zone.rules.getOffset(endedAt),
            metadata = metadata,
            exerciseType = sessionType,
            title = workout.title,
            notes = summary(workout, exerciseOf, nameOf),
            segments = withSegments,
        )
        val session = runCatching { record(segments) }.getOrElse {
            record(segments.map { ExerciseSegment(it.startTime, it.endTime, ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT, it.repetitions) })
        }
        client.insertRecords(listOf(session))
    }

    suspend fun delete(workout: Workout) {
        val clientRecordId = clientRecordIdOf(workout) ?: return
        if (!hasPermissions()) return
        client.deleteRecords(ExerciseSessionRecord::class, emptyList(), listOf(clientRecordId))
    }

    /** One segment per completed set, ending when it was ticked and starting at most a minute earlier. */
    private fun segmentsOf(workout: Workout, exerciseOf: (String) -> Exercise): List<ExerciseSegment> {
        val ticks = workout.exercises
            .flatMap { exercise -> exercise.sets.mapNotNull { set -> set.completedAt?.let { Triple(it, exercise.exerciseId, set.reps ?: 0) } } }
            .sortedBy { it.first }
        var previousEnd: Instant = workout.startedAt
        return ticks.mapNotNull { (completedAt, exerciseId, reps) ->
            val start = maxOf(previousEnd, completedAt.minusSeconds(SET_SECONDS), workout.startedAt)
            if (!start.isBefore(completedAt) || completedAt.isAfter(workout.endedAt)) return@mapNotNull null
            previousEnd = completedAt
            ExerciseSegment(start, completedAt, exerciseOf(exerciseId).segmentType, reps)
        }
    }

    private fun summary(workout: Workout, exerciseOf: (String) -> Exercise, nameOf: (Exercise) -> String): String =
        workout.exercises.joinToString("\n") { exercise ->
            val sets = exercise.sets.joinToString(", ") { set ->
                listOfNotNull(set.weightKg?.let { "${formatKg(it)}kg" }, set.reps?.toString()).joinToString(" x ")
            }
            "${nameOf(exerciseOf(exercise.exerciseId))}: $sets"
        }

    private fun formatKg(kg: Double): String = if (kg % 1.0 == 0.0) kg.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, kg).trimEnd('0').trimEnd('.')

    private companion object {
        const val SET_SECONDS = 60L
    }
}

/** Keyed by uuid because the local row id restarts after a reinstall while Health Connect keeps old records. */
internal fun clientRecordIdOf(workout: Workout): String? = workout.uuid.takeIf { it.isNotEmpty() }?.let { "fitgymlog-workout-$it" }
