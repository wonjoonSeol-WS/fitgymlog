package io.fitgymlog.app.coach

import androidx.health.connect.client.records.ExerciseSegment
import io.fitgymlog.app.model.Equipment
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.model.WorkoutSet
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachTest {
    private val today = LocalDate.parse("2026-10-22")
    private val done = Instant.parse("2026-10-19T10:00:00Z")
    private val squat = Exercise("squat_barbell", "Squat (Barbell)", ExerciseKind.WEIGHT_REPS, ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT, Equipment.BARBELL)
    private val bench = squat.copy(id = "bench_press_barbell")
    private val curl = squat.copy(id = "bicep_curl_dumbbell", equipment = Equipment.DUMBBELL)
    private val knee = Memory("m1", MemoryTopic.HEALTH, "Left knee sore", "2026-10-22")
    private val profile = Profile(bodyweightKg = 75.0, goal = Goal.BUILD, memories = listOf(knee), language = "ko")

    private fun fives(kg: Double, vararg reps: Int) = reps.map { WorkoutSet(weightKg = kg, reps = it, targetReps = 5, completedAt = done) }

    private val plan = listOf(
        Planned(squat, "스쿼트", listOf(PastSession(today.minusDays(3), fives(60.0, 5, 5, 5, 5, 5)))),
        Planned(bench, "벤치 프레스", listOf(PastSession(today.minusDays(3), fives(40.0, 5, 5, 5, 4, 3)))),
        Planned(curl, "덤벨 컬", emptyList()),
    )

    private class Recorder(private val reply: String) : Completer {
        val calls = mutableListOf<Pair<List<ChatMessage>, String>>()

        override suspend fun complete(messages: List<ChatMessage>, schemaName: String, schema: kotlinx.serialization.json.JsonObject): String {
            calls += messages to schemaName
            return reply
        }
    }

    @Test
    fun `targets the model overreaches on are held to the rule limits, and missing ones fall back to the rules`() = runTest {
        val model = Recorder("""{"targets":[{"id":"squat_barbell","weightKg":100,"reps":10,"note":"무릎 때문에 그대로"},{"id":"ghost","weightKg":5,"reps":5,"note":"x"}]}""")

        val targets = Coach.targets(model, profile, plan, WeightUnit.KG, today).associateBy { it.exerciseId }

        assertEquals(65.0, targets.getValue("squat_barbell").weightKg!!, 0.0)
        assertEquals(7, targets.getValue("squat_barbell").reps)
        assertEquals("무릎 때문에 그대로", targets.getValue("squat_barbell").note)
        assertEquals(40.0, targets.getValue("bench_press_barbell").weightKg!!, 0.0)
        assertNull(targets.getValue("bench_press_barbell").note)
        assertEquals(TargetReason.FIRST_TIME, targets.getValue("bicep_curl_dumbbell").reason)
        assertNull(targets.getValue("bicep_curl_dumbbell").weightKg)
    }

    @Test
    fun `a weight between steps is rounded to the step`() = runTest {
        val model = Recorder("""```json
            {"targets":[{"id":"squat_barbell","weightKg":61.7,"reps":5,"note":"ok"}]}
            ```""")

        assertEquals(62.5, Coach.targets(model, profile, plan, WeightUnit.KG, today).first().weightKg!!, 0.0)
    }

    @Test
    fun `the request carries the rule limits, the remembered notes and the app language, but not exercises with nothing to set`() = runTest {
        val model = Recorder("""{"targets":[]}""")

        Coach.targets(model, profile, plan, WeightUnit.KG, today)

        val (messages, schema) = model.calls.single()
        assertEquals("targets", schema)
        assertTrue(messages.first().content.contains("write in Korean"))
        val payload = Json.parseToJsonElement(messages.last().content).jsonObject
        assertEquals(listOf("health: Left knee sore"), payload.getValue("notes").jsonArray.map { it.jsonPrimitive.content })
        val exercises = payload.getValue("exercises").jsonArray.map { it.jsonObject }
        assertEquals(listOf("squat_barbell", "bench_press_barbell"), exercises.map { it.getValue("id").jsonPrimitive.content })
        assertEquals("47.5", exercises.first().getValue("rule").jsonObject.getValue("minKg").jsonPrimitive.content)
    }

    @Test
    fun `with nothing to set the model is not asked at all`() = runTest {
        val model = Recorder("not called")

        Coach.targets(model, profile, listOf(plan.last()), WeightUnit.KG, today)

        assertTrue(model.calls.isEmpty())
    }

    @Test
    fun `a reply that is not JSON is reported as a bad reply`() = runTest {
        val error = runCatching { Coach.targets(Recorder("Sorry, I can't help with that."), profile, plan, WeightUnit.KG, today) }.exceptionOrNull()

        assertEquals(CoachFailure.BAD_REPLY, (error as CoachException).failure)
    }

    @Test
    fun `the review's protein comes from bodyweight and goal, and only three meal ideas are kept`() = runTest {
        val model = Recorder("""{"notes":"좋았어요","foodSummary":"단백질을 챙기세요","mealIdeas":["닭가슴살 덮밥","두부 비빔밥","그릭 요거트","연어 샐러드"]}""")
        val workout = listOf(Done("스쿼트", fives(60.0, 5, 5, 5)))

        val review = Coach.review(model, profile, workout, emptyList(), WeightUnit.KG, today, "w-1", withFood = true)
        val noFood = Coach.review(model, profile, workout, emptyList(), WeightUnit.KG, today, "w-1", withFood = false)

        assertEquals(Food(135, 30, "단백질을 챙기세요", listOf("닭가슴살 덮밥", "두부 비빔밥", "그릭 요거트")), review.food)
        assertNull(noFood.food)
        assertTrue(model.calls.first().first.first().content.contains("mealIdeas"))
        assertTrue(!model.calls.last().first.first().content.contains("mealIdeas"))
    }

    @Test
    fun `protein follows the goal, and a meal stays between 20 and 50 grams`() {
        assertEquals(Protein(135, 30), Coach.proteinFor(75.0, Goal.BUILD))
        assertEquals(Protein(120, 30), Coach.proteinFor(75.0, Goal.KEEP))
        assertEquals(Protein(165, 30), Coach.proteinFor(75.0, Goal.LOSE))
        assertEquals(Protein(80, 20), Coach.proteinFor(45.0, Goal.BUILD))
        assertEquals(Protein(270, 50), Coach.proteinFor(150.0, Goal.BUILD))
    }

    @Test
    fun `chat changes are kept only for today's exercises and within limits, and offered memories keep their topic`() = runTest {
        val model = Recorder(
            """{"reply":"오늘은 가볍게","changes":[{"id":"squat_barbell","weightKg":20,"reps":5},{"id":"deadlift_barbell","weightKg":100,"reps":5}],
               "remember":[{"topic":"food","text":"유당불내증"},{"topic":"mood","text":"x"},{"topic":"HEALTH","text":"  "}]}""",
        )
        val todays = plan.take(1).let { it.zip(Coach.ruleTargets(it, WeightUnit.KG)) }

        val turn = Coach.chat(model, profile, emptyList(), "무릎이 아파요", todays, emptyList(), WeightUnit.KG, today)

        assertEquals("오늘은 가볍게", turn.text)
        assertEquals(listOf(Change("squat_barbell", 47.5, 5)), turn.changes)
        assertEquals(listOf(Remember(MemoryTopic.FOOD, "유당불내증")), turn.remember)
        assertEquals("무릎이 아파요", model.calls.single().first.last().content)
    }
}
