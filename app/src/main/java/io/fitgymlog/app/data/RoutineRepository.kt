package io.fitgymlog.app.data

import io.fitgymlog.app.model.Routine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoutineRepository(private val dao: RoutineDao, private val tombstones: TombstoneDao) {
    val routines: Flow<List<Routine>> = dao.all().map { routines -> routines.map { it.toRoutine() } }

    suspend fun all(): List<Routine> = dao.allNow().map { it.toRoutine() }

    suspend fun find(id: Long): Routine? = dao.find(id)?.toRoutine()

    suspend fun save(routine: Routine): Long =
        dao.saveEdit(routine.id, routine.name, routine.exercises.map { it.toEntity() }, System.currentTimeMillis())

    suspend fun delete(id: Long) = tombstones.deleteRoutine(id, System.currentTimeMillis())
}
