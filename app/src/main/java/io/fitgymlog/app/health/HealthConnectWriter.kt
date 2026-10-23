package io.fitgymlog.app.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Velocity
import io.fitgymlog.app.LOG_TAG
import io.fitgymlog.app.catching
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.model.isTimed
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class HealthConnectWriter(private val context: Context) {
    val writePermission = HealthPermission.getWritePermission(ExerciseSessionRecord::class)

    /** Optional: lets a cardio block another app already recorded, such as a band's auto-detected run, be left out. */
    val readPermission = HealthPermission.getReadPermission(ExerciseSessionRecord::class)

    private val speedPermission = HealthPermission.getWritePermission(SpeedRecord::class)
    private val distancePermission = HealthPermission.getWritePermission(DistanceRecord::class)

    /** Optional: a treadmill's speed and a cardio block's distance, written beside its session. */
    val detailPermissions: Set<String> = setOf(speedPermission, distancePermission)

    val permissions: Set<String> = setOf(writePermission, readPermission) + detailPermissions

    val isAvailable: Boolean
        get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    suspend fun hasPermissions(): Boolean = isAvailable && writePermission in granted()

    suspend fun readsExercise(): Boolean = isAvailable && readPermission in granted()

    private suspend fun granted(): Set<String> = client.permissionController.getGrantedPermissions()

    suspend fun write(workout: Workout, sessionType: Int, skipRecordedCardio: Boolean, exerciseOf: (String) -> Exercise, nameOf: (Exercise) -> String) {
        val recordedElsewhere = if (!skipRecordedCardio) emptyList() else catching { exerciseFromOtherApps(workout, exerciseOf) }
            .onFailure { Log.w(LOG_TAG, "Reading other apps' exercise failed; writing every cardio block", it) }
            .getOrDefault(emptyList())
        val sessions = sessionsOf(workout, sessionType, exerciseOf, recordedElsewhere, nameOf)
        if (sessions.isNotEmpty()) client.insertRecords(sessions)
        writeDetails(workout, exerciseOf, recordedElsewhere)
    }

    private suspend fun writeDetails(workout: Workout, exerciseOf: (String) -> Exercise, recordedElsewhere: List<ClosedRange<Instant>>) {
        val granted = granted()
        val details = cardioDetailsOf(workout, exerciseOf, recordedElsewhere).filter { record ->
            when (record) {
                is SpeedRecord -> speedPermission in granted
                is DistanceRecord -> distancePermission in granted
                else -> false
            }
        }
        if (details.isEmpty()) return
        catching { client.insertRecords(details) }.onFailure { Log.w(LOG_TAG, "Writing cardio speed and distance to Health Connect failed", it) }
    }

    /** Health Connect refuses reads while the app is in the background, so a workout saved then keeps every cardio block. */
    private suspend fun exerciseFromOtherApps(workout: Workout, exerciseOf: (String) -> Exercise): List<ClosedRange<Instant>> {
        val cardio = cardioSpan(workout, exerciseOf) ?: return emptyList()
        if (!readsExercise()) return emptyList()
        val range = TimeRangeFilter.between(cardio.start.minus(READ_MARGIN), cardio.endInclusive.plus(READ_MARGIN))
        val sessions = client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range)).records
            .map { OtherSession(it.metadata.dataOrigin.packageName, it.exerciseType, it.startTime..it.endTime) }
        return cardioElsewhere(sessions, context.packageName)
    }

    /** A synced workout is marked as logged in the app too; without the read permission this phone's own records can't be checked, so they are assumed. */
    suspend fun wrote(workout: Workout, exerciseOf: (String) -> Exercise): Boolean {
        val end = workout.endedAt ?: return false
        if (!readsExercise()) return true
        val ids = clientRecordIdsOf(workout, exerciseOf).toSet()
        val range = TimeRangeFilter.between(workout.startedAt.minus(READ_MARGIN), end.plus(READ_MARGIN))
        val own = ReadRecordsRequest(ExerciseSessionRecord::class, range, dataOriginFilter = setOf(DataOrigin(context.packageName)))
        return client.readRecords(own).records.any { it.metadata.clientRecordId in ids }
    }

    suspend fun delete(workout: Workout, exerciseOf: (String) -> Exercise) {
        if (!hasPermissions()) return
        // A delete naming a record that doesn't exist fails as a whole, so each record goes on its own.
        clientRecordIdsOf(workout, exerciseOf).forEach { id ->
            catching { client.deleteRecords(ExerciseSessionRecord::class, emptyList(), listOf(id)) }
        }
        val granted = granted()
        cardioDetailsOf(workout, exerciseOf).forEach { record ->
            val id = record.metadata.clientRecordId ?: return@forEach
            when {
                record is SpeedRecord && speedPermission in granted -> catching { client.deleteRecords(SpeedRecord::class, emptyList(), listOf(id)) }
                record is DistanceRecord && distancePermission in granted -> catching { client.deleteRecords(DistanceRecord::class, emptyList(), listOf(id)) }
            }
        }
    }
}

internal data class OtherSession(val packageName: String, val exerciseType: Int, val time: ClosedRange<Instant>)

/** A watch that logs the whole visit as one strength or other workout must not hide the cardio inside it. */
internal fun cardioElsewhere(sessions: List<OtherSession>, ownPackage: String): List<ClosedRange<Instant>> =
    sessions.filter { it.packageName != ownPackage && it.exerciseType in CARDIO_TYPES }.map { it.time }

