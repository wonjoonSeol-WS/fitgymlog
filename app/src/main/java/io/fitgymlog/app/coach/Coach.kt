package io.fitgymlog.app.coach

import io.fitgymlog.app.checkin.WeekSummary
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.RoutineExercise
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.model.isTimed
import io.fitgymlog.app.ui.REST_CHOICES
import io.fitgymlog.app.ui.formatRest
import io.fitgymlog.app.ui.formatKmh
import io.fitgymlog.app.ui.formatWeightNumber
import io.fitgymlog.app.ui.fromDisplayWeight
import io.fitgymlog.app.ui.workout.rpeText
import java.time.Duration
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

fun interface Completer {
    suspend fun complete(messages: List<ChatMessage>, schemaName: String, schema: JsonObject): String
}

data class Protein(val dailyGrams: Int, val mealGrams: Int)

/** A routine as the coach sees it, each planned exercise with its name in the app's language. */
data class RoutinePlan(val id: Long, val name: String, val exercises: List<Pair<NamedExercise, RoutineExercise>>)

/** An exercise in today's workout with its past sessions, most recent first, and the reps its routine plans, if it has one. */
data class Planned(
    val exercise: Exercise,
    val name: String,
    val sessions: List<PastSession>,
    val today: List<WorkoutSet> = emptyList(),
    val restSeconds: Int? = null,
    val plannedReps: Int? = null,
)

data class InProgress(val minutes: Long, val restLeftSeconds: Long? = null)

data class PastSession(val date: LocalDate, val sets: List<WorkoutSet>)

data class Done(val name: String, val kind: ExerciseKind, val sets: List<WorkoutSet>)

data class NamedExercise(val exercise: Exercise, val name: String)

/** [effort] is how hard the whole workout felt, 1 to 10, when the lifter rated it. */
data class PastWorkout(val date: LocalDate, val exercises: List<Done>, val effort: Int? = null)

object Coach {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val pretty = Json { prettyPrint = true }

    fun ruleTargets(plan: List<Planned>, unit: WeightUnit): List<RuleTarget> =
        plan.map { Progression.target(it.exercise, it.sessions.map(PastSession::sets), unit, it.plannedReps) }

    fun ruleOnly(plan: List<Planned>, unit: WeightUnit): List<Target> = ruleTargets(plan, unit).map { targetOf(it, suggested = null) }

    suspend fun targets(completer: Completer, profile: Profile, plan: List<Planned>, unit: WeightUnit, today: LocalDate): List<Target> {
        val rules = ruleTargets(plan, unit)
        val replies = targetsRequest(profile, plan, rules, unit, today)
            ?.let { decode<TargetsReply>(completer.complete(it, "targets", TARGETS_SCHEMA)).targets.associateBy { reply -> reply.id } }
            .orEmpty()
        return rules.map { rule -> targetOf(rule, replies[rule.exerciseId]) }
    }

    fun preview(profile: Profile, plan: List<Planned>, unit: WeightUnit, today: LocalDate): String? =
        targetsRequest(profile, plan, ruleTargets(plan, unit), unit, today)?.let { messages ->
            pretty.encodeToString(JsonElement.serializer(), JsonArray(messages.map { buildJsonObject { put("role", it.role); put("content", it.content) } }))
        }

    suspend fun review(completer: Completer, profile: Profile, workout: PastWorkout, recent: List<PastWorkout>, unit: WeightUnit, workoutUuid: String, withFood: Boolean): Review {
        val protein = if (withFood) profile.bodyweightKg?.let { proteinFor(it, profile.goal) } else null
        val payload = buildJsonObject {
            header(profile, unit, workout.date)
            aboutLifter(profile)
            protein?.let { put("proteinGramsToday", it.dailyGrams) }
            put("workout", JsonArray(workout.exercises.map(::doneJson)))
            workout.effort?.let { put("effortOutOf10", it) }
            put("recentWorkouts", recentJson(recent))
        }
        val task = if (protein != null) "$REVIEW_TASK $FOOD_TASK" else REVIEW_TASK
        val schema = if (protein != null) FOOD_REVIEW_SCHEMA else REVIEW_SCHEMA
        val reply = decode<ReviewReply>(completer.complete(messages(profile, task, payload, schema), "review", schema))
        if (reply.notes.isBlank()) throw CoachException(CoachFailure.BAD_REPLY, "Empty review")
        val food = protein?.let { Food(it.dailyGrams, it.mealGrams, reply.foodSummary.orEmpty(), reply.mealIdeas.filter(String::isNotBlank).take(MEAL_IDEAS), profile.calories?.target) }
        return Review(workoutUuid, reply.notes.trim(), food)
    }

