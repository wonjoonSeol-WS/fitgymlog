package io.fitgymlog.app.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import io.fitgymlog.app.catching
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.model.isTimed
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class HealthConnectWriter(private val context: Context) {
    val permissions: Set<String> = setOf(HealthPermission.getWritePermission(ExerciseSessionRecord::class))

    val isAvailable: Boolean
        get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun hasPermissions(): Boolean =
        isAvailable && client.permissionController.getGrantedPermissions().containsAll(permissions)

    suspend fun write(workout: Workout, sessionType: Int, exerciseOf: (String) -> Exercise, nameOf: (Exercise) -> String) {
        val sessions = sessionsOf(workout, sessionType, exerciseOf, nameOf)
        if (sessions.isNotEmpty()) client.insertRecords(sessions)
    }

    suspend fun delete(workout: Workout, exerciseOf: (String) -> Exercise) {
        if (!hasPermissions()) return
        // A delete naming a record that doesn't exist fails as a whole, so each record goes on its own.
        clientRecordIdsOf(workout, exerciseOf).forEach { id ->
            catching { client.deleteRecords(ExerciseSessionRecord::class, emptyList(), listOf(id)) }
        }
    }
}

/** Keyed by uuid because the local row id restarts after a reinstall while Health Connect keeps old records. */
internal fun clientRecordIdOf(workout: Workout): String? = workout.uuid.takeIf { it.isNotEmpty() }?.let { "fitgymlog-workout-$it" }

internal fun clientRecordIdsOf(workout: Workout, exerciseOf: (String) -> Exercise): List<String> =
    sessionsOf(workout, ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, exerciseOf) { "" }.mapNotNull { it.metadata.clientRecordId }

/**
 * Each cardio set is a session of its own type, since a strength session rejects cardio segments, and
 * lifting fills the time around them, one session per stretch between cardio blocks.
 */
internal fun sessionsOf(workout: Workout, sessionType: Int, exerciseOf: (String) -> Exercise, nameOf: (Exercise) -> String): List<ExerciseSessionRecord> {
    val endedAt = workout.endedAt ?: return emptyList()
    val base = clientRecordIdOf(workout)
    return timelineOf(workout, endedAt, exerciseOf).map { part ->
        when (part) {
            is CardioPart -> session(
                start = part.start,
                end = part.end,
                type = CARDIO_SESSION_TYPES[part.tick.exercise.segmentType] ?: ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT,
                title = nameOf(part.tick.exercise),
                clientRecordId = base?.let { "$it-${part.tick.exerciseIndex}-${part.tick.setIndex}" },
            )
            is LiftingPart -> session(
                start = part.start,
                end = part.end,
                type = sessionType,
                title = workout.title,
                notes = summary(part.ticks, nameOf),
                segments = segmentsOf(part),
                clientRecordId = base?.let { if (part.index == 0) it else "$it-lifting-${part.index}" },
            )
        }
    }
}

private data class Tick(val completedAt: Instant, val exercise: Exercise, val exerciseIndex: Int, val setIndex: Int, val set: WorkoutSet)

private sealed interface Part {
    val start: Instant
    val end: Instant
}

private data class CardioPart(override val start: Instant, override val end: Instant, val tick: Tick) : Part

private data class LiftingPart(override val start: Instant, override val end: Instant, val index: Int, val ticks: List<Tick>) : Part

