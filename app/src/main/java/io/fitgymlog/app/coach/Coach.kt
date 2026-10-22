package io.fitgymlog.app.coach

import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.model.WorkoutSet
import java.time.Duration
import java.time.LocalDate
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

fun interface Completer {
    suspend fun complete(messages: List<ChatMessage>, schemaName: String, schema: JsonObject): String
}

data class Protein(val dailyGrams: Int, val mealGrams: Int)

/** An exercise in today's workout with its past sessions, most recent first. */
data class Planned(val exercise: Exercise, val name: String, val sessions: List<PastSession>)

data class PastSession(val date: LocalDate, val sets: List<WorkoutSet>)

/** A finished exercise as the review sees it. */
data class Done(val name: String, val sets: List<WorkoutSet>)

/** Every number a model returns is clamped to the rule limits again, whatever the prompt asked for. */
object Coach {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    fun ruleTargets(plan: List<Planned>, unit: WeightUnit): List<RuleTarget> =
        plan.map { Progression.target(it.exercise, it.sessions.map(PastSession::sets), unit) }

    suspend fun targets(completer: Completer, profile: Profile, plan: List<Planned>, unit: WeightUnit, today: LocalDate): List<Target> {
        val rules = ruleTargets(plan, unit)
        val adjustable = plan.zip(rules).filter { (_, rule) -> rule.weightKg != null || rule.reps != null }
        val replies = if (adjustable.isEmpty()) emptyMap() else {
            val payload = buildJsonObject {
                put("date", today.toString())
                put("unit", unit.name.lowercase())
                put("notes", notesOf(profile))
                putJsonArray("exercises") {
                    adjustable.forEach { (planned, rule) -> add(exerciseJson(planned, rule)) }
                }
            }
            val reply = completer.complete(messages(profile, TARGETS_TASK, payload), "targets", TARGETS_SCHEMA)
            decode<TargetsReply>(reply).targets.associateBy { it.id }
        }
        return rules.map { rule ->
            val suggested = replies[rule.exerciseId]
            Target(
                exerciseId = rule.exerciseId,
                reason = rule.reason,
                weightKg = clampWeight(suggested?.weightKg ?: rule.weightKg, rule),
                reps = clampReps(suggested?.reps ?: rule.reps, rule),
                lastWeightKg = rule.lastWeightKg,
                note = suggested?.note?.takeIf { it.isNotBlank() },
            )
        }
    }

    suspend fun review(completer: Completer, profile: Profile, done: List<Done>, recent: List<Pair<LocalDate, List<Done>>>, unit: WeightUnit, today: LocalDate, workoutUuid: String, withFood: Boolean): Review {
        val protein = profile.bodyweightKg?.takeIf { withFood }?.let { proteinFor(it, profile.goal) }
        val payload = buildJsonObject {
            put("date", today.toString())
            put("unit", unit.name.lowercase())
            put("goal", profile.goal.name.lowercase())
            profile.bodyweightKg?.let { put("bodyweightKg", it) }
            protein?.let { put("proteinGramsToday", it.dailyGrams) }
            put("notes", notesOf(profile))
            put("workout", JsonArray(done.map { doneJson(it) }))
            putJsonArray("recentWorkouts") {
                recent.forEach { (date, exercises) -> add(buildJsonObject { put("date", date.toString()); put("exercises", JsonArray(exercises.map { doneJson(it) })) }) }
            }
        }
        val task = if (protein != null) REVIEW_TASK + " " + FOOD_TASK else REVIEW_TASK
        val reply = decode<ReviewReply>(completer.complete(messages(profile, task, payload), "review", REVIEW_SCHEMA))
        val food = protein?.let { Food(it.dailyGrams, it.mealGrams, reply.foodSummary.orEmpty(), reply.mealIdeas.filter(String::isNotBlank).take(MEAL_IDEAS)) }
        return Review(workoutUuid, reply.notes, food)
    }