    suspend fun chat(
        completer: Completer,
        profile: Profile,
        history: List<ChatTurn>,
        message: String,
        plan: List<Planned>,
        recent: List<PastWorkout>,
        unit: WeightUnit,
        today: LocalDate,
        earlier: String,
        exercisesInApp: List<NamedExercise> = emptyList(),
        inProgress: InProgress? = InProgress(minutes = 0).takeIf { plan.isNotEmpty() },
    ): ChatTurn {
        val rules = ruleTargets(plan, unit)
        val named = exercisesInApp.distinctBy { it.name.trim().lowercase() }
        val context = buildJsonObject {
            header(profile, unit, today)
            aboutLifter(profile)
            if (named.isNotEmpty()) put("exercisesInApp", JsonArray(named.map { JsonPrimitive(it.name) }))
            if (earlier.isNotBlank()) put("earlierConversation", earlier)
            put("workoutInProgress", inProgress != null)
            inProgress?.let { now ->
                put("minutesIntoWorkout", now.minutes)
                now.restLeftSeconds?.let { put("restSecondsLeft", it) }
            }
            put("todaysWorkout", JsonArray(plan.zip(rules).map { (planned, rule) -> exerciseJson(planned, rule) }))
            put("recentWorkouts", recentJson(recent))
        }
        val system = ChatMessage("system", "${instructions(profile, CHAT_TASK, CHAT_SCHEMA)}\n\nData: $context")
        val names = named.associate { it.exercise.id to it.name } + plan.associate { it.exercise.id to it.name }
        val turns = history.takeLast(MAX_RAW_TURNS).map { ChatMessage(if (it.fromUser) "user" else "assistant", spoken(it) { id -> names[id] ?: id }) }.filter { it.content.isNotBlank() } +
            ChatMessage("user", message)
        val reply = decode<ChatReply>(completer.complete(listOf(system) + alternating(turns), "chat", CHAT_SCHEMA))
        val changes = if (inProgress != null) changesOf(reply.changes, plan, rules, named) else emptyList()
        val remember = offersOf(reply.remember, profile.memories)
        if (reply.reply.isBlank() && changes.isEmpty() && remember.isEmpty()) throw CoachException(CoachFailure.BAD_REPLY, "Empty chat reply")
        return ChatTurn(fromUser = false, text = reply.reply.trim(), changes = changes, remember = remember)
    }

    /** One change per exercise, the last offered; adjustments stay within the rule limits, and an exercise is added only from the app's list. */
    private fun changesOf(offered: List<ChangeReply>, plan: List<Planned>, rules: List<RuleTarget>, inApp: List<NamedExercise>): List<Change> {
        val todays = plan.associateBy { it.exercise.id }
        val ruleOf = rules.associateBy { it.exerciseId }
        val byName = inApp.associateBy { it.name.trim().lowercase() }
        return offered.mapNotNull { offer ->
            when (offer.action.trim().lowercase()) {
                "add" -> {
                    val exercise = offer.name?.let { byName[it.trim().lowercase()] }?.exercise?.takeIf { it.id !in todays } ?: return@mapNotNull null
                    Change(
                        exercise.id,
                        action = ChangeAction.ADD,
                        sets = setsOf(offer.sets),
                        reps = repsFor(offer.reps, exercise.kind),
                        minutes = minutesFor(offer.minutes, exercise.kind),
                        restSeconds = restFor(offer.restSeconds, exercise.kind),
                    )
                }
                "remove" -> offer.id?.takeIf { it in todays }?.let { Change(it, action = ChangeAction.REMOVE) }
                "adjust" -> {
                    val planned = offer.id?.let(todays::get) ?: return@mapNotNull null
                    val rule = ruleOf.getValue(planned.exercise.id)
                    Change(
                        planned.exercise.id,
                        weightKg = offer.weightKg?.let { clampWeight(it, rule) },
                        reps = offer.reps?.let { clampReps(it, rule) },
                        sets = setsOf(offer.sets),
                        minutes = minutesFor(offer.minutes, planned.exercise.kind),
                        restSeconds = restFor(offer.restSeconds, planned.exercise.kind),
                    ).takeIf { listOf(it.weightKg, it.reps, it.sets, it.minutes, it.restSeconds).any { field -> field != null } }
                }
                else -> null
            }
        }.associateBy { it.exerciseId }.values.toList()
    }

    private fun minutesFor(minutes: Int?, kind: ExerciseKind): Int? = minutes?.takeIf { kind == ExerciseKind.CARDIO }?.coerceIn(1, MAX_MINUTES)

    private fun restFor(seconds: Int?, kind: ExerciseKind): Int? = seconds?.takeIf { kind != ExerciseKind.CARDIO }?.let { REST_CHOICES.minBy { choice -> abs(choice - it) } }

    private fun setsOf(sets: Int?): Int? = sets?.takeIf { it >= 1 }?.coerceAtMost(MAX_SETS)

    private fun repsFor(reps: Int?, kind: ExerciseKind): Int? = reps?.takeIf { !kind.isTimed }?.coerceIn(1, MAX_REPS)

    suspend fun week(
        completer: Completer,
        profile: Profile,
        summary: WeekSummary,
        nameOf: (String) -> String,
        workouts: List<PastWorkout>,
        routines: List<RoutinePlan>,
        exercisesInApp: List<NamedExercise>,
        unit: WeightUnit,
        today: LocalDate,
    ): WeekReview {
        val named = exercisesInApp.distinctBy { it.name.trim().lowercase() }
        val payload = buildJsonObject {
            header(profile, unit, today)
            aboutLifter(profile)
            put("week", weekJson(summary, nameOf))
            put("workoutsThisWeek", recentJson(workouts))
            put("routines", JsonArray(routines.map(::routineJson)))
            put("exercisesInApp", JsonArray(named.map { JsonPrimitive(it.name) }))
        }
        val reply = decode<WeekReply>(completer.complete(messages(profile, WEEK_TASK, payload, WEEK_SCHEMA), "week", WEEK_SCHEMA))
        val changes = routineChangesOf(reply.changes, routines, named)
        if (reply.reply.isBlank() && changes.isEmpty()) throw CoachException(CoachFailure.BAD_REPLY, "Empty week reply")
        return WeekReview(summary.start.toString(), reply.reply.trim(), changes)
    }

