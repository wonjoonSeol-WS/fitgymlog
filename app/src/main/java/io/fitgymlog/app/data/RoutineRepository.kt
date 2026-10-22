package io.fitgymlog.app.data

import io.fitgymlog.app.model.Routine
import io.fitgymlog.app.sync.TombstoneKind
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoutineRepository(private val dao: RoutineDao, private val tombstones: TombstoneDao) {
    val routines: Flow<List<Routine>> = dao.all().map { routines -> routines.map { it.toRoutine() } }

    suspend fun all(): List<Routine> = dao.allNow().map { it.toRoutine() }

    suspend fun find(id: Long): Routine? = dao.find(id)?.toRoutine()

    suspend fun save(routine: Routine): Long {
        val existing = if (routine.id == 0L) null else dao.find(routine.id)?.routine
        val entity = RoutineEntity(
            id = routine.id,
            uuid = existing?.uuid ?: routine.uuid.ifEmpty { UUID.randomUUID().toString() },
            updatedAt = System.currentTimeMillis(),
            name = routine.name,
            position = existing?.position ?: dao.nextPosition(),
        )
        return dao.save(entity, routine.exercises.map { it.toEntity() })
    }

    /** Leaves a deletion record so sync removes the routine on other phones too. */
    suspend fun delete(id: Long) {
        dao.uuidOf(id)?.takeIf { it.isNotEmpty() }?.let { uuid ->
            tombstones.put(listOf(TombstoneEntity(TombstoneKind.ROUTINE, uuid, System.currentTimeMillis())))
        }
        dao.delete(id)
    }
}