    /** [today] is the workout open now, each exercise with the rule limits its changes are held to. */
    suspend fun chat(
        completer: Completer,
        profile: Profile,
        history: List<ChatTurn>,
        message: String,
        today: List<Pair<Planned, RuleTarget>>,
        recent: List<Pair<LocalDate, List<Done>>>,
        unit: WeightUnit,
        date: LocalDate,
    ): ChatTurn {
        val context = buildJsonObject {
            put("date", date.toString())
            put("unit", unit.name.lowercase())
            put("notes", notesOf(profile))
            profile.bodyweightKg?.let { put("bodyweightKg", it) }
            put("goal", profile.goal.name.lowercase())
            put("todaysWorkout", JsonArray(today.map { (planned, rule) -> exerciseJson(planned, rule) }))
            putJsonArray("recentWorkouts") {
                recent.forEach { (day, exercises) -> add(buildJsonObject { put("date", day.toString()); put("exercises", JsonArray(exercises.map { doneJson(it) })) }) }
            }
        }
        val conversation = history.takeLast(CHAT_TURNS).map { ChatMessage(if (it.fromUser) "user" else "assistant", it.text) }
        val all = messages(profile, CHAT_TASK, context) + conversation + ChatMessage("user", message)
        val reply = decode<ChatReply>(completer.complete(all, "chat", CHAT_SCHEMA))
        val rules = today.associate { (planned, rule) -> planned.exercise.id to rule }
        val changes = reply.changes.mapNotNull { change ->
            val rule = rules[change.id] ?: return@mapNotNull null
            Change(change.id, clampWeight(change.weightKg, rule), clampReps(change.reps, rule)).takeIf { it.weightKg != null || it.reps != null }
        }
        val remember = reply.remember.mapNotNull { offered ->
            val topic = MemoryTopic.entries.firstOrNull { it.name.equals(offered.topic, ignoreCase = true) } ?: return@mapNotNull null
            offered.text.trim().takeIf { it.isNotEmpty() }?.let { Remember(topic, it) }
        }
        return ChatTurn(fromUser = false, text = reply.reply, changes = changes, remember = remember)
    }

    fun proteinFor(bodyweightKg: Double, goal: Goal): Protein {
        val perKg = when (goal) {
            Goal.BUILD -> 1.8
            Goal.KEEP -> 1.6
            Goal.LOSE -> 2.2
        }
        val meal = (bodyweightKg * MEAL_PROTEIN_PER_KG).coerceIn(MEAL_PROTEIN_MIN, MEAL_PROTEIN_MAX)
        return Protein(dailyGrams = roundTo5(bodyweightKg * perKg), mealGrams = roundTo5(meal))
    }