    private fun weekJson(summary: WeekSummary, nameOf: (String) -> String) = buildJsonObject {
        put("from", summary.start.toString())
        put("to", summary.end.toString())
        put("workouts", summary.workouts.first)
        put("workoutsLastWeek", summary.workouts.second)
        put("minutes", summary.minutes.first)
        put("minutesLastWeek", summary.minutes.second)
        put("volumeKg", summary.volumeKg.first.roundToLong())
        put("volumeKgLastWeek", summary.volumeKg.second.roundToLong())
        put("newRecords", JsonArray(summary.records.map { JsonPrimitive("${nameOf(it.exerciseId)}: ${it.metrics.joinToString { metric -> metric.name.lowercase() }}") }))
        put("missedReps", JsonArray(summary.missed.map { JsonPrimitive("${nameOf(it.exerciseId)}: ${it.missed} of ${it.sets} sets short of their target") }))
        summary.weightTrendKg?.let { put("weightTrendKg", tenth(it)) }
        summary.weightChangeKg?.let { put("weightChangeKg", tenth(it)) }
    }

    private fun routineJson(routine: RoutinePlan) = buildJsonObject {
        put("id", routine.id)
        put("name", routine.name)
        put("exercises", JsonArray(routine.exercises.map { (named, planned) ->
            buildJsonObject {
                put("id", named.exercise.id)
                put("name", named.name)
                put("kind", named.exercise.kind.name.lowercase())
                put("sets", planned.sets)
                planned.reps?.let { put("reps", it) }
                planned.durationSeconds?.let { put("minutes", it / 60) }
            }
        }))
    }

    /** One change per exercise of a routine, the last offered; an exercise is added only from the app's list and only once to a routine. */
    private fun routineChangesOf(offered: List<RoutineChangeReply>, routines: List<RoutinePlan>, inApp: List<NamedExercise>): List<RoutineChange> {
        val byId = routines.associateBy { it.id }
        val byName = inApp.associateBy { it.name.trim().lowercase() }
        return offered.mapNotNull { offer ->
            val routine = offer.routine?.let(byId::get) ?: return@mapNotNull null
            val planned = routine.exercises.associate { (named, _) -> named.exercise.id to named.exercise }
            when (offer.action.trim().lowercase()) {
                "add" -> {
                    val exercise = offer.name?.let { byName[it.trim().lowercase()] }?.exercise?.takeIf { it.id !in planned } ?: return@mapNotNull null
                    RoutineChange(routine.id, exercise.id, ChangeAction.ADD, setsOf(offer.sets), repsFor(offer.reps, exercise.kind), minutesFor(offer.minutes, exercise.kind))
                }
                "remove" -> offer.id?.takeIf { it in planned }?.let { RoutineChange(routine.id, it, ChangeAction.REMOVE) }
                "adjust" -> {
                    val exercise = offer.id?.let(planned::get) ?: return@mapNotNull null
                    RoutineChange(routine.id, exercise.id, ChangeAction.ADJUST, setsOf(offer.sets), repsFor(offer.reps, exercise.kind), minutesFor(offer.minutes, exercise.kind))
                        .takeIf { it.sets != null || it.reps != null || it.minutes != null }
                }
                else -> null
            }
        }.associateBy { it.routineId to it.exerciseId }.values.toList()
    }

    /** The coach's own offers go back with its words, so a later "yes, do that" has something to refer to. */
    private fun spoken(turn: ChatTurn, nameOf: (String) -> String): String {
        if (turn.fromUser) return turn.text
        val changes = turn.changes.takeIf { it.isNotEmpty() }?.joinToString("; ", prefix = "Offered workout changes: ") { change ->
            listOfNotNull(
                change.action.name.lowercase(),
                nameOf(change.exerciseId),
                change.sets?.let { "$it sets" },
                change.weightKg?.let { "${formatWeightNumber(it, WeightUnit.KG)}kg" },
                change.reps?.let { "$it reps" },
                change.minutes?.let { "$it min" },
                change.restSeconds?.let { "rest ${it}s" },
                "(applied)".takeIf { change.applied },
            ).joinToString(" ")
        }
        val notes = turn.remember.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Offered notes: ") { it.key }
        return listOfNotNull(turn.text.takeIf { it.isNotBlank() }, changes, notes).joinToString("\n")
    }

    /** Chat turns older than the ones sent as they are, not yet folded into the summary; only once enough have piled up. */
    fun toSummarize(chat: List<ChatTurn>, summarizedUntil: Long): List<ChatTurn> {
        val older = chat.dropLast(CHAT_TURNS).filter { it.at > summarizedUntil }
        return if (older.size >= SUMMARY_BATCH) older else emptyList()
    }