private fun timelineOf(workout: Workout, endedAt: Instant, exerciseOf: (String) -> Exercise): List<Part> {
    val ticks = workout.exercises.flatMapIndexed { exerciseIndex, exercise ->
        val info = exerciseOf(exercise.exerciseId)
        exercise.sets.mapIndexedNotNull { setIndex, set -> set.completedAt?.let { Tick(it, info, exerciseIndex, setIndex, set) } }
    }.sortedBy { it.completedAt }
    val parts = mutableListOf<Part>()
    var lifts = mutableListOf<Tick>()
    var liftingFrom = workout.startedAt
    var previousEnd: Instant? = null
    fun closeLifting(until: Instant) {
        if (lifts.isNotEmpty() && liftingFrom.isBefore(until)) parts += LiftingPart(liftingFrom, until, parts.count { it is LiftingPart }, lifts)
        lifts = mutableListOf()
    }
    for (tick in ticks) {
        if (tick.exercise.kind != ExerciseKind.CARDIO) {
            lifts += tick
            previousEnd = tick.completedAt
            continue
        }
        val seconds = tick.set.durationSeconds?.takeIf { it > 0 } ?: continue
        val start = tick.completedAt.minusSeconds(seconds.toLong()).let { from -> previousEnd?.let { maxOf(it, from) } ?: from }
        if (!start.isBefore(tick.completedAt)) continue
        closeLifting(start)
        parts += CardioPart(start, tick.completedAt, tick)
        previousEnd = tick.completedAt
        liftingFrom = tick.completedAt
    }
    closeLifting(endedAt)
    return parts
}

private fun segmentsOf(part: LiftingPart): List<ExerciseSegment> {
    var previousEnd = part.start
    return part.ticks.mapNotNull { tick ->
        val seconds = tick.set.durationSeconds?.takeIf { it > 0 && tick.exercise.kind.isTimed } ?: SET_SECONDS
        val start = maxOf(previousEnd, tick.completedAt.minusSeconds(seconds.toLong()))
        if (!start.isBefore(tick.completedAt) || tick.completedAt.isAfter(part.end)) return@mapNotNull null
        previousEnd = tick.completedAt
        ExerciseSegment(start, tick.completedAt, tick.exercise.segmentType, tick.set.reps ?: 0)
    }
}

private fun session(
    start: Instant,
    end: Instant,
    type: Int,
    title: String,
    clientRecordId: String?,
    notes: String? = null,
    segments: List<ExerciseSegment> = emptyList(),
): ExerciseSessionRecord {
    val zone = ZoneId.systemDefault()
    val device = Device(type = Device.TYPE_PHONE)
    val metadata = clientRecordId?.let { Metadata.activelyRecorded(device, clientRecordId = it) } ?: Metadata.activelyRecorded(device)
    fun record(withSegments: List<ExerciseSegment>) = ExerciseSessionRecord(
        startTime = start,
        startZoneOffset = zone.rules.getOffset(start),
        endTime = end,
        endZoneOffset = zone.rules.getOffset(end),
        metadata = metadata,
        exerciseType = type,
        title = title,
        notes = notes,
        segments = withSegments,
    )
    return runCatching { record(segments) }.getOrElse {
        record(segments.map { ExerciseSegment(it.startTime, it.endTime, ExerciseSegment.EXERCISE_SEGMENT_TYPE_OTHER_WORKOUT, it.repetitions) })
    }
}

private fun summary(ticks: List<Tick>, nameOf: (Exercise) -> String): String =
    ticks.groupBy { it.exerciseIndex }.toSortedMap().values.joinToString("\n") { sets ->
        val text = sets.sortedBy { it.setIndex }.joinToString(", ") { tick ->
            val set = tick.set
            listOfNotNull(set.weightKg?.let { "${formatKg(it)}kg" }, set.reps?.toString(), set.durationSeconds?.let { "${it}s" }).joinToString(" x ")
        }
        "${nameOf(sets.first().exercise)}: $text"
    }

private fun formatKg(kg: Double): String = if (kg % 1.0 == 0.0) kg.toLong().toString() else "%.2f".format(Locale.ROOT, kg).trimEnd('0').trimEnd('.')

private const val SET_SECONDS = 60

internal val CARDIO_SESSION_TYPES = mapOf(
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING_TREADMILL to ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_STAIR_CLIMBING_MACHINE to ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE,
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING_STATIONARY to ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
)
