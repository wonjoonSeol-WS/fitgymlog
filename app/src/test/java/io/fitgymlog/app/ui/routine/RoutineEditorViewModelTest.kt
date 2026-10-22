package io.fitgymlog.app.ui.routine

import androidx.lifecycle.SavedStateHandle
import io.fitgymlog.app.model.NewExercise
import io.fitgymlog.app.model.Routine
import io.fitgymlog.app.model.RoutineExercise
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoutineEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val squat = RoutineExercise("squat_barbell", sets = 5, reps = 5, weightKg = null, restSeconds = 180)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeStore(routine: Routine?) : RoutineEditorStore {
        var routine: Routine? = routine
            set(value) {
                field = value
                present.value = value != null
            }
        private val present = MutableStateFlow(routine != null)
        val saved = mutableListOf<Routine>()
        var afterDelete: () -> Unit = {}

        override suspend fun find(id: Long) = routine?.takeIf { it.id == id }
        override fun exists(id: Long): Flow<Boolean> = present
        override suspend fun save(routine: Routine) {
            yield()
            check(routine.id == 0L || this.routine?.id == routine.id) { "Routine ${routine.id} no longer exists" }
            saved += routine
        }
        override suspend fun delete(id: Long) {
            routine = null
            yield()
            afterDelete()
        }
        override suspend fun createExercise(exercise: NewExercise) = "custom-1"
    }

    private fun TestScope.editor(id: Long, store: FakeStore, state: SavedStateHandle = SavedStateHandle()) =
        RoutineEditorViewModel(id, store, state).also { advanceUntilIdle() }

    @Test
    fun `a double tap on save saves once`() = runTest(dispatcher) {
        val store = FakeStore(null)
        val editor = editor(0, store)
        editor.edit { it.rename("Legs").add(squat) }

        editor.save()
        editor.save()
        advanceUntilIdle()

        assertEquals(1, store.saved.size)
        assertEquals(EditorPhase.DONE, editor.phase)
    }

    @Test
    fun `restores what was typed after the activity is recreated`() = runTest(dispatcher) {
        val state = SavedStateHandle()
        editor(0, FakeStore(null), state).edit { it.rename("Legs").add(squat).add(squat) }

        val recreated = editor(0, FakeStore(null), state)

        assertEquals("Legs", recreated.draft.name)
        assertEquals(2, recreated.draft.planned.size)
        assertTrue(recreated.draft.hasChanges)
    }

    @Test
    fun `keeps unsaved edits of an existing routine instead of reloading it`() = runTest(dispatcher) {
        val store = FakeStore(Routine(id = 7, name = "Legs", exercises = listOf(squat)))
        val state = SavedStateHandle()
        editor(7, store, state).edit { it.rename("Legs B") }

        val recreated = editor(7, store, state)

        assertEquals("Legs B", recreated.draft.name)
        assertEquals("Legs", recreated.draft.savedName)
    }

    @Test
    fun `reports a routine that was deleted before it opened`() = runTest(dispatcher) {
        assertEquals(EditorPhase.MISSING, editor(7, FakeStore(null)).phase)
    }

    @Test
    fun `reports a routine deleted elsewhere while editing and stops saving it`() = runTest(dispatcher) {
        val store = FakeStore(Routine(id = 7, name = "Legs", exercises = listOf(squat)))
        val editor = editor(7, store)
        assertEquals(EditorPhase.EDITING, editor.phase)

        store.routine = null
        advanceUntilIdle()
        editor.save()
        advanceUntilIdle()

        assertEquals(EditorPhase.MISSING, editor.phase)
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `a save that finds the routine already gone reports it missing`() = runTest(dispatcher) {
        val store = FakeStore(Routine(id = 7, name = "Legs", exercises = listOf(squat)))
        val editor = editor(7, store)

        store.routine = null
        editor.save()
        advanceUntilIdle()

        assertEquals(EditorPhase.MISSING, editor.phase)
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `saves the edits of a routine deleted elsewhere as a new routine`() = runTest(dispatcher) {
        val store = FakeStore(Routine(id = 7, name = "Legs", exercises = listOf(squat)))
        val editor = editor(7, store)
        editor.edit { it.copy(name = "Legs B") }

        store.routine = null
        advanceUntilIdle()
        editor.saveAsNew()
        advanceUntilIdle()

        assertEquals(EditorPhase.DONE, editor.phase)
        assertEquals(listOf(0L to "Legs B"), store.saved.map { it.id to it.name })
    }

    @Test
    fun `its own delete closes without reporting the routine missing`() = runTest(dispatcher) {
        val store = FakeStore(Routine(id = 7, name = "Legs", exercises = listOf(squat)))
        val editor = editor(7, store)
        val phasesWhileDeleting = mutableListOf<EditorPhase>()
        store.afterDelete = { phasesWhileDeleting += editor.phase }

        editor.delete()
        advanceUntilIdle()

        assertEquals(listOf(EditorPhase.BUSY), phasesWhileDeleting)
        assertEquals(EditorPhase.DONE, editor.phase)
    }
}