    suspend fun summarize(completer: Completer, profile: Profile, previous: String, turns: List<ChatTurn>, nameOf: (String) -> String): String {
        val payload = buildJsonObject {
            put("summary", previous)
            put("messages", JsonArray(turns.map { turn -> buildJsonObject { put("from", if (turn.fromUser) "lifter" else "coach"); put("text", spoken(turn, nameOf)) } }))
        }
        val reply = decode<SummaryReply>(completer.complete(messages(profile, SUMMARY_TASK, payload, SUMMARY_SCHEMA), "summary", SUMMARY_SCHEMA))
        return reply.summary.trim().take(MAX_SUMMARY_CHARS).ifEmpty { throw CoachException(CoachFailure.BAD_REPLY, "Empty summary") }
    }

    /** Null when the picture holds no measurement the app can use. */
    suspend fun readBody(completer: Completer, profile: Profile, imageUrl: String, today: LocalDate): BodyMeasurement? {
        val messages = listOf(
            ChatMessage("system", instructions(profile, BODY_TASK, BODY_SCHEMA)),
            ChatMessage("user", buildJsonObject { put("today", today.toString()) }.toString(), imageUrl),
        )
        val reply = decode<BodyReply>(completer.complete(messages, "body", BODY_SCHEMA))
        return bodyOf(reply, today)
    }

    /** Values outside what a person can measure are dropped as misreads; the app converts pounds itself. */
    internal fun bodyOf(reply: BodyReply, today: LocalDate): BodyMeasurement? {
        val unit = if (reply.massUnit?.lowercase()?.let { it.startsWith("lb") || it.startsWith("pound") } == true) WeightUnit.LB else WeightUnit.KG
        val date = reply.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.takeIf { !it.isAfter(today) } ?: today
        val weight = reply.weight?.let { fromDisplayWeight(it, unit) }?.takeIf { it in BODYWEIGHT_KG }
        val fat = reply.bodyFatPercent?.takeIf { it in BODY_FAT_PERCENT }
        val muscle = reply.skeletalMuscle?.let { fromDisplayWeight(it, unit) }?.takeIf { it in SKELETAL_MUSCLE_KG }
        val visceral = reply.visceralFatLevel?.roundToInt()?.takeIf { it in VISCERAL_FAT_LEVEL }
        if (weight == null && fat == null && muscle == null && visceral == null) return null
        return BodyMeasurement(
            date = date.toString(),
            weightKg = weight?.let(::tenth),
            bodyFatPercent = fat?.let(::tenth),
            skeletalMuscleKg = muscle?.let(::tenth),
            visceralFatLevel = visceral,
            source = MeasurementSource.PHOTO,
        )
    }

    private fun tenth(value: Double): Double = (value * 10).roundToLong() / 10.0

    fun proteinFor(bodyweightKg: Double, goal: Goal): Protein {
        val perKg = when (goal) {
            Goal.BUILD -> 1.8
            Goal.KEEP -> 1.6
            Goal.RECOMP, Goal.LOSE -> 2.2
        }
        val meal = (bodyweightKg * MEAL_PROTEIN_PER_KG).coerceIn(MEAL_PROTEIN_MIN, MEAL_PROTEIN_MAX)
        return Protein(dailyGrams = roundTo5(bodyweightKg * perKg), mealGrams = roundTo5(meal))
    }

    /**
     * Resting needs from lean mass when a body fat reading gives it, by the equation InBody prints its BMR with, otherwise
     * from height, age and sex (Mifflin-St Jeor). Null under 18, where the formulas and the deficits below are not meant to apply.
     */
    fun caloriesFor(bodyweightKg: Double, heightCm: Double?, leanMassKg: Double?, sex: Sex, age: Int, workoutsPerWeek: Double, goal: Goal): Calories? {
        if (age < ADULT_AGE) return null
        val resting = leanMassKg?.let { KATCH_MCARDLE_BASE + KATCH_MCARDLE_PER_KG * it }
            ?: heightCm?.let { 10 * bodyweightKg + 6.25 * it - 5 * age + if (sex == Sex.MALE) 5 else -161 }
            ?: return null
        val maintenance = resting * activityFactor(workoutsPerWeek)
        val planned = plannedWeeklyChangeKg(bodyweightKg, goal)
        val target = when {
            planned != null -> maintenance + planned * KCAL_PER_KG / 7
            goal == Goal.BUILD -> maintenance * (1 + BUILD_SURPLUS)
            else -> maintenance
        }
        val floor = if (sex == Sex.MALE) MIN_KCAL_MEN else MIN_KCAL
        return Calories(roundTo10(maintenance), roundTo10(target).coerceAtLeast(floor), fromLeanMass = leanMassKg != null)
    }

    /** The weekly weight change a losing goal plans for; null for goals that plan none. */
    fun plannedWeeklyChangeKg(bodyweightKg: Double, goal: Goal): Double? = when (goal) {
        Goal.LOSE -> -bodyweightKg * WEEKLY_LOSS
        Goal.RECOMP -> -bodyweightKg * RECOMP_WEEKLY_LOSS
        Goal.BUILD, Goal.KEEP -> null
    }

