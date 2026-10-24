package io.fitgymlog.app.coach

import androidx.health.connect.client.records.ExerciseSegment
import io.fitgymlog.app.checkin.MissedReps
import io.fitgymlog.app.checkin.WeekRecord
import io.fitgymlog.app.checkin.WeekSummary
import io.fitgymlog.app.model.Equipment
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.RoutineExercise
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.ui.exercise.ProgressMetric
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachTest {
    private val today = LocalDate.parse("2026-10-22")
    private val done = Instant.parse("2026-10-19T10:00:00Z")
    private val squat = Exercise("squat_barbell", "Squat (Barbell)", ExerciseKind.WEIGHT_REPS, ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT, Equipment.BARBELL)
    private val bench = squat.copy(id = "bench_press_barbell")
    private val row = squat.copy(id = "bent_over_row_barbell")
    private val curl = squat.copy(id = "bicep_curl_dumbbell", equipment = Equipment.DUMBBELL)
    private val pushUp = squat.copy(id = "push_up", kind = ExerciseKind.BODYWEIGHT_REPS, equipment = Equipment.BODYWEIGHT)
    private val assistedPullUp = squat.copy(id = "pull_up_assisted", kind = ExerciseKind.ASSISTED_BODYWEIGHT, equipment = Equipment.MACHINE)
    private val hipThrust = squat.copy(id = "hip_thrust_barbell")
    private val gluteBridge = squat.copy(id = "glute_bridge", kind = ExerciseKind.BODYWEIGHT_REPS, equipment = Equipment.BODYWEIGHT, customName = "hip thrust")
    private val treadmill = squat.copy(id = "treadmill", kind = ExerciseKind.CARDIO, equipment = Equipment.MACHINE)
    private val knee = Memory("health/left-knee", "Left knee sore", "2026-10-22", updatedAt = 1)
    private val profile = Profile(bodyweightKg = 75.0, goal = Goal.BUILD, memories = listOf(knee), shareNotes = true, language = "ko")

    private fun sets(kg: Double?, target: Int, vararg reps: Int) = reps.map { WorkoutSet(weightKg = kg, reps = it, targetReps = target, completedAt = done) }

    private fun fives(kg: Double, vararg reps: Int) = sets(kg, 5, *reps)

    private fun planned(exercise: Exercise, name: String, sets: List<WorkoutSet>) = Planned(exercise, name, listOf(PastSession(today.minusDays(3), sets)))

    private val plan = listOf(
        planned(squat, "스쿼트", fives(60.0, 5, 5, 5, 5, 5)),
        planned(bench, "벤치 프레스", fives(40.0, 5, 5, 5, 4, 3)),
        Planned(curl, "덤벨 컬", emptyList()),
    )

    private class Recorder(private val reply: String) : Completer {
        val calls = mutableListOf<Pair<List<ChatMessage>, String>>()

        override suspend fun complete(messages: List<ChatMessage>, schemaName: String, schema: JsonObject): String {
            calls += messages to schemaName
            return reply
        }
    }

    private fun payloadOf(model: Recorder) = Json.parseToJsonElement(model.calls.single().first[1].content).jsonObject

    private fun contextOf(model: Recorder) = Json.parseToJsonElement(model.calls.single().first.first().content.substringAfter("\n\nData: ")).jsonObject

    private suspend fun failureOf(block: suspend () -> Unit): CoachFailure? = (runCatching { block() }.exceptionOrNull() as? CoachException)?.failure

    @Test
    fun `targets the model overreaches on are held to the rule limits, and unknown ids are ignored`() = runTest {
        val model = Recorder(
            """{"targets":[{"id":"squat_barbell","weightKg":100,"reps":10,"note":"무릎 때문에 그대로"},{"id":"ghost","weightKg":5,"reps":5,"note":"x"},{"id":"bench_press_barbell","weightKg":null,"reps":1,"note":"x"}]}""",
        )

        val targets = Coach.targets(model, profile, plan, WeightUnit.KG, today).associateBy { it.exerciseId }

        assertEquals(setOf("squat_barbell", "bench_press_barbell", "bicep_curl_dumbbell"), targets.keys)
        assertEquals(65.0, targets.getValue("squat_barbell").weightKg!!, 0.0)
        assertEquals(7, targets.getValue("squat_barbell").reps)
        assertEquals(40.0, targets.getValue("bench_press_barbell").weightKg!!, 0.0)
        assertEquals(3, targets.getValue("bench_press_barbell").reps)
        assertEquals("무릎 때문에 그대로", targets.getValue("squat_barbell").note)
        assertEquals(TargetReason.FIRST_TIME, targets.getValue("bicep_curl_dumbbell").reason)
        assertNull(targets.getValue("bicep_curl_dumbbell").weightKg)
    }

    @Test
    fun `an exercise the model leaves out keeps the rule target, not last time's weight`() = runTest {
        val withRow = plan + planned(row, "바벨 로우", fives(60.0, 5, 5, 5, 5, 5))

        val targets = Coach.targets(Recorder("""{"targets":[]}"""), profile, withRow, WeightUnit.KG, today).associateBy { it.exerciseId }

        assertEquals(62.5, targets.getValue("bent_over_row_barbell").weightKg!!, 0.0)
        assertEquals(60.0, targets.getValue("bent_over_row_barbell").lastWeightKg!!, 0.0)
        assertEquals(5, targets.getValue("bent_over_row_barbell").reps)
        assertNull(targets.getValue("bent_over_row_barbell").note)
    }

    @Test
    fun `when the model lists an exercise twice its last entry counts`() = runTest {
        val model = Recorder("""{"targets":[{"id":"squat_barbell","weightKg":60,"reps":5,"note":"first"},{"id":"squat_barbell","weightKg":65,"reps":5,"note":"second"}]}""")

        val squatTarget = Coach.targets(model, profile, plan, WeightUnit.KG, today).first()

        assertEquals(65.0, squatTarget.weightKg!!, 0.0)
        assertEquals("second", squatTarget.note)
    }

    @Test
    fun `a weight between steps is moved onto the step grid`() = runTest {
        val model = Recorder("""```json
            {"targets":[{"id":"squat_barbell","weightKg":64.1,"reps":5,"note":"ok"}]}
            ```""")

        assertEquals(65.0, Coach.targets(model, profile, plan, WeightUnit.KG, today).first().weightKg!!, 0.0)
    }

    @Test
    fun `the grid counts from the last weight, so microplate weights stay put and never pass the maximum`() = runTest {
        val missed = listOf(planned(squat, "스쿼트", fives(61.25, 5, 5, 4)))
        val passed = listOf(planned(squat, "스쿼트", fives(61.25, 5, 5, 5)))
        fun reply(kg: Double) = Recorder("""{"targets":[{"id":"squat_barbell","weightKg":$kg,"reps":5,"note":"ok"}]}""")

        assertEquals(61.25, Coach.targets(Recorder("""{"targets":[]}"""), profile, missed, WeightUnit.KG, today).single().weightKg!!, 0.0)
        assertEquals(63.75, Coach.targets(Recorder("""{"targets":[]}"""), profile, passed, WeightUnit.KG, today).single().weightKg!!, 0.0)
        assertEquals(63.75, Coach.targets(reply(64.0), profile, passed, WeightUnit.KG, today).single().weightKg!!, 0.0)
        assertEquals(66.25, Coach.targets(reply(66.25), profile, passed, WeightUnit.KG, today).single().weightKg!!, 0.0)
        assertEquals(66.25, Coach.targets(reply(67.5), profile, passed, WeightUnit.KG, today).single().weightKg!!, 0.0)
        assertEquals(63.75, Coach.ruleOnly(passed, WeightUnit.KG).single().weightKg!!, 0.0)
    }

    @Test
    fun `the rule reads the routine's planned reps, so a curl done at twelve climbs from its plan of ten instead of repeating`() {
        val curls = listOf(PastSession(today.minusDays(3), sets(12.0, 12, 12, 12, 12)))

        val routine = Coach.ruleOnly(listOf(Planned(curl, "덤벨 컬", curls, plannedReps = 10)), WeightUnit.KG).single()
        val loose = Coach.ruleOnly(listOf(Planned(curl, "덤벨 컬", curls)), WeightUnit.KG).single()

        assertEquals(TargetReason.MORE_REPS, routine.reason)
        assertEquals(13, routine.reps)
        assertEquals(TargetReason.SAME_AS_LAST, loose.reason)
        assertEquals(12, loose.reps)
    }

    @Test
    fun `reps past thirty stay near the rule instead of failing the whole call`() = runTest {
        val highReps = listOf(planned(pushUp, "푸시업", sets(null, 40, 40, 40)))
        val model = Recorder("""{"targets":[{"id":"push_up","weightKg":null,"reps":50,"note":"ok"}]}""")

        val target = Coach.targets(model, profile, highReps, WeightUnit.KG, today).single()

        assertEquals(41, target.reps)
        assertNull(target.weightKg)
    }

    @Test
    fun `the request carries the rule limits, the reply shape, the notes and the app language, but not exercises with nothing to set`() = runTest {
        val model = Recorder("""{"targets":[]}""")

        Coach.targets(model, profile, plan, WeightUnit.KG, today)

        val (messages, schema) = model.calls.single()
        assertEquals("targets", schema)
        assertTrue(messages.first().content.contains("write in Korean, in polite 해요체"))
        assertTrue(messages.first().content.contains("""{"targets":[{"id":string,"weightKg":number|null,"reps":integer|null,"note":string}]}"""))
        val payload = payloadOf(model)
        assertEquals(listOf("health/left-knee (saved 2026-10-22): Left knee sore"), payload.getValue("notes").jsonArray.map { it.jsonPrimitive.content })
        val exercises = payload.getValue("exercises").jsonArray.map { it.jsonObject }
        assertEquals(listOf("squat_barbell", "bench_press_barbell"), exercises.map { it.getValue("id").jsonPrimitive.content })
        val rule = exercises.first().getValue("rule").jsonObject
        assertEquals(
            listOf("62.5", "5", "3", "7", "47.5", "65.0", "2.5", "60.0"),
            listOf("weightKg", "reps", "minReps", "maxReps", "minKg", "maxKg", "stepKg", "lastKg").map { rule.getValue(it).jsonPrimitive.content },
        )
        Coach.targets(model, profile.copy(language = "en"), plan, WeightUnit.KG, today)
        assertTrue(model.calls.last().first.first().content.contains("write in English"))
    }

    @Test
    fun `pounds, assistance, added weight and set types are written so a model can read them`() = runTest {
        val model = Recorder("""{"targets":[]}""")
        val imported = WorkoutSet(weightKg = 30.0, reps = 8)
        val dip = squat.copy(id = "chest_dip_weighted", kind = ExerciseKind.WEIGHTED_BODYWEIGHT)
        val warmUp = WorkoutSet(type = SetType.WARMUP, weightKg = 20.0, reps = 5)
        val pounds = listOf(
            planned(squat, "스쿼트", listOf(warmUp) + fives(135 * 0.45359237, 5)),
            planned(assistedPullUp, "어시스트 풀업", listOf(imported)),
            planned(dip, "웨이티드 딥스", sets(10.0, 8, 8)),
        )

        Coach.targets(model, profile, pounds, WeightUnit.LB, today)

        val payload = payloadOf(model)
        val recent = payload.getValue("exercises").jsonArray.map { it.jsonObject.getValue("recent").jsonArray.single().jsonObject.getValue("sets").jsonPrimitive.content }
        assertEquals("lb", payload.getValue("unit").jsonPrimitive.content)
        assertEquals(listOf("20kg 5 reps warmup, 61.23kg 5 reps", "30kg assistance 8 reps", "10kg added 8 reps"), recent)
    }

    @Test
    fun `the preview is the request today's targets send, and empty when nothing would be sent`() = runTest {
        val model = Recorder("""{"targets":[]}""")

        Coach.targets(model, profile, plan, WeightUnit.KG, today)
        val preview = Json.parseToJsonElement(Coach.preview(profile, plan, WeightUnit.KG, today)!!).jsonArray

        assertEquals(model.calls.single().first.map { it.role to it.content }, preview.map { it.jsonObject.getValue("role").jsonPrimitive.content to it.jsonObject.getValue("content").jsonPrimitive.content })
        assertNull(Coach.preview(profile, listOf(plan.last()), WeightUnit.KG, today))
    }

    @Test
    fun `with nothing to set the model is not asked at all`() = runTest {
        val model = Recorder("not called")

        Coach.targets(model, profile, listOf(plan.last()), WeightUnit.KG, today)

        assertTrue(model.calls.isEmpty())
    }

    @Test
    fun `a reply that is not JSON, or says nothing, is reported as a bad reply`() = runTest {
        val workout = PastWorkout(today, listOf(Done("스쿼트", ExerciseKind.WEIGHT_REPS, fives(60.0, 5))))

        assertEquals(CoachFailure.BAD_REPLY, failureOf { Coach.targets(Recorder("Sorry, I can't help with that."), profile, plan, WeightUnit.KG, today) })
        assertEquals(CoachFailure.BAD_REPLY, failureOf { Coach.review(Recorder("""{"answer":"Good"}"""), profile, workout, emptyList(), WeightUnit.KG, "w-1", withFood = false) })
        assertEquals(CoachFailure.BAD_REPLY, failureOf { Coach.chat(Recorder("""{"answer":"Do 3x5"}"""), profile, emptyList(), "hi", emptyList(), emptyList(), WeightUnit.KG, today, earlier = "") })
        assertEquals(CoachFailure.BAD_REPLY, failureOf { Coach.summarize(Recorder("""{"summary":"  "}"""), profile, "before", emptyList()) { it } })
    }

    @Test
    fun `the review sends the workout with its rests, recent workouts and today's protein`() = runTest {
        val model = Recorder("""{"notes":"ok","foodSummary":"eat","mealIdeas":[]}""")
        val timed = listOf(WorkoutSet(weightKg = 60.0, reps = 5, completedAt = done), WorkoutSet(weightKg = 60.0, reps = 5, completedAt = done.plusSeconds(150)))
        val workout = PastWorkout(today, listOf(Done("스쿼트", ExerciseKind.WEIGHT_REPS, timed)))
        val earlier = PastWorkout(today.minusDays(3), listOf(Done("벤치", ExerciseKind.WEIGHT_REPS, fives(40.0, 5))))

        Coach.review(model, profile, workout, listOf(earlier), WeightUnit.KG, "w-1", withFood = true)

        val payload = payloadOf(model)
        assertEquals("2:30", payload.getValue("workout").jsonArray.single().jsonObject.getValue("restsBetweenSets").jsonPrimitive.content)
        assertEquals("2026-10-19", payload.getValue("recentWorkouts").jsonArray.single().jsonObject.getValue("date").jsonPrimitive.content)
        assertEquals("135", payload.getValue("proteinGramsToday").jsonPrimitive.content)
    }

    @Test
    fun `the review's protein comes from bodyweight and goal, and only three non-blank meal ideas are kept`() = runTest {
        val model = Recorder("""{"notes":"좋았어요","foodSummary":"단백질을 챙기세요","mealIdeas":["닭가슴살 덮밥"," ","두부 비빔밥","그릭 요거트","연어 샐러드"]}""")
        val workout = PastWorkout(today, listOf(Done("스쿼트", ExerciseKind.WEIGHT_REPS, fives(60.0, 5, 5, 5))))

        val review = Coach.review(model, profile, workout, emptyList(), WeightUnit.KG, "w-1", withFood = true)
        val noFood = Coach.review(model, profile, workout, emptyList(), WeightUnit.KG, "w-1", withFood = false)
        val noWeight = Coach.review(model, profile.copy(bodyweightKg = null), workout, emptyList(), WeightUnit.KG, "w-1", withFood = true)

        assertEquals(Food(135, 30, "단백질을 챙기세요", listOf("닭가슴살 덮밥", "두부 비빔밥", "그릭 요거트")), review.food)
        assertNull(noFood.food)
        assertNull(noWeight.food)
        assertTrue(model.calls[0].first.first().content.contains("mealIdeas"))
        assertFalse(model.calls[1].first.first().content.contains("mealIdeas"))
    }

    @Test
    fun `protein follows the goal, and a meal stays between 20 and 50 grams`() {
        assertEquals(Protein(135, 30), Coach.proteinFor(75.0, Goal.BUILD))
        assertEquals(Protein(120, 30), Coach.proteinFor(75.0, Goal.KEEP))
        assertEquals(Protein(165, 30), Coach.proteinFor(75.0, Goal.LOSE))
        assertEquals(Protein(165, 30), Coach.proteinFor(75.0, Goal.RECOMP))
        assertEquals(Protein(80, 20), Coach.proteinFor(45.0, Goal.BUILD))
        assertEquals(Protein(270, 50), Coach.proteinFor(150.0, Goal.BUILD))
        assertEquals(Protein(125, 25), Coach.proteinFor(68.5, Goal.BUILD))
    }

    @Test
    fun `calories come from Mifflin-St Jeor, workouts a week and the goal, a tenth more to build, half a percent of bodyweight a week to lose, a quarter to recomp`() {
        assertEquals(Calories(2720, 2720), Coach.caloriesFor(80.0, 180.0, null, Sex.MALE, 35, 3.0, Goal.KEEP))
        assertEquals(Calories(2720, 2280), Coach.caloriesFor(80.0, 180.0, null, Sex.MALE, 35, 3.0, Goal.LOSE))
        assertEquals(Calories(2720, 2990), Coach.caloriesFor(80.0, 180.0, null, Sex.MALE, 35, 3.0, Goal.BUILD))
        assertEquals(Calories(2720, 2500), Coach.caloriesFor(80.0, 180.0, null, Sex.MALE, 35, 3.0, Goal.RECOMP))
        assertEquals(Calories(1920, 1620), Coach.caloriesFor(55.0, 160.0, null, Sex.FEMALE, 30, 3.0, Goal.LOSE))
        val byWorkouts = listOf(0.75, 1.0, 2.75, 3.0, 5.75, 6.0).map { Coach.caloriesFor(80.0, 180.0, null, Sex.MALE, 35, it, Goal.KEEP)?.maintenance }
        assertEquals(listOf(2110, 2410, 2410, 2720, 2720, 3030), byWorkouts)
    }

    @Test
    fun `with lean mass from a body fat reading, resting needs come from it as InBody works out BMR, and height is not needed`() {
        assertEquals(Calories(2930, 2930, fromLeanMass = true), Coach.caloriesFor(80.0, 180.0, 70.4, Sex.MALE, 35, 3.0, Goal.KEEP))
        assertEquals(Calories(2930, 2930, fromLeanMass = true), Coach.caloriesFor(80.0, null, 70.4, Sex.MALE, 35, 3.0, Goal.KEEP))
        assertEquals(Calories(1950, 1650, fromLeanMass = true), Coach.caloriesFor(55.0, 160.0, 41.25, Sex.FEMALE, 30, 3.0, Goal.LOSE))
        assertNull(Coach.caloriesFor(80.0, null, null, Sex.MALE, 35, 3.0, Goal.KEEP))
        assertNull(Coach.caloriesFor(80.0, null, 70.4, Sex.MALE, 17, 3.0, Goal.KEEP))
    }

    @Test
    fun `losing plans half a percent of bodyweight a week, recomp a quarter, and building or keeping plans no loss`() {
        assertEquals(-0.4, Coach.plannedWeeklyChangeKg(80.0, Goal.LOSE)!!, 1e-9)
        assertEquals(-0.2, Coach.plannedWeeklyChangeKg(80.0, Goal.RECOMP)!!, 1e-9)
        assertNull(Coach.plannedWeeklyChangeKg(80.0, Goal.BUILD))
        assertNull(Coach.plannedWeeklyChangeKg(80.0, Goal.KEEP))
    }

    @Test
    fun `the calorie target stays at or above 1200, or 1500 for men, and is not worked out under 18`() {
        assertEquals(Calories(1110, 1200), Coach.caloriesFor(45.0, 150.0, null, Sex.FEMALE, 60, 0.0, Goal.LOSE))
        assertEquals(Calories(1310, 1500), Coach.caloriesFor(45.0, 150.0, null, Sex.MALE, 60, 0.0, Goal.LOSE))
        assertNull(Coach.caloriesFor(60.0, 170.0, null, Sex.MALE, 17, 3.0, Goal.KEEP))
        assertEquals(Calories(2450, 2450), Coach.caloriesFor(60.0, 170.0, null, Sex.MALE, 18, 3.0, Goal.KEEP))
    }

    @Test
    fun `the app's calorie target and sex go to the model, and the review's food shows the target`() = runTest {
        val model = Recorder("""{"notes":"ok","foodSummary":"eat","mealIdeas":[]}""")
        val workout = PastWorkout(today, listOf(Done("스쿼트", ExerciseKind.WEIGHT_REPS, fives(60.0, 5, 5, 5))))
        val known = profile.copy(sex = Sex.FEMALE, calories = Calories(maintenance = 2100, target = 1800))

        val review = Coach.review(model, known, workout, emptyList(), WeightUnit.KG, "w-1", withFood = true)

        val payload = payloadOf(model)
        assertEquals(listOf("female", "2100", "1800"), listOf("sex", "maintenanceKcal", "targetKcal").map { payload.getValue(it).jsonPrimitive.content })
        assertEquals(1800, review.food?.kcal)
        assertTrue(model.calls.single().first.first().content.contains("targetKcal"))
    }

    @Test
    fun `a picture's numbers are taken as printed, pounds become kilograms, and misreads or a future date are dropped`() {
        assertEquals(
            BodyMeasurement("2026-10-20", 79.6, 18.2, 35.4, source = MeasurementSource.PHOTO),
            Coach.bodyOf(Coach.BodyReply("2026-10-20", 79.6, 18.2, 35.4, "kg"), today),
        )
        assertEquals(
            BodyMeasurement("2026-10-22", 79.8, null, 35.4, source = MeasurementSource.PHOTO),
            Coach.bodyOf(Coach.BodyReply("2026-12-01", 176.0, 0.5, 78.0, "lb"), today),
        )
        assertEquals("2026-10-22", Coach.bodyOf(Coach.BodyReply("not a date", 80.0), today)?.date)
        assertEquals(79.8, Coach.bodyOf(Coach.BodyReply("2026-10-20", 176.0, massUnit = "lbs"), today)?.weightKg!!, 0.0)
        assertNull(Coach.bodyOf(Coach.BodyReply(weight = 800.0, bodyFatPercent = 90.0), today))
        assertEquals(8, Coach.bodyOf(Coach.BodyReply("2026-10-20", visceralFatLevel = 7.6), today)?.visceralFatLevel)
        assertNull(Coach.bodyOf(Coach.BodyReply("2026-10-20", visceralFatLevel = 95.0), today))
    }

    @Test
    fun `reading a picture sends it with the text, and a picture with nothing usable reads as nothing`() = runTest {
        val model = Recorder("""{"date":null,"weight":null,"bodyFatPercent":null,"skeletalMuscle":null,"massUnit":null}""")

        val read = Coach.readBody(model, profile, "data:image/jpeg;base64,AAAA", today)

        assertNull(read)
        assertEquals("data:image/jpeg;base64,AAAA", model.calls.single().first.last().imageUrl)
        assertEquals("body", model.calls.single().second)
    }

    private val week = WeekSummary(
        start = LocalDate.parse("2026-10-19"),
        workouts = 3 to 2,
        minutes = 180L to 150L,
        volumeKg = 12_040.4 to 11_000.0,
        records = listOf(WeekRecord("squat_barbell", listOf(ProgressMetric.HEAVIEST))),
        missed = listOf(MissedReps("bench_press_barbell", missed = 2, sets = 15)),
        weightTrendKg = 78.24,
        weightChangeKg = -0.36,
    )

    private val routines = listOf(
        RoutinePlan(4, "Day A", listOf(NamedExercise(squat, "스쿼트") to RoutineExercise("squat_barbell", 5, 5, null, 180), NamedExercise(bench, "벤치 프레스") to RoutineExercise("bench_press_barbell", 5, 5, null, 180))),
        RoutinePlan(5, "Cardio", listOf(NamedExercise(treadmill, "트레드밀") to RoutineExercise("treadmill", 1, null, null, 0, durationSeconds = 1_200))),
    )

    private suspend fun lookBack(model: Recorder, inApp: List<NamedExercise> = emptyList()) =
        Coach.week(model, profile, week, mapOf("squat_barbell" to "스쿼트", "bench_press_barbell" to "벤치 프레스")::getValue, emptyList(), routines, inApp, WeightUnit.KG, today)

    @Test
    fun `the week's look back sends the week's numbers and the routines with their ids`() = runTest {
        val model = Recorder("""{"reply":"좋은 한 주였어요","changes":[]}""")

        val review = lookBack(model)

        val payload = payloadOf(model)
        val sent = payload.getValue("week").jsonObject
        assertEquals("week", model.calls.single().second)
        assertEquals(WeekReview("2026-10-19", "좋은 한 주였어요"), review)
        assertEquals(
            listOf("2026-10-19", "2026-10-25", "3", "2", "180", "150", "12040", "11000", "78.2", "-0.4"),
            listOf("from", "to", "workouts", "workoutsLastWeek", "minutes", "minutesLastWeek", "volumeKg", "volumeKgLastWeek", "weightTrendKg", "weightChangeKg").map { sent.getValue(it).jsonPrimitive.content },
        )
        assertEquals(listOf("스쿼트: heaviest"), sent.getValue("newRecords").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("벤치 프레스: 2 of 15 sets short of their target"), sent.getValue("missedReps").jsonArray.map { it.jsonPrimitive.content })
        val sentRoutines = payload.getValue("routines").jsonArray.map { it.jsonObject }
        assertEquals(listOf("4", "5"), sentRoutines.map { it.getValue("id").jsonPrimitive.content })
        assertEquals("20", sentRoutines[1].getValue("exercises").jsonArray.single().jsonObject.getValue("minutes").jsonPrimitive.content)
        assertEquals(CoachFailure.BAD_REPLY, failureOf { lookBack(Recorder("""{"reply":" ","changes":[]}""")) })
    }

    @Test
    fun `the week's routine changes keep to known routines and their exercises, within limits, once each, and only for actions it knows`() = runTest {
        val model = Recorder(
            """{"reply":"다음 주는 벤치를 줄여요","changes":[
                {"routine":4,"action":"adjust","id":"squat_barbell","sets":0,"reps":40,"minutes":20},
                {"routine":4,"action":"adjust","id":"bench_press_barbell","sets":12,"reps":null,"minutes":null},
                {"routine":4,"action":"adjust","id":"bicep_curl_dumbbell","sets":3},
                {"routine":9,"action":"remove","id":"squat_barbell"},
                {"routine":4,"action":"add","name":" hip THRUST ","sets":3,"reps":40},
                {"routine":4,"action":"add","name":"스쿼트","sets":3,"reps":5},
                {"routine":5,"action":"add","name":"Treadmill","sets":1,"reps":10,"minutes":30},
                {"routine":5,"action":"remove","id":"treadmill"},
                {"routine":4,"action":"swap","id":"bench_press_barbell","sets":2},
                {"routine":4,"action":"adjust","id":"squat_barbell","reps":6}]}""",
        )
        val inApp = listOf(NamedExercise(squat, "스쿼트"), NamedExercise(hipThrust, "Hip Thrust"), NamedExercise(treadmill, "Treadmill"))

        val review = lookBack(model, inApp)

        assertEquals(
            listOf(
                RoutineChange(4, "squat_barbell", reps = 6),
                RoutineChange(4, "bench_press_barbell", sets = 10),
                RoutineChange(4, "hip_thrust_barbell", ChangeAction.ADD, sets = 3, reps = 30),
                RoutineChange(5, "treadmill", ChangeAction.REMOVE),
            ),
            review.changes,
        )
    }

    @Test
    fun `chat sends the recent body measurements so the coach can see a trend`() = runTest {
        val model = Recorder("""{"reply":"ok","changes":[],"remember":[]}""")
        val body = listOf(BodyMeasurement("2026-10-01", weightKg = 81.0), BodyMeasurement("2026-10-20", weightKg = 79.5, bodyFatPercent = 18.0, skeletalMuscleKg = 35.0, visceralFatLevel = 7))

        Coach.chat(model, profile.copy(body = body), emptyList(), "hi", emptyList(), emptyList(), WeightUnit.KG, today, earlier = "")

        val sent = contextOf(model).getValue("bodyMeasurements").jsonArray.map { it.jsonObject }
        assertEquals(listOf("2026-10-01", "2026-10-20"), sent.map { it.getValue("date").jsonPrimitive.content })
        assertEquals(listOf("weightKg"), sent.first().keys.filter { it != "date" })
        assertEquals("35.0", sent.last().getValue("skeletalMuscleKg").jsonPrimitive.content)
        assertEquals("7", sent.last().getValue("visceralFatLevel").jsonPrimitive.content)
    }

    @Test
    fun `chat knows the app's exercises and how to find things, while targets and reviews are not told`() = runTest {
        val model = Recorder("""{"reply":"ok","changes":[],"remember":[]}""")

        val inApp = listOf(NamedExercise(squat, "스쿼트"), NamedExercise(hipThrust, "Hip Thrust"))

        Coach.chat(model, profile, emptyList(), "what should I add?", emptyList(), emptyList(), WeightUnit.KG, today, earlier = "", exercisesInApp = inApp)

        assertEquals(listOf("스쿼트", "Hip Thrust"), contextOf(model).getValue("exercisesInApp").jsonArray.map { it.jsonPrimitive.content })
        assertTrue(model.calls.single().first.first().content.contains("New exercise"))
        assertTrue(model.calls.single().first.first().content.contains("\"action\":\"adjust\"|\"add\"|\"remove\""))
    }

    @Test
    fun `chat adds exercises from the app's list by name, but not one already in today's workout or one the app does not have`() = runTest {
        val model = Recorder(
            """{"reply":"추가해요","changes":[
                {"action":"add","name":" hip THRUST ","sets":12,"reps":40},
                {"action":"add","name":"Treadmill","sets":1,"reps":10,"minutes":400},
                {"action":"add","name":"Nordic Curl","sets":3,"reps":5},
                {"action":"add","name":"스쿼트","sets":3,"reps":5}],"remember":[]}""",
        )
        val inApp = listOf(NamedExercise(squat, "스쿼트"), NamedExercise(hipThrust, "Hip Thrust"), NamedExercise(gluteBridge, "hip thrust"), NamedExercise(treadmill, "Treadmill"))

        val turn = Coach.chat(model, profile, emptyList(), "뭘 더 할까요?", plan, emptyList(), WeightUnit.KG, today, earlier = "", exercisesInApp = inApp)

        assertEquals(
            listOf(
                Change("hip_thrust_barbell", action = ChangeAction.ADD, sets = 10, reps = 30),
                Change("treadmill", action = ChangeAction.ADD, sets = 1, minutes = 180),
            ),
            turn.changes,
        )
    }

    @Test
    fun `chat removes or resizes only today's exercises, ignores fewer than one set and actions it does not know, caps sets at 10, and minutes are for cardio only`() = runTest {
        val model = Recorder(
            """{"reply":"조정했어요","changes":[
                {"action":"remove","id":"bench_press_barbell"},
                {"action":"replace","id":"bench_press_barbell","weightKg":50},
                {"action":"remove","id":"deadlift_barbell"},
                {"action":"adjust","id":"bicep_curl_dumbbell","sets":0,"minutes":20},
                {"action":"adjust","id":"treadmill","sets":1,"minutes":45},
                {"action":"adjust","id":"squat_barbell","sets":15}],"remember":[]}""",
        )
        val withCardio = plan + Planned(treadmill, "트레드밀", emptyList())

        val turn = Coach.chat(model, profile, emptyList(), "시간이 없어요", withCardio, emptyList(), WeightUnit.KG, today, earlier = "")

        assertEquals(
            listOf(
                Change("bench_press_barbell", action = ChangeAction.REMOVE),
                Change("treadmill", sets = 1, minutes = 45),
                Change("squat_barbell", sets = 10),
            ),
            turn.changes,
        )
    }

    @Test
    fun `chat sends the sets done so far today and the changes the coach offered before`() = runTest {
        val model = Recorder("""{"reply":"ok","changes":[],"remember":[]}""")
        val ran = WorkoutSet(durationSeconds = 600, level = 5, speedKmh = 8.5, completedAt = Instant.parse("2026-10-22T10:00:00Z"))
        val running = plan + Planned(treadmill, "트레드밀", emptyList(), today = listOf(ran, WorkoutSet(durationSeconds = 600)))
        val offered = ChatTurn(
            fromUser = false,
            text = "",
            at = 2,
            changes = listOf(Change("squat_barbell", 55.0, 5, applied = true, restSeconds = 180), Change("bench_press_barbell", action = ChangeAction.REMOVE)),
        )

        Coach.chat(model, profile, listOf(ChatTurn(true, "무릎이 아파요", at = 1), offered), "고마워요", running, emptyList(), WeightUnit.KG, today, earlier = "")

        val treadmillToday = contextOf(model).getValue("todaysWorkout").jsonArray.map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == "treadmill" }
        assertEquals("600s 8.5km/h level 5 done, 600s", treadmillToday.getValue("today").jsonPrimitive.content)
        assertEquals(
            "Offered workout changes: adjust 스쿼트 55kg 5 reps rest 180s (applied); remove 벤치 프레스",
            model.calls.single().first[2].content,
        )
    }

    @Test
    fun `chat sends the planned rest, the rest taken between each exercise's own sets, how long the workout has run and the rest left`() = runTest {
        val resting = Recorder("""{"reply":"ok","changes":[],"remember":[]}""")
        val lifting = Recorder("""{"reply":"ok","changes":[],"remember":[]}""")
        val at = { time: String -> Instant.parse("2026-10-22T$time" + "Z") }
        val squatToday = listOf(at("10:00:00"), at("10:02:30"), at("10:06:00")).map { WorkoutSet(weightKg = 60.0, reps = 5, completedAt = it) } + WorkoutSet(weightKg = 60.0, reps = 5)
        val supersetted = listOf(
            Planned(squat, "스쿼트", emptyList(), today = squatToday, restSeconds = 150),
            Planned(bench, "벤치 프레스", emptyList(), today = listOf(WorkoutSet(weightKg = 40.0, reps = 5, completedAt = at("10:01:00")))),
        )

        Coach.chat(resting, profile, emptyList(), "얼마나 쉬어요?", supersetted, emptyList(), WeightUnit.KG, today, earlier = "", inProgress = InProgress(minutes = 42, restLeftSeconds = 75))
        Coach.chat(lifting, profile, emptyList(), "얼마나 쉬어요?", supersetted, emptyList(), WeightUnit.KG, today, earlier = "", inProgress = InProgress(minutes = 43))

        val context = contextOf(resting)
        val (squatJson, benchJson) = context.getValue("todaysWorkout").jsonArray.map { it.jsonObject }
        assertEquals(150, squatJson.getValue("restSeconds").jsonPrimitive.content.toInt())
        assertEquals("2:30, 3:30", squatJson.getValue("restsBetweenSets").jsonPrimitive.content)
        assertFalse("restSeconds" in benchJson || "restsBetweenSets" in benchJson)
        assertEquals(42, context.getValue("minutesIntoWorkout").jsonPrimitive.content.toInt())
        assertEquals(75, context.getValue("restSecondsLeft").jsonPrimitive.content.toInt())
        assertFalse("restSecondsLeft" in contextOf(lifting))
    }

    @Test
    fun `rest changes snap to the rest timer's choices, are for lifts only, and a rest alone is a change`() = runTest {
        val model = Recorder(
            """{"reply":"쉬어요","changes":[
                {"action":"adjust","id":"squat_barbell","restSeconds":200},
                {"action":"adjust","id":"bench_press_barbell","restSeconds":1000},
                {"action":"adjust","id":"bicep_curl_dumbbell","restSeconds":-5},
                {"action":"adjust","id":"treadmill","restSeconds":60},
                {"action":"add","name":"Hip Thrust","sets":3,"reps":8,"restSeconds":100}],"remember":[]}""",
        )
        val withCardio = plan + Planned(treadmill, "트레드밀", emptyList())

        val turn = Coach.chat(model, profile, emptyList(), "더 쉬고 싶어요", withCardio, emptyList(), WeightUnit.KG, today, earlier = "", exercisesInApp = listOf(NamedExercise(hipThrust, "Hip Thrust")))

        assertEquals(
            listOf(
                Change("squat_barbell", restSeconds = 180),
                Change("bench_press_barbell", restSeconds = 300),
                Change("bicep_curl_dumbbell", restSeconds = 0),
                Change("hip_thrust_barbell", action = ChangeAction.ADD, sets = 3, reps = 8, restSeconds = 90),
            ),
            turn.changes,
        )
    }

    @Test
    fun `changes are offered only during a workout, which may still be empty, and the model is told which it is`() = runTest {
        val reply = """{"reply":"스쿼트부터","changes":[{"action":"add","name":"스쿼트","sets":5,"reps":5}],"remember":[]}"""
        val inApp = listOf(NamedExercise(squat, "스쿼트"))
        val resting = Recorder(reply)
        val started = Recorder(reply)

        val outside = Coach.chat(resting, profile, emptyList(), "오늘 뭐 해요?", emptyList(), emptyList(), WeightUnit.KG, today, earlier = "", exercisesInApp = inApp, inProgress = null)
        val inside = Coach.chat(started, profile, emptyList(), "오늘 뭐 해요?", emptyList(), emptyList(), WeightUnit.KG, today, earlier = "", exercisesInApp = inApp, inProgress = InProgress(minutes = 0))

        assertEquals(emptyList<Change>(), outside.changes)
        assertEquals(listOf(Change("squat_barbell", action = ChangeAction.ADD, sets = 5, reps = 5)), inside.changes)
        assertFalse(contextOf(resting).getValue("workoutInProgress").jsonPrimitive.content.toBoolean())
        assertTrue(contextOf(started).getValue("workoutInProgress").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `chat changes are kept only for today's exercises, within limits, once each, and only for what the rule can set`() = runTest {
        val model = Recorder(
            """{"reply":"오늘은 가볍게","changes":[
                {"id":"squat_barbell","weightKg":55,"reps":5},
                {"id":"squat_barbell","weightKg":20,"reps":5},
                {"id":"deadlift_barbell","weightKg":100,"reps":5},
                {"id":"bicep_curl_dumbbell","weightKg":12,"reps":12},
                {"id":"bench_press_barbell","weightKg":null,"reps":null}],"remember":[]}""",
        )

        val turn = Coach.chat(model, profile, emptyList(), "무릎이 아파요", plan, emptyList(), WeightUnit.KG, today, earlier = "")

        assertEquals("오늘은 가볍게", turn.text)
        assertEquals(listOf(Change("squat_barbell", 47.5, 5)), turn.changes)
        assertEquals("무릎이 아파요", model.calls.single().first.last().content)
    }

    @Test
    fun `offered notes add, replace or forget by key, the last offer for a key wins, and anything else is dropped`() = runTest {
        val model = Recorder(
            """{"reply":"ok","changes":[],"remember":[
                {"key":"Food/Lactose Intolerant","text":"유당불내증","forget":false},
                {"key":"health/left-knee","text":"무릎 괜찮아짐","forget":false},
                {"key":"health/left-knee","text":"","forget":true},
                {"key":"mood/today","text":"x","forget":false},
                {"key":"health/unknown","text":"","forget":true},
                {"key":"health/left-knee-2","text":"Left knee sore","forget":false},
                {"key":"goals/squat","text":"   ","forget":false}]}""",
        )

        val turn = Coach.chat(model, profile, emptyList(), "hi", emptyList(), emptyList(), WeightUnit.KG, today, earlier = "")

        assertEquals(
            listOf(
                Remember("food/lactose-intolerant", "유당불내증"),
                Remember("health/left-knee", "Left knee sore", forget = true),
                Remember("health/left-knee-2", "Left knee sore"),
            ),
            turn.remember,
        )
    }

    @Test
    fun `with sharing off no note is sent, but an offer still knows the note it replaces`() = runTest {
        val model = Recorder("""{"reply":"ok","changes":[],"remember":[{"key":"health/left-knee","text":"Knee fine","forget":false}]}""")

        val turn = Coach.chat(model, profile.copy(shareNotes = false), emptyList(), "hi", emptyList(), emptyList(), WeightUnit.KG, today, earlier = "")

        assertEquals(emptyList<String>(), contextOf(model).getValue("notes").jsonArray.toList())
        assertEquals(listOf(Remember("health/left-knee", "Knee fine", previous = "Left knee sore")), turn.remember)
    }

    @Test
    fun `an offer that repeats a stored note word for word is dropped`() = runTest {
        val model = Recorder("""{"reply":"ok","changes":[],"remember":[{"key":"health/left-knee","text":"Left knee sore","forget":false}]}""")

        assertEquals(emptyList<Remember>(), Coach.chat(model, profile, emptyList(), "hi", emptyList(), emptyList(), WeightUnit.KG, today, earlier = "").remember)
    }

    @Test
    fun `chat sends goal, bodyweight and the summary only when there is one, and keeps turns alternating`() = runTest {
        val model = Recorder("""{"reply":"ok","changes":[],"remember":[]}""")
        val history = listOf(ChatTurn(fromUser = false, text = "old reply", at = 1), ChatTurn(true, "a", at = 2), ChatTurn(false, "x", at = 3), ChatTurn(true, "b", at = 4))

        Coach.chat(model, profile, history, "c", emptyList(), emptyList(), WeightUnit.KG, today, earlier = "")

        val context = contextOf(model)
        assertEquals("build", context.getValue("goal").jsonPrimitive.content)
        assertEquals("75.0", context.getValue("bodyweightKg").jsonPrimitive.content)
        assertFalse(context.containsKey("earlierConversation"))
        assertEquals(
            listOf("system", "user" to "a", "assistant" to "x", "user" to "b\n\nc"),
            model.calls.single().first.map { if (it.role == "system") "system" else it.role to it.content },
        )
    }

    @Test
    fun `notes are sent newest change first, whatever order they are stored or dated in`() {
        val notes = listOf(
            Memory("health/a", "A", "2026-10-01", updatedAt = 5),
            Memory("health/b", "B", "2026-10-20", updatedAt = 1),
            Memory("health/c", "C", "2026-09-01", updatedAt = 9),
        )

        val sent = Coach.notesOf(profile.copy(memories = notes), today).map { it.jsonPrimitive.content.substringAfterLast(": ") }

        assertEquals(listOf("C", "A", "B"), sent)
    }

    @Test
    fun `years of notes are cut to the newest that fit, at most forty, and one long note does not block the rest`() {
        val long = (1..200).map { day -> Memory("health/note-$day", "note $day " + "x".repeat(60), "2026-01-01", updatedAt = day.toLong()) }
        val short = (1..50).map { Memory("food/n$it", "n$it", "2026-01-01", updatedAt = it.toLong()) }
        val essay = Memory("health/essay", "x".repeat(2_100), "2026-10-22", updatedAt = 999)
        val ended = (1..10).map { Memory("schedule/old-$it", "o$it", "2026-01-01", updatedAt = 100L + it, until = "2026-10-01") }

        val fromLong = Coach.notesOf(profile.copy(memories = long), today).map { it.jsonPrimitive.content }
        val fromShort = Coach.notesOf(profile.copy(memories = short), today)
        val pastEssay = Coach.notesOf(profile.copy(memories = listOf(essay) + short.take(3)), today)

        assertTrue(fromLong.sumOf { it.length } <= 2_000)
        assertTrue(fromLong.first().contains("note 200 "))
        assertTrue(fromLong.none { it.contains("note 1 ") })
        assertEquals(40, fromShort.size)
        assertEquals(40, Coach.notesOf(profile.copy(memories = short + ended), today).size)
        assertEquals(3, pastEssay.size)
    }

    @Test
    fun `keys are topic slash a short lower-case name, and typed notes get a free key under the chosen topic`() {
        assertEquals("health/left-knee", MemoryKeys.normalize(" Health/Left Knee! "))
        assertEquals("food/유당", MemoryKeys.normalize("food/유당"))
        assertEquals(null, MemoryKeys.normalize("squat 100"))
        assertEquals("goals/" + "a".repeat(40), MemoryKeys.normalize("goals/" + "a".repeat(50)))
        assertEquals(MemoryTopic.HEALTH, MemoryTopic.of("mood/today"))
        assertEquals(null, MemoryKeys.normalize("mood/today"))
        assertEquals(null, MemoryKeys.normalize("health/!!!"))
        assertEquals(MemoryTopic.FOOD, MemoryTopic.of("food/lactose"))
        assertEquals("health/left-knee-sore-2", MemoryKeys.forText(MemoryTopic.HEALTH, "Left knee sore since Monday", setOf("health/left-knee-sore")))
        assertEquals("goals/3-4-squat", MemoryKeys.forText(MemoryTopic.GOALS, "3/4 squat depth", emptySet()))
        assertEquals("health/food-diet-notes", MemoryKeys.forText(MemoryTopic.HEALTH, "food/diet notes", emptySet()))
        assertEquals("schedule/note", MemoryKeys.forText(MemoryTopic.SCHEDULE, "!!!", emptySet()))
    }

    @Test
    fun `a short-term note counts until its end date and then stops being sent`() {
        val notes = listOf(
            Memory("health/left-knee", "Left knee sore", "2026-10-20", updatedAt = 3, until = "2026-10-22"),
            Memory("schedule/travel", "Travelling", "2026-10-01", updatedAt = 2, until = "2026-10-21"),
            Memory("food/lactose", "Lactose intolerant", "2026-09-01", updatedAt = 1),
        )

        val sent = Coach.notesOf(profile.copy(memories = notes), today).map { it.jsonPrimitive.content }

        assertEquals(listOf("health/left-knee (saved 2026-10-20, until 2026-10-22): Left knee sore", "food/lactose (saved 2026-09-01): Lactose intolerant"), sent)
    }

    @Test
    fun `a note offered for a while carries its days, held between 1 and 90`() = runTest {
        val model = Recorder(
            """{"reply":"ok","changes":[],"remember":[
                {"key":"health/left-knee","text":"Knee sore","forget":false,"days":14},
                {"key":"schedule/busy","text":"Busy month","forget":false,"days":400},
                {"key":"schedule/now","text":"Today only","forget":false,"days":0},
                {"key":"food/pork","text":"No pork","forget":false,"days":null}]}""",
        )

        val turn = Coach.chat(model, profile.copy(memories = emptyList()), emptyList(), "hi", emptyList(), emptyList(), WeightUnit.KG, today, earlier = "")

        assertEquals(listOf(14, 90, 1, null), turn.remember.map { it.days })
    }

    @Test
    fun `chat sends at most thirty earlier turns as they are`() = runTest {
        val model = Recorder("""{"reply":"ok","changes":[],"remember":[]}""")
        val history = (1..40L).map { ChatTurn(fromUser = it % 2 == 1L, text = "m$it", at = it) }

        Coach.chat(model, profile, history, "hi", emptyList(), emptyList(), WeightUnit.KG, today, earlier = "")

        val sent = model.calls.single().first.drop(1).dropLast(1).map { it.content }
        assertEquals((11..40).map { "m$it" }, sent)
    }

    @Test
    fun `older chat is summarized only once ten turns beyond the last twenty have piled up`() {
        val chat = (1..45L).map { ChatTurn(fromUser = it % 2 == 1L, text = "m$it", at = it) }

        assertEquals((1..25L).toList(), Coach.toSummarize(chat, summarizedUntil = 0).map { it.at })
        assertEquals((16..25L).toList(), Coach.toSummarize(chat, summarizedUntil = 15).map { it.at })
        assertEquals(emptyList<ChatTurn>(), Coach.toSummarize(chat, summarizedUntil = 16))
    }

    @Test
    fun `the summary folds in new turns, is cut to 600 characters, and goes with later chat`() = runTest {
        val summarizer = Recorder("""{"summary":"${"s".repeat(700)}"}""")
        val summary = Coach.summarize(summarizer, profile, "Knee sore last week.", listOf(ChatTurn(true, "Can I run?", at = 1))) { it }
        val chatter = Recorder("""{"reply":"ok","changes":[],"remember":[]}""")

        Coach.chat(chatter, profile, emptyList(), "hi", emptyList(), emptyList(), WeightUnit.KG, today, earlier = "Knee sore last week.")

        assertEquals(600, summary.length)
        val summarizerPayload = payloadOf(summarizer)
        assertEquals("Knee sore last week.", summarizerPayload.getValue("summary").jsonPrimitive.content)
        assertTrue(summarizerPayload.getValue("messages").toString().contains("Can I run?"))
        assertEquals("Knee sore last week.", contextOf(chatter).getValue("earlierConversation").jsonPrimitive.content)
    }
}
