package io.fitgymlog.app.data

import io.fitgymlog.app.model.Routine
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutExercise
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.workout.ActiveWorkout
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the one in-progress workout. It lives in memory for instant UI and band updates and is
 * written through to the database on every change, so it survives the app being killed mid-session.
 */
class WorkoutRepository(private val dao: WorkoutDao, private val tombstones: TombstoneDao, scope: CoroutineScope) {
    private val mutex = Mutex()
    private val loaded = CompletableDeferred<Unit>()
    private val _active = MutableStateFlow<ActiveWorkout?>(null)
    val active: StateFlow<ActiveWorkout?> = _active.asStateFlow()

    val history: Flow<List<Workout>> = dao.finished().map { workouts -> workouts.map { it.toWorkout() } }

    init {
        scope.launch {
            _active.value = dao.inProgress()?.toActiveWorkout()
            loaded.complete(Unit)
        }
    }

    suspend fun awaitLoaded() = loaded.await()

    fun observe(id: Long): Flow<Workout?> = dao.observe(id).map { it?.toWorkout() }

    suspend fun start(title: String, routine: Routine?, exercises: List<WorkoutExercise>, now: Instant): ActiveWorkout {
        loaded.await()
        return mutex.withLock {
            _active.value?.let { return@withLock it }
            val draft = ActiveWorkout(
                Workout(uuid = UUID.randomUUID().toString(), title = title, startedAt = now, routineId = routine?.id, exercises = exercises),
            )
            val id = dao.save(draft.toGraph())
            draft.copy(workout = draft.workout.copy(id = id)).also { _active.value = it }
        }
    }

    suspend fun update(transform: (ActiveWorkout) -> ActiveWorkout) {
        loaded.await()
        mutex.withLock {
            val current = _active.value ?: return
            val next = transform(current)
            if (next == current) return
            _active.value = next
            dao.save(next.toGraph())
        }
    }

    /** Saves the finished workout, or drops it when no set was completed. */
    suspend fun finish(endedAt: Instant): Workout? {
        loaded.await()
        return mutex.withLock {
            val current = _active.value ?: return@withLock null
            _active.value = null
            val finished = current.finish(endedAt)?.copy(updatedAt = Instant.now())
            if (finished == null) {
                dao.delete(current.workout.id)
                null
            } else {
                dao.save(finished.toGraph())
                finished
            }
        }
    }

    suspend fun discard() {
        loaded.await()
        mutex.withLock {
            val current = _active.value ?: return
            _active.value = null
            dao.delete(current.workout.id)
        }
    }

    suspend fun delete(id: Long) = tombstones.deleteWorkout(id, System.currentTimeMillis())

    suspend fun previousSets(exerciseId: String): List<WorkoutSet> = dao.lastSetsOf(exerciseId).map { it.toPreviousSet() }

    suspend fun allFinished(): List<Workout> = dao.allFinished().map { it.toWorkout() }

    /** Adds workouts from another app or a backup, skipping any that start in the same minute with the same title. */
    suspend fun importFinished(workouts: List<Workout>): Int {
        val existing = dao.finishedKeys().map { minuteKey(Instant.ofEpochMilli(it.startedAt), it.title) }.toMutableSet()
        val fresh = workouts.filter { existing.add(minuteKey(it.startedAt, it.title)) }
        val now = Instant.now()
        dao.saveAll(fresh.map { it.copy(id = 0, uuid = UUID.randomUUID().toString(), updatedAt = now).toGraph() })
        return fresh.size
    }

    private fun minuteKey(startedAt: Instant, title: String) = "${startedAt.truncatedTo(ChronoUnit.MINUTES)}|$title"
}
