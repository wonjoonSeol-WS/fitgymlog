package io.fitgymlog.app.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutSet
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
        val base = clientRecordIdOf(workout) ?: return
        if (!hasPermissions()) return
        val ids = listOf(base) + cardioBlocks(workout, exerciseOf).map { it.clientRecordId(base) }
        client.deleteRecords(ExerciseSessionRecord::class, emptyList(), ids)
    }
}

/** Keyed by uuid because the local row id restarts after a reinstall while Health Connect keeps old records. */
internal fun clientRecordIdOf(workout: Workout): String? = workout.uuid.takeIf { it.isNotEmpty() }?.let { "fitgymlog-workout-$it" }

/**
 * Lifting goes in one session of [sessionType]; each cardio set gets a session of its own type, which a
 * strength session could not hold as a segment, so Samsung Health lists the run or the stairs separately.
 */
internal fun sessionsOf(workout: Workout, sessionType: Int, exerciseOf: (String) -> Exercise, nameOf: (Exercise) -> String): List<ExerciseSessionRecord> {
    val endedAt = workout.endedAt ?: return emptyList()
    val base = clientRecordIdOf(workout)
    val blocks = cardioBlocks(workout, exerciseOf)
    val cardio = blocks.map { block ->
        session(
            start = block.start,
            end = block.end,
            type = CARDIO_SESSION_TYPES[block.exercise.segmentType] ?: ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT,
            title = nameOf(block.exercise),
            notes = null,
            segments = emptyList(),
            clientRecordId = base?.let(block::clientRecordId),
        )
    }
    val lifts = workout.exercises.filter { exerciseOf(it.exerciseId).kind != ExerciseKind.CARDIO }
    val ticks = lifts.flatMap { exercise -> exercise.sets.mapNotNull { set -> set.completedAt?.let { Tick(it, exercise.exerciseId, set) } } }
        .sortedBy { it.completedAt }
    if (ticks.isEmpty()) return cardio
    val first = ticks.first().completedAt
    val last = ticks.last().completedAt
    val start = blocks.filter { !it.end.isAfter(first) }.maxOfOrNull { it.end } ?: workout.startedAt
    val end = blocks.filter { !it.start.isBefore(last) }.minOfOrNull { it.start } ?: endedAt
    val (from, to) = if (start.isBefore(end)) start to end else workout.startedAt to endedAt
    val strength = session(
        start = from,
        end = to,
        type = sessionType,
        title = workout.title,
        notes = summary(lifts.map { it.exerciseId to it.sets }, exerciseOf, nameOf),
        segments = segmentsOf(ticks, from, to, exerciseOf),
        clientRecordId = base,
    )
    return listOf(strength) + cardio
}

internal data class CardioBlock(val exerciseIndex: Int, val setIndex: Int, val exercise: Exercise, val start: Instant, val end: Instant) {
    fun clientRecordId(base: String): String = "$base-$exerciseIndex-$setIndex"
}

/** A cardio set ends when it was ticked and started its duration earlier. */
internal fun cardioBlocks(workout: Workout, exerciseOf: (String) -> Exercise): List<CardioBlock> {
    val endedAt = workout.endedAt ?: return emptyList()
    return workout.exercises.flatMapIndexed { exerciseIndex, exercise ->
        val info = exerciseOf(exercise.exerciseId)
        if (info.kind != ExerciseKind.CARDIO) return@flatMapIndexed emptyList()
        exercise.sets.mapIndexedNotNull { setIndex, set ->
            val end = minOf(set.completedAt ?: return@mapIndexedNotNull null, endedAt)
            val seconds = set.durationSeconds?.takeIf { it > 0 } ?: return@mapIndexedNotNull null
            val start = maxOf(end.minusSeconds(seconds.toLong()), workout.startedAt)
            if (start.isBefore(end)) CardioBlock(exerciseIndex, setIndex, info, start, end) else null
        }
    }
}

private data class Tick(val completedAt: Instant, val exerciseId: String, val set: WorkoutSet)

/** One segment per completed set, ending when it was ticked and starting its duration, or at most a minute, earlier. */
private fun segmentsOf(ticks: List<Tick>, from: Instant, to: Instant, exerciseOf: (String) -> Exercise): List<ExerciseSegment> {
    var previousEnd = from
    return ticks.mapNotNull { (completedAt, exerciseId, set) ->
        val start = maxOf(previousEnd, completedAt.minusSeconds((set.durationSeconds ?: SET_SECONDS).toLong()))
        if (!start.isBefore(completedAt) || completedAt.isAfter(to)) return@mapNotNull null
        previousEnd = completedAt
        ExerciseSegment(start, completedAt, exerciseOf(exerciseId).segmentType, set.reps ?: 0)
    }
}

private fun session(
    start: Instant,
    end: Instant,
    type: Int,
    title: String,
    notes: String?,
    segments: List<ExerciseSegment>,
    clientRecordId: String?,
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

private fun summary(exercises: List<Pair<String, List<WorkoutSet>>>, exerciseOf: (String) -> Exercise, nameOf: (Exercise) -> String): String =
    exercises.joinToString("\n") { (exerciseId, sets) ->
        val text = sets.joinToString(", ") { set ->
            listOfNotNull(set.weightKg?.let { "${formatKg(it)}kg" }, set.reps?.toString(), set.durationSeconds?.let { "${it}s" }).joinToString(" x ")
        }
        "${nameOf(exerciseOf(exerciseId))}: $text"
    }

private fun formatKg(kg: Double): String = if (kg % 1.0 == 0.0) kg.toLong().toString() else "%.2f".format(Locale.ROOT, kg).trimEnd('0').trimEnd('.')

private const val SET_SECONDS = 60

private val CARDIO_SESSION_TYPES = mapOf(
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING_TREADMILL to ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_STAIR_CLIMBING_MACHINE to ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE,
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING_STATIONARY to ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
)
