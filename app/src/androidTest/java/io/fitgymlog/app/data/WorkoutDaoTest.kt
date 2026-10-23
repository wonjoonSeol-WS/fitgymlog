package io.fitgymlog.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.fitgymlog.app.model.Workout
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutDaoTest {
    private val database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, GymDatabase::class.java).build()
    private val dao = database.workouts()
    private val minute = Instant.parse("2026-10-22T18:00:00Z")

    @After
    fun close() = database.close()

    private fun finished(uuid: String, title: String, startedAt: Instant, endedAt: Instant? = startedAt.plusSeconds(3_600)) =
        Workout(uuid = uuid, title = title, startedAt = startedAt, endedAt = endedAt, exercises = emptyList())

    private fun count(uuid: String, title: String) =
        runBlocking { dao.countFinished(uuid, title, minute.toEpochMilli(), minute.plusSeconds(60).toEpochMilli()) }

    @Test
    fun onlyAnotherFinishedWorkoutWithTheSameTitleInTheSameMinuteCounts() = runBlocking {
        dao.save(finished("same-minute", "Day A", minute.plusSeconds(59)).toGraph())
        dao.save(finished("next-minute", "Day A", minute.plusSeconds(60)).toGraph())
        dao.save(finished("other-title", "Day B", minute.plusSeconds(10)).toGraph())
        dao.save(finished("open", "Day A", minute.plusSeconds(20), endedAt = null).toGraph())

        assertEquals(1, count("moving", "Day A"))
        assertEquals(0, count("same-minute", "Day A"))
        assertEquals(1, count("moving", "Day B"))
    }

    @Test
    fun finishedWorkoutsFromTheGivenMomentOnAreCounted() = runBlocking {
        dao.save(finished("before", "Day A", minute.minusSeconds(1)).toGraph())
        dao.save(finished("at", "Day A", minute).toGraph())
        dao.save(finished("after", "Day B", minute.plusSeconds(86_400)).toGraph())
        dao.save(finished("open", "Day A", minute.plusSeconds(20), endedAt = null).toGraph())

        assertEquals(2, dao.countFinishedSince(minute.toEpochMilli()))
    }
}