    private fun activityFactor(workoutsPerWeek: Double) = when {
        workoutsPerWeek >= 6 -> 1.725
        workoutsPerWeek >= 3 -> 1.55
        workoutsPerWeek >= 1 -> 1.375
        else -> 1.2
    }

    internal fun notesOf(profile: Profile, today: LocalDate): JsonArray {
        if (!profile.shareNotes) return JsonArray(emptyList())
        val lines = profile.memories.filter { it.isActive(today) }.sortedByDescending { it.updatedAt }.take(MAX_NOTES)
            .map { note -> "${note.key} (saved ${note.savedAt}${note.until?.let { ", until $it" }.orEmpty()}): ${note.text}" }
        val sent = buildList {
            var left = NOTES_BUDGET_CHARS
            for (line in lines) {
                if (line.length > left) continue
                add(JsonPrimitive(line))
                left -= line.length
            }
        }
        return JsonArray(sent)
    }

    private fun targetsRequest(profile: Profile, plan: List<Planned>, rules: List<RuleTarget>, unit: WeightUnit, today: LocalDate): List<ChatMessage>? {
        val adjustable = plan.zip(rules).filter { (_, rule) -> rule.weightKg != null || rule.reps != null }
        if (adjustable.isEmpty()) return null
        val payload = buildJsonObject {
            header(profile, unit, today)
            put("exercises", JsonArray(adjustable.map { (planned, rule) -> exerciseJson(planned, rule) }))
        }
        return messages(profile, TARGETS_TASK, payload, TARGETS_SCHEMA)
    }

    private fun targetOf(rule: RuleTarget, suggested: TargetReply?) = Target(
        exerciseId = rule.exerciseId,
        reason = rule.reason,
        weightKg = suggested?.weightKg?.let { clampWeight(it, rule) } ?: rule.weightKg,
        reps = suggested?.reps?.let { clampReps(it, rule) } ?: rule.reps,
        lastWeightKg = rule.lastWeightKg,
        note = suggested?.note?.takeIf { it.isNotBlank() },
    )

    /** The model's offers checked against every stored note, shared or not; for a key offered twice the last offer wins. */
    private fun offersOf(offered: List<RememberReply>, stored: List<Memory>): List<Remember> {
        val known = stored.associate { it.key to it.text }
        return offered.mapNotNull { offer ->
            val key = MemoryKeys.normalize(offer.key) ?: return@mapNotNull null
            val text = offer.text.trim()
            when {
                offer.forget -> known[key]?.let { Remember(key, it, forget = true) }
                text.isEmpty() || text == known[key] -> null
                else -> Remember(key, text, previous = known[key], days = offer.days?.coerceIn(1, MAX_NOTE_DAYS))
            }
        }.associateBy { it.key }.values.toList()
    }

    private fun clampWeight(kg: Double, rule: RuleTarget): Double? {
        val low = rule.minWeightKg ?: return null
        val high = rule.maxWeightKg ?: return null
        val last = rule.lastWeightKg
        val step = rule.stepKg
        val onGrid = if (last == null || step == null) kg else last + ((kg - last) / step).roundToInt() * step
        return (onGrid.coerceIn(low, high) * 1000).roundToLong() / 1000.0
    }

    private fun clampReps(reps: Int, rule: RuleTarget): Int? = rule.reps?.let { reps.coerceIn(repRange(it)) }

    private fun repRange(base: Int): IntRange {
        val high = (base + REP_SWING).coerceAtMost(maxOf(MAX_REPS, base))
        return (base - REP_SWING).coerceIn(1, high)..high
    }

    private fun messages(profile: Profile, task: String, payload: JsonObject, schema: JsonObject): List<ChatMessage> = listOf(
        ChatMessage("system", instructions(profile, task, schema)),
        ChatMessage("user", payload.toString()),
    )

    private fun instructions(profile: Profile, task: String, schema: JsonObject): String =
        "${SYSTEM.replace("{language}", if (profile.language == "ko") "Korean, in polite 해요체 such as 했어요 and 해 보세요" else "English")}\n\n$task\n\nReply as JSON shaped like ${shapeOf(schema)}"

    /** Some servers refuse two messages in a row from the same side or a conversation that opens with the assistant. */
    private fun alternating(turns: List<ChatMessage>): List<ChatMessage> = turns.dropWhile { it.role != "user" }.fold(emptyList()) { merged, turn ->
        val last = merged.lastOrNull()
        if (last?.role == turn.role) merged.dropLast(1) + last.copy(content = "${last.content}\n\n${turn.content}") else merged + turn
    }

    private fun JsonObjectBuilder.header(profile: Profile, unit: WeightUnit, date: LocalDate) {
        put("date", date.toString())
        put("unit", unit.name.lowercase())
        put("notes", notesOf(profile, date))
    }

