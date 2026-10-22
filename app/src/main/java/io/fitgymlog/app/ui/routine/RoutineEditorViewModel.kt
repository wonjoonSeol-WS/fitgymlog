package io.fitgymlog.app.ui.routine

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.fitgymlog.app.catching
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.NewExercise
import io.fitgymlog.app.model.Routine
import io.fitgymlog.app.model.RoutineExercise
import io.fitgymlog.app.model.defaultRestSeconds
import io.fitgymlog.app.model.defaultSets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

interface RoutineEditorStore {
    suspend fun find(id: Long): Routine?
    fun exists(id: Long): Flow<Boolean>
    suspend fun save(routine: Routine)
    suspend fun delete(id: Long)
    suspend fun createExercise(exercise: NewExercise): String
}

enum class EditorPhase { LOADING, EDITING, BUSY, MISSING, DONE }

class RoutineEditorViewModel(
    private val routineId: Long,
    private val store: RoutineEditorStore,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val restored = savedState.get<String>(DRAFT_KEY)?.let(RoutineDraft::decode)

    var draft by mutableStateOf(restored ?: RoutineDraft())
        private set
    var phase by mutableStateOf(if (restored != null || routineId == NEW_ROUTINE) EditorPhase.EDITING else EditorPhase.LOADING)
        private set
    var failed by mutableStateOf(false)
        private set

    init {
        if (phase == EditorPhase.LOADING) {
            viewModelScope.launch {
                val routine = store.find(routineId)
                if (phase != EditorPhase.LOADING) return@launch
                if (routine == null) {
                    phase = EditorPhase.MISSING
                } else {
                    keep(RoutineDraft.of(routine))
                    phase = EditorPhase.EDITING
                }
            }
        }
        if (routineId != NEW_ROUTINE) {
            viewModelScope.launch {
                store.exists(routineId).collect { exists ->
                    if (!exists && (phase == EditorPhase.LOADING || phase == EditorPhase.EDITING)) phase = EditorPhase.MISSING
                }
            }
        }
    }

    fun edit(transform: (RoutineDraft) -> RoutineDraft) {
        if (phase == EditorPhase.EDITING) keep(transform(draft))
    }

    fun add(exerciseId: String, kind: ExerciseKind, restSeconds: Int) = edit {
        it.add(RoutineExercise(exerciseId, sets = kind.defaultSets(), reps = null, weightKg = null, restSeconds = kind.defaultRestSeconds(restSeconds)))
    }

    fun createAndAdd(exercise: NewExercise, restSeconds: Int) {
        viewModelScope.launch {
            val id = attempt { store.createExercise(exercise) } ?: return@launch
            add(id, exercise.kind, restSeconds)
        }
    }

    fun save() {
        if (phase != EditorPhase.EDITING || !draft.canSave) return
        val routine = draft.toRoutine(routineId)
        phase = EditorPhase.BUSY
        viewModelScope.launch {
            phase = when {
                attempt { store.save(routine) } != null -> EditorPhase.DONE
                routineId != NEW_ROUTINE && attempt { store.find(routineId) == null } == true -> EditorPhase.MISSING
                else -> EditorPhase.EDITING.also { failed = true }
            }
        }
    }

    fun saveAsNew() {
        if (phase != EditorPhase.MISSING || !draft.canSave) return
        val routine = draft.toRoutine(NEW_ROUTINE)
        phase = EditorPhase.BUSY
        viewModelScope.launch {
            phase = if (attempt { store.save(routine) } != null) EditorPhase.DONE else EditorPhase.MISSING.also { failed = true }
        }
    }

    fun delete() {
        if (phase != EditorPhase.EDITING || routineId == NEW_ROUTINE) return
        phase = EditorPhase.BUSY
        viewModelScope.launch {
            phase = if (attempt { store.delete(routineId) } != null) EditorPhase.DONE else EditorPhase.EDITING.also { failed = true }
        }
    }

    fun close() {
        if (phase != EditorPhase.BUSY) phase = EditorPhase.DONE
    }

    fun failureShown() {
        failed = false
    }

    private fun keep(next: RoutineDraft) {
        draft = next
        savedState[DRAFT_KEY] = next.encode()
    }

    private suspend fun <T : Any> attempt(block: suspend () -> T): T? = catching { block() }.getOrNull()

    private companion object {
        const val NEW_ROUTINE = 0L
        const val DRAFT_KEY = "routine_draft"
    }
}