    /** The exact request body, so the user can see what leaves the phone. */
    fun preview(profile: Profile, plan: List<Planned>, unit: WeightUnit, today: LocalDate): String {
        val rules = ruleTargets(plan, unit)
        val payload = buildJsonObject {
            put("date", today.toString())
            put("unit", unit.name.lowercase())
            put("notes", notesOf(profile))
            put("exercises", JsonArray(plan.zip(rules).map { (planned, rule) -> exerciseJson(planned, rule) }))
        }
        return Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), JsonArray(messages(profile, TARGETS_TASK, payload).map {
            buildJsonObject { put("role", it.role); put("content", it.content) }
        }))
    }

    private fun clampWeight(kg: Double?, rule: RuleTarget): Double? {
        if (kg == null) return null
        val low = rule.minWeightKg ?: return null
        val high = rule.maxWeightKg ?: return null
        val step = rule.stepKg ?: return kg.coerceIn(low, high)
        val stepped = (kg.coerceIn(low, high) / step).roundToInt() * step
        return (stepped * 1000).roundToInt() / 1000.0
    }

    private fun clampReps(reps: Int?, rule: RuleTarget): Int? {
        if (reps == null) return null
        val base = rule.reps ?: return reps.coerceIn(1, MAX_REPS)
        return reps.coerceIn((base - REP_SWING).coerceAtLeast(1), (base + REP_SWING).coerceAtMost(MAX_REPS))
    }

    private fun messages(profile: Profile, task: String, payload: JsonObject): List<ChatMessage> = listOf(
        ChatMessage("system", SYSTEM.replace("{language}", if (profile.language == "ko") "Korean" else "English") + "\n\n" + task),
        ChatMessage("user", payload.toString()),
    )

    /** Newest notes first, up to a fixed budget, so years of notes never crowd out the training data. */
    internal fun notesOf(profile: Profile): JsonArray {
        var budget = NOTES_BUDGET_CHARS
        val sent = profile.memories.sortedByDescending { it.savedAt }.take(MAX_NOTES).map { "${it.topic.name.lowercase()}, saved ${it.savedAt}: ${it.text}" }
            .takeWhile { line -> (budget - line.length).also { budget = it } >= 0 }
        return JsonArray(sent.map(::JsonPrimitive))
    }

    private fun exerciseJson(planned: Planned, rule: RuleTarget) = buildJsonObject {
        put("id", planned.exercise.id)
        put("name", planned.name)
        put("kind", planned.exercise.kind.name.lowercase())
        put("rule", buildJsonObject {
            put("reason", rule.reason.name.lowercase())
            rule.weightKg?.let { put("weightKg", it) }
            rule.reps?.let { put("reps", it) }
            rule.minWeightKg?.let { put("minKg", it) }
            rule.maxWeightKg?.let { put("maxKg", it) }
            rule.stepKg?.let { put("stepKg", it) }
        })
        putJsonArray("recent") {
            planned.sessions.take(RECENT_SESSIONS).forEach { session ->
                add(buildJsonObject { put("date", session.date.toString()); put("sets", setsText(session.sets)) })
            }
        }
    }

    private fun doneJson(done: Done) = buildJsonObject {
        put("name", done.name)
        put("sets", setsText(done.sets))
        restsOf(done.sets)?.let { put("restsBetweenSets", it) }
    }

    private fun setsText(sets: List<WorkoutSet>): String = sets.filter { it.isCompleted }.joinToString(", ") { set ->
        listOfNotNull(
            set.weightKg?.let { "${trim(it)}kg" },
            set.reps?.let { "${it} reps" },
            set.durationSeconds?.let { "${it}s" },
            set.level?.let { "level $it" },
            set.type.takeIf { it != SetType.NORMAL }?.name?.lowercase(),
        ).joinToString(" ")
    }

    private fun restsOf(sets: List<WorkoutSet>): String? {
        val times = sets.mapNotNull { it.completedAt }.sorted()
        if (times.size < 2) return null
        return times.zipWithNext { a, b -> Duration.between(a, b).seconds }.joinToString(", ") { "%d:%02d".format(it / 60, it % 60) }
    }

    private fun trim(kg: Double): String = if (kg % 1.0 == 0.0) kg.toLong().toString() else kg.toString()

    private fun roundTo5(grams: Double): Int = ((grams / 5).roundToInt() * 5)

    private inline fun <reified T> decode(reply: String): T = try {
        json.decodeFromString<T>(jsonIn(reply))
    } catch (error: Exception) {
        throw CoachException(CoachFailure.BAD_REPLY, reply.take(300), error)
    }

    @Serializable
    private class TargetsReply(val targets: List<TargetReply> = emptyList())

    @Serializable
    private class TargetReply(val id: String, val weightKg: Double? = null, val reps: Int? = null, val note: String? = null)

    @Serializable
    private class ReviewReply(val notes: String = "", val foodSummary: String? = null, val mealIdeas: List<String> = emptyList())

    @Serializable
    private class ChatReply(val reply: String = "", val changes: List<ChangeReply> = emptyList(), val remember: List<RememberReply> = emptyList())

    @Serializable
    private class ChangeReply(val id: String, val weightKg: Double? = null, val reps: Int? = null)

    @Serializable
    private class RememberReply(val topic: String = "", val text: String = "")

    private const val RECENT_SESSIONS = 3
    private const val CHAT_TURNS = 20
    private const val REP_SWING = 2
    private const val MAX_REPS = 30
    private const val MEAL_PROTEIN_PER_KG = 0.4
    private const val MEAL_PROTEIN_MIN = 20.0
    private const val MEAL_PROTEIN_MAX = 50.0
    private const val MEAL_IDEAS = 3
    private const val MAX_NOTES = 40
    private const val NOTES_BUDGET_CHARS = 2_000

    private const val SYSTEM = """You are the coach inside FitGymLog, a workout log. The lifter shares their own logged training and notes they asked you to remember, each with the date it was saved; weigh old notes, such as an injury from months ago, less than recent ones. Be brief, specific and kind, and write in {language}. You are not a doctor: for pain beyond ordinary muscle soreness, suggest stopping that exercise and seeing a professional. Never suggest eating under 1,200 kcal a day, or under 1,500 for a lifter you know is a man, without medical supervision; never suggest skipping meals to lose weight, or supplements beyond protein powder, creatine and caffeine. Weights are in kilograms in the data. Reply with JSON only, in exactly the requested shape."""

    private const val TARGETS_TASK = """Set today's target for each exercise in the data. Each has the app's rule target and an allowed range. Keep the rule target unless the history or the notes give a reason, such as pain, repeated misses or reps far above the plan. Weights must stay between minKg and maxKg and be a multiple of stepKg; reps may move by at most 2 from the rule. For each exercise write a note of at most 12 words saying why."""

    private const val REVIEW_TASK = """Review the workout just finished in 2 to 4 short sentences: what went well and what to change next time, such as weight, rest or order. Mention rest only if the rest times show something."""

    private const val FOOD_TASK = """Also write foodSummary, one sentence about eating today given proteinGramsToday and the goal, and mealIdeas, three simple meals of at most 6 words each that respect the food notes."""

    private const val CHAT_TASK = """Answer the lifter's message. The first user message holds today's workout with rule limits, recent workouts and their notes. To change today's targets, list changes by exercise id; only exercises in today's workout can change, within their rule limits. If the lifter states a lasting fact about injuries, food, schedule or goals, offer it in remember with topic HEALTH, FOOD, SCHEDULE or GOALS; never invent one. Leave changes and remember empty when there is nothing to add."""
}

