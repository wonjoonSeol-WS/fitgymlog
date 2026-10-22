package io.fitgymlog.app.ui.exercise

import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import java.text.Collator
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ExercisePickerTest {
    private fun exercise(name: String) = Exercise(id = name, englishName = name, kind = ExerciseKind.WEIGHT_REPS, segmentType = 0)

    @Test
    fun `sorts names the way the language does`() {
        val named = listOf("Zercher squat", "Écarté press", "apple pull").map { exercise(it) to it }

        assertEquals(
            listOf("apple pull", "Écarté press", "Zercher squat"),
            sortedByName(named, Collator.getInstance(Locale.ENGLISH)).map { it.second },
        )
    }

    @Test
    fun `matches the shown or the english name and ignores surrounding spaces`() {
        val squat = Exercise(id = "squat", englishName = "Squat (Barbell)", kind = ExerciseKind.WEIGHT_REPS, segmentType = 0)
        val bench = Exercise(id = "bench", englishName = "Bench Press", kind = ExerciseKind.WEIGHT_REPS, segmentType = 0)
        val named = listOf(squat to "바벨 스쿼트", bench to "벤치 프레스")

        assertEquals(listOf(squat), matching(named, " squat ").map { it.first })
        assertEquals(listOf(bench), matching(named, "벤치").map { it.first })
        assertEquals(named, matching(named, "  "))
    }
}