internal fun cardioSpan(workout: Workout, exerciseOf: (String) -> Exercise): ClosedRange<Instant>? {
    val endedAt = workout.endedAt ?: return null
    val cardio = timelineOf(workout, endedAt, exerciseOf).filterIsInstance<CardioPart>()
    return if (cardio.isEmpty()) null else cardio.minOf { it.start }..cardio.maxOf { it.end }
}

private val CARDIO_TYPES = setOf(
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING,
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
    ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING,
    ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE,
    ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL,
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING,
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE,
)

private val READ_MARGIN: Duration = Duration.ofHours(3)

/** Keyed by uuid because the local row id restarts after a reinstall while Health Connect keeps old records. */
internal fun clientRecordIdOf(workout: Workout): String? = workout.uuid.takeIf { it.isNotEmpty() }?.let { "fitgymlog-workout-$it" }

internal fun clientRecordIdsOf(workout: Workout, exerciseOf: (String) -> Exercise): List<String> =
    sessionsOf(workout, ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, exerciseOf) { "" }.mapNotNull { it.metadata.clientRecordId }

/**
 * Each cardio set is a session of its own type, since a strength session rejects cardio segments, and
 * lifting fills the time around them, one session per stretch between cardio blocks.
 */
internal fun sessionsOf(
    workout: Workout,
    sessionType: Int,
    exerciseOf: (String) -> Exercise,
    recordedElsewhere: List<ClosedRange<Instant>> = emptyList(),
    nameOf: (Exercise) -> String,
): List<ExerciseSessionRecord> {
    val endedAt = workout.endedAt ?: return emptyList()
    val base = clientRecordIdOf(workout)
    return timelineOf(workout, endedAt, exerciseOf).filterNot { it is CardioPart && it.mostlyWithin(recordedElsewhere) }.map { part ->
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

/** Speed for a block with one, and distance as logged or, on a treadmill, as its speed over the logged time. */
internal fun cardioDetailsOf(workout: Workout, exerciseOf: (String) -> Exercise, recordedElsewhere: List<ClosedRange<Instant>> = emptyList()): List<Record> {
    val endedAt = workout.endedAt ?: return emptyList()
    val base = clientRecordIdOf(workout)
    return timelineOf(workout, endedAt, exerciseOf).filterIsInstance<CardioPart>().filterNot { it.mostlyWithin(recordedElsewhere) }.flatMap { part ->
        val set = part.tick.set
        val id = base?.let { "$it-${part.tick.exerciseIndex}-${part.tick.setIndex}" }
        val meters = set.distanceMeters ?: set.speedKmh?.let { kmh -> set.durationSeconds?.let { kmh * it / SECONDS_PER_HOUR * 1000 } }
        listOfNotNull<Record>(
            set.speedKmh?.let { speedRecord(part.start, part.end, it, id?.let { "$it-speed" }) },
            meters?.let { distanceRecord(part.start, part.end, it, id?.let { "$it-distance" }) },
        )
    }
}

private data class Tick(val completedAt: Instant, val exercise: Exercise, val exerciseIndex: Int, val setIndex: Int, val set: WorkoutSet)

private sealed interface Part {
    val start: Instant
    val end: Instant
}

private data class CardioPart(override val start: Instant, override val end: Instant, val tick: Tick) : Part {
    fun mostlyWithin(others: List<ClosedRange<Instant>>): Boolean {
        val length = Duration.between(start, end)
        return others.any { other ->
            val overlap = Duration.between(maxOf(start, other.start), minOf(end, other.endInclusive))
            overlap.multipliedBy(2) >= length
        }
    }
}

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

private fun metadataOf(clientRecordId: String?): Metadata {
    val device = Device(type = Device.TYPE_PHONE)
    return clientRecordId?.let { Metadata.activelyRecorded(device, clientRecordId = it) } ?: Metadata.activelyRecorded(device)
}

private fun speedRecord(start: Instant, end: Instant, kmh: Double, clientRecordId: String?): SpeedRecord {
    val zone = ZoneId.systemDefault()
    return SpeedRecord(
        startTime = start,
        startZoneOffset = zone.rules.getOffset(start),
        endTime = end,
        endZoneOffset = zone.rules.getOffset(end),
        samples = listOf(SpeedRecord.Sample(start, Velocity.kilometersPerHour(kmh))),
        metadata = metadataOf(clientRecordId),
    )
}

private fun distanceRecord(start: Instant, end: Instant, meters: Double, clientRecordId: String?): DistanceRecord {
    val zone = ZoneId.systemDefault()
    return DistanceRecord(
        startTime = start,
        startZoneOffset = zone.rules.getOffset(start),
        endTime = end,
        endZoneOffset = zone.rules.getOffset(end),
        distance = Length.meters(meters),
        metadata = metadataOf(clientRecordId),
    )
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
    val metadata = metadataOf(clientRecordId)
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
private const val SECONDS_PER_HOUR = 3600.0

internal val CARDIO_SESSION_TYPES = mapOf(
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING_TREADMILL to ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_STAIR_CLIMBING_MACHINE to ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE,
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING_STATIONARY to ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
    ExerciseSegment.EXERCISE_SEGMENT_TYPE_ROWING_MACHINE to ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE,
)
