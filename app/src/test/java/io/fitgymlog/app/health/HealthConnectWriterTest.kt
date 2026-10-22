package io.fitgymlog.app.health

import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import io.fitgymlog.app.catalog.ExerciseCatalog
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutExercise
import io.fitgymlog.app.model.WorkoutSet
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthConnectWriterTest {
    private val start = Instant.parse("2026-10-19T10:00:00Z")
    private val workout = Workout(id = 7, uuid = "0b1c9e", title = "Day A", startedAt = start, exercises = emptyList())
    private val base = "fitgymlog-workout-0b1c9e"

    private val exercises = listOf(
        Exercise("treadmill", "Treadmill", ExerciseKind.CARDIO, ExerciseSegment.EXERCISE_SEGMENT_TYPE_RUNNING_TREADMILL),
        Exercise("stationary_bike", "Cycling", ExerciseKind.CARDIO, ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING_STATIONARY),
        Exercise("stair_machine", "Stair Machine (Floors)", ExerciseKind.CARDIO, ExerciseSegment.EXERCISE_SEGMENT_TYPE_STAIR_CLIMBING_MACHINE),
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
    fun `every built-in cardio exercise has a Health Connect session type of its own`() {
        val unmapped = ExerciseCatalog.all.filter { it.kind == ExerciseKind.CARDIO && it.segmentType !in CARDIO_SESSION_TYPES }

        assertEquals(emptyList<String>(), unmapped.map { it.id })
    }
}
