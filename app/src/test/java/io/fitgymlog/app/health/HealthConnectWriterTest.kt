package io.fitgymlog.app.health

import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import io.fitgymlog.app.catalog.ExerciseCatalog
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutExercise
import io.fitgymlog.app.model.WorkoutSet
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectWriterTest {
    private val start = Instant.parse("2026-10-19T10:00:00Z")
    private val workout = Workout(id = 7, uuid = "0b1c9e", title = "Day A", startedAt = start, exercises = emptyList())
    private val base = "fitgymlog-workout-0b1c9e"

    private val exercises = listOf(
        Exercise("treadmill", "Treadmill", ExerciseKind.CARDIO, ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING_TREADMILL),
        Exercise("stationary_bike", "Cycling", ExerciseKind.CARDIO, ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING_STATIONARY),
        Exercise("stair_machine", "Stair Machine (Floors)", ExerciseKind.CARDIO, ExerciseSegment.EXERCISE_SEGMENT_TYPE_STAIR_CLIMBING_MACHINE),
        Exercise("rowing_machine", "Rowing Machine", ExerciseKind.CARDIO, ExerciseSegment.EXERCISE_SEGMENT_TYPE_ROWING_MACHINE),
        Exercise("squat_barbell", "Squat (Barbell)", ExerciseKind.WEIGHT_REPS, ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT),
        Exercise("bench_press_barbell", "Bench Press (Barbell)", ExerciseKind.WEIGHT_REPS, ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS),
        Exercise("plank", "Plank", ExerciseKind.DURATION, ExerciseSegment.EXERCISE_SEGMENT_TYPE_PLANK),
    ).associateBy { it.id }

    private fun at(minutes: Long): Instant = start.plusSeconds(minutes * 60)

    private fun exercise(id: String, vararg sets: WorkoutSet) = WorkoutExercise(key = id.hashCode().toLong(), exerciseId = id, restSeconds = 0, sets = sets.toList())

    private fun lift(minute: Long) = WorkoutSet(weightKg = 60.0, reps = 5, completedAt = at(minute))

    private fun cardio(minutes: Int, tickedAt: Long) = WorkoutSet(durationSeconds = minutes * 60, completedAt = at(tickedAt))

    private fun sessions(workout: Workout) = sessionsOf(workout, ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, exercises::getValue) { it.englishName }

    private fun summary(workout: Workout) = sessions(workout).map { listOf(it.exerciseType, it.startTime, it.endTime, it.metadata.clientRecordId) }

    @Test
    fun `a treadmill block gets its speed and the distance it covers, a logged distance wins, and cardio another app recorded gets neither`() {
        val day = workout.copy(
            endedAt = at(60),
            exercises = listOf(
                exercise("treadmill", WorkoutSet(durationSeconds = 20 * 60, speedKmh = 9.0, completedAt = at(20))),
                exercise("rowing_machine", WorkoutSet(durationSeconds = 10 * 60, distanceMeters = 2_150.0, completedAt = at(35))),
                exercise("treadmill", WorkoutSet(durationSeconds = 10 * 60, speedKmh = 10.0, distanceMeters = 1_550.0, completedAt = at(50))),
                exercise("stationary_bike", cardio(minutes = 5, tickedAt = 58)),
            ),
        )
        fun describe(records: List<Record>) = records.map { record ->
            when (record) {
                is SpeedRecord -> listOf("speed", record.metadata.clientRecordId, record.samples.single().speed.inKilometersPerHour, record.startTime, record.endTime)
                is DistanceRecord -> listOf("distance", record.metadata.clientRecordId, record.distance.inMeters, record.startTime, record.endTime)
                else -> listOf(record)
            }
        }

        assertEquals(
            listOf(
                listOf("speed", "$base-0-0-speed", 9.0, at(0), at(20)),
                listOf("distance", "$base-0-0-distance", 3_000.0, at(0), at(20)),
                listOf("distance", "$base-1-0-distance", 2_150.0, at(25), at(35)),
                listOf("speed", "$base-2-0-speed", 10.0, at(40), at(50)),
                listOf("distance", "$base-2-0-distance", 1_550.0, at(40), at(50)),
            ),
            describe(cardioDetailsOf(day, exercises::getValue)),
        )
        assertEquals(listOf("$base-1-0-distance", "$base-2-0-speed", "$base-2-0-distance"), cardioDetailsOf(day, exercises::getValue, listOf(at(0)..at(20))).map { it.metadata.clientRecordId })
    }

    @Test
    fun `lifting on both sides of a skipped cardio block keeps the ids that deleting and checking look for`() {
        val day = workout.copy(
            endedAt = at(40),
            exercises = listOf(exercise("squat_barbell", lift(5)), exercise("treadmill", cardio(minutes = 20, tickedAt = 30)), exercise("bench_press_barbell", lift(35))),
        )

        val written = sessionsOf(day, ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, exercises::getValue, listOf(at(10)..at(30))) { it.englishName }.map { it.metadata.clientRecordId }

        assertEquals(listOf(base, "$base-lifting-1"), written)
        assertTrue(clientRecordIdsOf(day, exercises::getValue).containsAll(written))
    }

    @Test
    fun `records are keyed by the workout uuid, not the local row id`() {
        assertEquals(base, clientRecordIdOf(workout))
    }

    @Test
    fun `a workout without a uuid gets no client record id`() {
        assertNull(clientRecordIdOf(workout.copy(uuid = "")))
    }

    @Test
    fun `a treadmill warm-up and a stair finisher are their own sessions, and lifting covers the time between`() {
        val day = workout.copy(
            endedAt = at(21),
            exercises = listOf(
                exercise("treadmill", cardio(minutes = 5, tickedAt = 3)),
                exercise("squat_barbell", lift(8), lift(11)),
                exercise("stair_machine", cardio(minutes = 5, tickedAt = 20)),
            ),
        )

        assertEquals(
            listOf(
                listOf(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL, at(-2), at(3), "$base-0-0"),
                listOf(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, at(3), at(15), base),
                listOf(ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE, at(15), at(20), "$base-2-0"),
            ),
            summary(day),
        )
        assertEquals(listOf(listOf(at(7), at(8)), listOf(at(10), at(11))), sessions(day)[1].segments.map { listOf(it.startTime, it.endTime) })
    }

    @Test
    fun `cardio in the middle splits lifting in two, and a block never reaches back past the set before it`() {
        val day = workout.copy(
            endedAt = at(32),
            exercises = listOf(
                exercise("squat_barbell", lift(8), lift(11)),
                exercise("stationary_bike", cardio(minutes = 20, tickedAt = 25)),
                exercise("bench_press_barbell", lift(28), lift(31)),
            ),
        )

        assertEquals(
            listOf(
                listOf(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, at(0), at(11), base),
                listOf(ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY, at(11), at(25), "$base-1-0"),
                listOf(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, at(25), at(32), "$base-lifting-1"),
            ),
            summary(day),
        )
    }

    @Test
    fun `alternating cardio and lifting gives four sessions in order, none overlapping`() {
        val day = workout.copy(
            endedAt = at(27),
            exercises = listOf(
                exercise("treadmill", cardio(minutes = 5, tickedAt = 5)),
                exercise("squat_barbell", lift(8), lift(11)),
                exercise("stair_machine", cardio(minutes = 5, tickedAt = 20)),
                exercise("bench_press_barbell", lift(23), lift(26)),
            ),
        )

        assertEquals(
            listOf(
                listOf(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL, at(0), at(5), "$base-0-0"),
                listOf(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, at(5), at(15), base),
                listOf(ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE, at(15), at(20), "$base-2-0"),
                listOf(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, at(20), at(27), "$base-lifting-1"),
            ),
            summary(day),
        )
        assertEquals(listOf("Squat (Barbell): 60kg x 5, 60kg x 5", "Bench Press (Barbell): 60kg x 5, 60kg x 5"), sessions(day).filter { it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING }.map { it.notes })
        assertEquals(
            "Bench Press (Barbell): 60kg x 5, 60kg x 5\nEffort: 7/10",
            sessions(day.copy(effort = 7)).last { it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING }.notes,
        )
    }

    @Test
    fun `back-to-back cardio sets follow each other, each with its own record`() {
        val day = workout.copy(
            endedAt = at(30),
            exercises = listOf(
                exercise("treadmill", cardio(minutes = 10, tickedAt = 10), cardio(minutes = 20, tickedAt = 25)),
            ),
        )

        assertEquals(
            listOf(
                listOf(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL, at(0), at(10), "$base-0-0"),
                listOf(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL, at(10), at(25), "$base-0-1"),
            ),
            summary(day),
        )
    }

    @Test
    fun `a cardio block another app recorded for at least half its time is left out, and lifting is still written`() {
        val day = workout.copy(
            endedAt = at(40),
            exercises = listOf(
                exercise("squat_barbell", lift(5), lift(10)),
                exercise("treadmill", cardio(minutes = 20, tickedAt = 30)),
                exercise("stair_machine", cardio(minutes = 10, tickedAt = 40)),
            ),
        )
        val bandRun = at(20)..at(30)
        val bandWalk = at(36)..at(40)

        val written = sessionsOf(day, ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, exercises::getValue, listOf(bandRun, bandWalk)) { it.englishName }

        assertEquals(listOf(base, "$base-2-0"), written.map { it.metadata.clientRecordId })
    }

    @Test
    fun `only other apps' cardio sessions count, so a watch's whole-visit workout or this app's own records hide nothing`() {
        val run = at(10)..at(30)
        val sessions = listOf(
            OtherSession("com.watch", ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL, run),
            OtherSession("com.watch", ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, at(0)..at(60)),
            OtherSession("com.watch", ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, at(0)..at(60)),
            OtherSession("io.fitgymlog.app", ExerciseSessionRecord.EXERCISE_TYPE_WALKING, at(40)..at(50)),
        )

        assertEquals(listOf(run), cardioElsewhere(sessions, ownPackage = "io.fitgymlog.app"))
    }

    @Test
    fun `the span to look for other apps' cardio starts at the first block, before the workout itself, and lifting alone has none`() {
        val warmUp = workout.copy(
            endedAt = at(21),
            exercises = listOf(exercise("treadmill", cardio(minutes = 5, tickedAt = 3)), exercise("squat_barbell", lift(8)), exercise("stair_machine", cardio(minutes = 5, tickedAt = 20))),
        )
        val liftingOnly = workout.copy(endedAt = at(21), exercises = listOf(exercise("squat_barbell", lift(8))))

        assertEquals(at(-2)..at(20), cardioSpan(warmUp, exercises::getValue))
        assertNull(cardioSpan(liftingOnly, exercises::getValue))
    }

    @Test
    fun `a workout without cardio is one session from start to finish`() {
        val day = workout.copy(endedAt = at(30), exercises = listOf(exercise("squat_barbell", lift(8))))

        assertEquals(listOf(listOf(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, at(0), at(30), base)), summary(day))
    }

    @Test
    fun `deleting removes exactly the records writing made, which for cardio alone has no lifting record`() {
        val cardioOnly = workout.copy(endedAt = at(12), exercises = listOf(exercise("treadmill", cardio(minutes = 10, tickedAt = 10), WorkoutSet(completedAt = at(11)))))
        val mixed = workout.copy(endedAt = at(30), exercises = listOf(exercise("squat_barbell", lift(8)), exercise("stair_machine", cardio(minutes = 5, tickedAt = 20))))

        assertEquals(listOf("$base-0-0"), clientRecordIdsOf(cardioOnly, exercises::getValue))
        assertEquals(listOf(base, "$base-1-0"), clientRecordIdsOf(mixed, exercises::getValue))
        assertEquals(sessions(mixed).map { it.metadata.clientRecordId }, clientRecordIdsOf(mixed, exercises::getValue))
    }

    @Test
    fun `a timed lift's segment lasts its duration, and other sets get a minute whatever duration they carry`() {
        val day = workout.copy(
            endedAt = at(30),
            exercises = listOf(
                exercise("plank", WorkoutSet(durationSeconds = 120, completedAt = at(10))),
                exercise("squat_barbell", lift(20).copy(durationSeconds = 600)),
            ),
        )

        assertEquals(listOf(listOf(at(8), at(10)), listOf(at(19), at(20))), sessions(day).single().segments.map { listOf(it.startTime, it.endTime) })
    }

    @Test
    fun `every built-in cardio exercise has a Health Connect session type of its own, and the rower writes rowing machine`() {
        val cardio = ExerciseCatalog.all.filter { it.kind == ExerciseKind.CARDIO }

        assertEquals(emptyList<String>(), cardio.filter { it.segmentType !in CARDIO_SESSION_TYPES }.map { it.id })
        assertEquals(cardio.size, cardio.map { CARDIO_SESSION_TYPES[it.segmentType] }.distinct().size)
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE, CARDIO_SESSION_TYPES[ExerciseCatalog.all.single { it.id == "rowing_machine" }.segmentType])
    }
}