    private fun JsonObjectBuilder.aboutLifter(profile: Profile) {
        put("goal", profile.goal.name.lowercase())
        profile.bodyweightKg?.let { put("bodyweightKg", it) }
        profile.sex?.let { put("sex", it.name.lowercase()) }
        if (profile.body.isNotEmpty()) {
            put("bodyMeasurements", JsonArray(profile.body.map { entry ->
                buildJsonObject {
                    put("date", entry.date)
                    entry.weightKg?.let { put("weightKg", it) }
                    entry.bodyFatPercent?.let { put("bodyFatPercent", it) }
                    entry.skeletalMuscleKg?.let { put("skeletalMuscleKg", it) }
                    entry.visceralFatLevel?.let { put("visceralFatLevel", it) }
                }
            }))
        }
        profile.calories?.let {
            put("maintenanceKcal", it.maintenance)
            put("targetKcal", it.target)
        }
    }

    private fun exerciseJson(planned: Planned, rule: RuleTarget) = buildJsonObject {
        put("id", planned.exercise.id)
        put("name", planned.name)
        put("kind", planned.exercise.kind.name.lowercase())
        put("rule", buildJsonObject {
            put("reason", rule.reason.name.lowercase())
            rule.weightKg?.let { put("weightKg", it) }
            rule.reps?.let { reps ->
                put("reps", reps)
                repRange(reps).let { put("minReps", it.first); put("maxReps", it.last) }
            }
            rule.minWeightKg?.let { put("minKg", it) }
            rule.maxWeightKg?.let { put("maxKg", it) }
            rule.stepKg?.let { put("stepKg", it) }
            rule.lastWeightKg?.let { put("lastKg", it) }
        })
        planned.restSeconds?.let { put("restSeconds", it) }
        if (planned.today.isNotEmpty()) {
            put("today", planned.today.joinToString(", ") { set -> setText(set, planned.exercise.kind) + if (set.isCompleted) " done" else "" })
            restsOf(planned.today)?.let { put("restsBetweenSets", it) }
        }
        put("recent", JsonArray(planned.sessions.map { session ->
            buildJsonObject { put("date", session.date.toString()); put("sets", setsText(session.sets, planned.exercise.kind)) }
        }))
    }

    private fun recentJson(recent: List<PastWorkout>) = JsonArray(recent.map { workout ->
        buildJsonObject {
            put("date", workout.date.toString())
            workout.effort?.let { put("effortOutOf10", it) }
            put("exercises", JsonArray(workout.exercises.map(::doneJson)))
        }
    })

    private fun doneJson(done: Done) = buildJsonObject {
        put("name", done.name)
        put("sets", setsText(done.sets, done.kind))
        restsOf(done.sets)?.let { put("restsBetweenSets", it) }
    }

    private fun setsText(sets: List<WorkoutSet>, kind: ExerciseKind): String = sets.joinToString(", ") { setText(it, kind) }

    private fun setText(set: WorkoutSet, kind: ExerciseKind): String =
        listOfNotNull(
            set.weightKg?.let { "${formatWeightNumber(it, WeightUnit.KG)}kg${weightNote(kind)}" },
            set.reps?.let { "$it reps" },
            set.durationSeconds?.let { "${it}s" },
            set.speedKmh?.let { "${formatKmh(it)}km/h" },
            set.distanceMeters?.let { "${it.roundToLong()}m" },
            set.level?.let { "level $it" },
            set.rpe?.let { "@RPE ${rpeText(it)}" },
            set.type.takeIf { it != SetType.NORMAL }?.name?.lowercase(),
        ).joinToString(" ")

    private fun weightNote(kind: ExerciseKind): String = when (kind) {
        ExerciseKind.ASSISTED_BODYWEIGHT -> " assistance"
        ExerciseKind.WEIGHTED_BODYWEIGHT -> " added"
        else -> ""
    }

    private fun restsOf(sets: List<WorkoutSet>): String? {
        val times = sets.mapNotNull { it.completedAt }.sorted()
        if (times.size < 2) return null
        return times.zipWithNext { a, b -> formatRest(Duration.between(a, b).seconds.toInt()) }.joinToString(", ")
    }

    private fun roundTo5(grams: Double): Int = ((grams / 5).roundToInt() * 5)

    private fun roundTo10(kcal: Double): Int = ((kcal / 10).roundToInt() * 10)

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
    private class WeekReply(val reply: String = "", val changes: List<RoutineChangeReply> = emptyList())

    @Serializable
    private class RoutineChangeReply(
        val routine: Long? = null,
        val action: String = "adjust",
        val id: String? = null,
        val name: String? = null,
        val sets: Int? = null,
        val reps: Int? = null,
        val minutes: Int? = null,
    )

    @Serializable
    private class ChangeReply(
        val action: String = "adjust",
        val id: String? = null,
        val name: String? = null,
        val sets: Int? = null,
        val weightKg: Double? = null,
        val reps: Int? = null,
        val minutes: Int? = null,
        val restSeconds: Int? = null,
    )

    @Serializable
    private class RememberReply(val key: String = "", val text: String = "", val forget: Boolean = false, val days: Int? = null)

    @Serializable
    private class SummaryReply(val summary: String = "")

    @Serializable
    internal class BodyReply(
        val date: String? = null,
        val weight: Double? = null,
        val bodyFatPercent: Double? = null,
        val skeletalMuscle: Double? = null,
        val massUnit: String? = null,
        val visceralFatLevel: Double? = null,
    )

