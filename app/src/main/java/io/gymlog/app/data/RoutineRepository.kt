package io.gymlog.app.data

import io.gymlog.app.model.Routine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoutineRepository(private val dao: RoutineDao) {
    val routines: Flow<List<Routine>> = dao.all().map { routines -> routines.map { it.toRoutine() } }

    suspend fun all(): List<Routine> = dao.allNow().map { it.toRoutine() }

    suspend fun find(id: Long): Routine? = dao.find(id)?.toRoutine()

    suspend fun save(routine: Routine): Long {
        val position = if (routine.id == 0L) dao.nextPosition() else dao.find(routine.id)?.routine?.position ?: dao.nextPosition()
        return dao.save(RoutineEntity(id = routine.id, name = routine.name, position = position), routine.exercises.map { it.toEntity() })
    }

    suspend fun delete(id: Long) = dao.delete(id)
}