private fun objectSchema(vararg properties: Pair<String, JsonElement>) = buildJsonObject {
    put("type", "object")
    put("properties", JsonObject(properties.toMap()))
    put("required", buildJsonArray { properties.forEach { add(JsonPrimitive(it.first)) } })
    put("additionalProperties", false)
}

private fun type(vararg names: String): JsonObject = buildJsonObject {
    if (names.size == 1) put("type", names[0]) else put("type", buildJsonArray { names.forEach { add(JsonPrimitive(it)) } })
}

private fun arrayOf(items: JsonElement) = buildJsonObject { put("type", "array"); put("items", items) }

private val TARGETS_SCHEMA = objectSchema(
    "targets" to arrayOf(objectSchema("id" to type("string"), "weightKg" to type("number", "null"), "reps" to type("integer", "null"), "note" to type("string"))),
)

private val REVIEW_SCHEMA = objectSchema(
    "notes" to type("string"),
    "foodSummary" to type("string", "null"),
    "mealIdeas" to arrayOf(type("string")),
)

private val CHAT_SCHEMA = objectSchema(
    "reply" to type("string"),
    "changes" to arrayOf(objectSchema("id" to type("string"), "weightKg" to type("number", "null"), "reps" to type("integer", "null"))),
    "remember" to arrayOf(
        objectSchema(
            "topic" to buildJsonObject { put("type", "string"); put("enum", buildJsonArray { MemoryTopic.entries.forEach { add(JsonPrimitive(it.name)) } }) },
            "text" to type("string"),
        ),
    ),
)