    private const val CHAT_TURNS = 20
    private const val SUMMARY_BATCH = 10
    private const val MAX_RAW_TURNS = CHAT_TURNS + SUMMARY_BATCH
    private const val MAX_SUMMARY_CHARS = 600
    private const val MAX_NOTE_DAYS = 90
    private const val REP_SWING = 2
    private const val MAX_REPS = 30
    private const val MAX_SETS = 10
    private const val MAX_MINUTES = 180
    private const val MEAL_PROTEIN_PER_KG = 0.4
    private const val MEAL_PROTEIN_MIN = 20.0
    private const val MEAL_PROTEIN_MAX = 50.0
    private const val MEAL_IDEAS = 3
    private const val ADULT_AGE = 18
    private const val MIN_KCAL = 1_200
    private const val MIN_KCAL_MEN = 1_500

    /** Iraki et al. 2019: a 10 to 20% surplus; Helms et al. 2014: lose 0.5 to 1% of bodyweight a week. */
    private const val BUILD_SURPLUS = 0.10
    private const val WEEKLY_LOSS = 0.005

    /** Murphy and Koehler 2021: lean mass gains shrink as the deficit grows, and stop on average near 500 kcal a day. */
    private const val RECOMP_WEEKLY_LOSS = 0.0025
    private const val KCAL_PER_KG = 7_700
    private const val KATCH_MCARDLE_BASE = 370
    private const val KATCH_MCARDLE_PER_KG = 21.6
    private const val MAX_NOTES = 40
    private const val NOTES_BUDGET_CHARS = 2_000

    private const val SYSTEM = """You are the coach inside FitGymLog, a workout log. The lifter shares their own logged training and notes they asked you to remember, each with its key and the date it was saved. Be brief, specific and kind, and write in {language}. You are not a doctor: for pain beyond ordinary muscle soreness, suggest stopping that exercise and seeing a professional. Never suggest eating under $MIN_KCAL kcal a day, or under $MIN_KCAL_MEN for a lifter you know is a man, without medical supervision; maintenanceKcal and targetKcal, when present, are worked out by the app, so use them instead of your own estimate; never suggest skipping meals to lose weight, or supplements beyond protein powder, creatine and caffeine. Weights are in kilograms in the data; for weighted_bodyweight exercises the weight is added to the body, and for assisted_bodyweight exercises it is help from the machine, so less is harder. Reply with JSON only, in exactly the requested shape."""

    private const val TARGETS_TASK = """Set today's target for each exercise in the data. Each has the app's rule target and an allowed range. Keep the rule target unless the history or the notes give a reason, such as pain, repeated misses or reps far above the plan. Weights must stay between minKg and maxKg and move from lastKg in steps of stepKg; reps must stay between minReps and maxReps. For each exercise write a note of at most 12 words saying why."""

    private const val REVIEW_TASK = """Review the workout just finished in 2 to 4 short sentences, as notes: what went well and what to change next time, such as weight, rest or order. Mention rest only if the rest times show something. effortOutOf10, when present, is how hard the lifter felt the whole workout was; weigh it against the numbers, for example a hard rating on easy weights hints at fatigue. A set marked @RPE is how close to failure it was, 10 being no reps left and 8 two; easy sets in a hard workout point to the session as a whole, such as its length, rest or recovery."""

    private const val FOOD_TASK = """Also write foodSummary, one sentence about eating today given proteinGramsToday, targetKcal when present, and the goal, and mealIdeas, $MEAL_IDEAS simple meals of at most 6 words each that respect the food notes."""

    private const val BODY_TASK = """Read the body composition in this picture: a smart scale app, an InBody or similar result sheet, or a screenshot of one. Copy the numbers exactly as printed; never estimate or calculate. date is the measurement date as YYYY-MM-DD when shown, otherwise null. weight and skeletalMuscle are in massUnit, kg or lb as printed. skeletalMuscle is skeletal muscle mass only (InBody's SMM, 골격근량); leave it null when the picture shows only total muscle mass (근육량), lean or fat-free mass (제지방), or a muscle percentage. bodyFatPercent is the body fat percentage. visceralFatLevel is the visceral fat level or rating, a plain number such as InBody's 내장지방레벨; leave it null when only a visceral fat area in cm² is shown. Use null for anything not shown. If the picture holds several measurements, read the newest."""

    private const val WEEK_TASK = """Look back on the lifter's week, from week and workoutsThisWeek, where effortOutOf10 is how hard a workout felt when rated, in reply: 2 to 4 short sentences on how it went and what to focus on next week. In changes, offer changes to the routines for next week only when they clearly help, such as fewer sets after a week of missed reps or more when everything was easy: adjust an exercise of a routine by the routine's id and the exercise's id, with sets as the number of working sets, reps, or minutes per set for cardio; remove one by its id; or add one at the end of a routine by its name copied exactly from exercisesInApp, with sets and reps, or minutes for cardio. Weights are left to the app's progression rules. Leave fields a change does not use null, and changes empty when nothing should change."""

    private const val SUMMARY_TASK = """Update the running summary of the earlier conversation with these messages. Keep what matters for training, health, food and plans, and drop small talk. At most $MAX_SUMMARY_CHARS characters."""

    private const val CHAT_TASK = """Answer the lifter's message in reply. The data holds today's workout with rule limits, restSeconds as the planned rest after each set and, under today, the sets so far with done ones marked and restsBetweenSets, the time from finishing one set to finishing the next; recent workouts and their notes. During a workout, minutesIntoWorkout says how long it has run and restSecondsLeft, when present, how much of the current rest is left. While workoutInProgress is true, put changes to today's workout the lifter asks for, or that clearly help, in changes for the lifter to accept: adjust an exercise in todaysWorkout by id, with weightKg and reps within its rule limits, sets as the number of working sets, minutes per set for cardio, or restSeconds for the rest after each set, 0 to turn the timer off; remove one by id, which keeps the sets already done; or add one at the end by its name copied exactly from exercisesInApp, with sets, reps and restSeconds, or minutes per set for cardio, while its weights come from the lifter's last session. Leave fields a change does not use null. If the lifter states a fact worth remembering about injuries, food, schedule or goals, offer it in remember under a key like health/left-knee: the topic (health, food, schedule or goals), a slash and a short name. To change a note in the notes list, reuse its key with the new text; to drop one that is no longer true, return its key with forget true. For something that should pass, such as a sore knee or a busy week, set days to how long it should count (1 to $MAX_NOTE_DAYS); leave days null for lasting facts such as allergies or goals. earlierConversation, when present, summarizes older messages. Never invent a fact. Leave changes and remember empty when there is nothing to add.

About the app, for questions on how to do something: the Workout tab starts an empty workout or a routine, Programs adds ready-made routines (StrongLifts 5x5, full body, upper/lower, push/pull/legs), and New routine builds one, where cardio can plan minutes, the treadmill's speed and incline, or a machine's level. During a workout the weight is set by tapping plates onto the bar, and a Galaxy Fit band's music buttons complete sets. History holds finished workouts; tapping an exercise there shows its progress chart. Settings holds the weight unit, rest timer, the band, writing to Health Connect for Samsung Health, Google Drive sync, Hevy or Strong CSV import and backups. Stats shows weekly totals, the weekly check-in, the weight trend and Body composition, where measurements are typed in, read from a scale or InBody screenshot, or come from Health Connect. Coach settings holds the goal and body details. When suggesting exercises, prefer names from exercisesInApp; any other exercise is fine too, in reply only, and the lifter adds it with New exercise in the exercise list."""
}

/** A compact picture of a schema, for servers that ignore response_format and only read the prompt. */
internal fun shapeOf(schema: JsonElement): String {
    val node = schema.jsonObject
    node["properties"]?.let { properties ->
        return properties.jsonObject.entries.joinToString(",", "{", "}") { (name, value) -> "\"$name\":${shapeOf(value)}" }
    }
    node["items"]?.let { return "[${shapeOf(it)}]" }
    node["enum"]?.let { values -> return values.jsonArray.joinToString("|") { "\"${it.jsonPrimitive.content}\"" } }
    return when (val type = node.getValue("type")) {
        is JsonArray -> type.joinToString("|") { it.jsonPrimitive.content }
        else -> type.jsonPrimitive.content
    }
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

private fun enumOf(vararg values: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
}

private fun arrayOf(items: JsonElement) = buildJsonObject { put("type", "array"); put("items", items) }

private val TARGETS_SCHEMA = objectSchema(
    "targets" to arrayOf(objectSchema("id" to type("string"), "weightKg" to type("number", "null"), "reps" to type("integer", "null"), "note" to type("string"))),
)

private val REVIEW_SCHEMA = objectSchema("notes" to type("string"))

private val FOOD_REVIEW_SCHEMA = objectSchema(
    "notes" to type("string"),
    "foodSummary" to type("string"),
    "mealIdeas" to arrayOf(type("string")),
)

private val CHAT_SCHEMA = objectSchema(
    "reply" to type("string"),
    "changes" to arrayOf(
        objectSchema(
            "action" to enumOf("adjust", "add", "remove"),
            "id" to type("string", "null"),
            "name" to type("string", "null"),
            "sets" to type("integer", "null"),
            "weightKg" to type("number", "null"),
            "reps" to type("integer", "null"),
            "minutes" to type("integer", "null"),
            "restSeconds" to type("integer", "null"),
        ),
    ),
    "remember" to arrayOf(objectSchema("key" to type("string"), "text" to type("string"), "forget" to type("boolean"), "days" to type("integer", "null"))),
)

private val WEEK_SCHEMA = objectSchema(
    "reply" to type("string"),
    "changes" to arrayOf(
        objectSchema(
            "routine" to type("integer"),
            "action" to enumOf("adjust", "add", "remove"),
            "id" to type("string", "null"),
            "name" to type("string", "null"),
            "sets" to type("integer", "null"),
            "reps" to type("integer", "null"),
            "minutes" to type("integer", "null"),
        ),
    ),
)

private val SUMMARY_SCHEMA = objectSchema("summary" to type("string"))

private val BODY_SCHEMA = objectSchema(
    "date" to type("string", "null"),
    "weight" to type("number", "null"),
    "bodyFatPercent" to type("number", "null"),
    "skeletalMuscle" to type("number", "null"),
    "visceralFatLevel" to type("number", "null"),
    "massUnit" to type("string", "null"),
)
