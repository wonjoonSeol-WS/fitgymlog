package io.fitgymlog.app.coach

import android.app.LocaleManager
import android.content.Context
import android.net.Uri
import android.util.Log
import io.fitgymlog.app.LOG_TAG
import io.fitgymlog.app.catching
import io.fitgymlog.app.checkin.WeekSummary
import io.fitgymlog.app.data.ExerciseRepository
import io.fitgymlog.app.data.RoutineRepository
import io.fitgymlog.app.data.SettingsRepository
import io.fitgymlog.app.data.WorkoutRepository
import io.fitgymlog.app.health.HealthConnectBody
import io.fitgymlog.app.model.RoutineExercise
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.defaultSets
import io.fitgymlog.app.model.defaultRestSeconds
import io.fitgymlog.app.ui.name
import io.fitgymlog.app.workout.ActiveWorkout
import io.fitgymlog.app.workout.warmedUp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TodaysTargets(
    val workoutUuid: String,
    val exerciseIds: Set<String>,
    val targets: List<Target>,
    val error: Throwable?,
    val applied: Set<String> = emptySet(),
    val dismissed: Boolean = false,
)

sealed interface Pending {
    data object Running : Pending
    data class Failed(val error: Throwable) : Pending
}

class CoachRepository(
    private val context: Context,
    val settings: CoachSettingsRepository,
    val store: CoachStore,
    private val vault: KeyVault,
    private val workouts: WorkoutRepository,
    private val routines: RoutineRepository,
    private val exercises: ExerciseRepository,
    private val appSettings: SettingsRepository,
    val healthBody: HealthConnectBody,
    private val scope: CoroutineScope,
) {
    private val client = ChatClient()
    private val loadingTargets = Mutex()
    private val replying = Mutex()
    private val applying = Mutex()
    private val summarizing = Mutex()
    private val replies = SupervisorJob(scope.coroutineContext[Job])
    private val _todaysTargets = MutableStateFlow<TodaysTargets?>(null)
    private val _chat = MutableStateFlow<Pending?>(null)
    private val _week = MutableStateFlow<Pending?>(null)
    private val _reviews = MutableStateFlow<Map<String, Pending>>(emptyMap())
    private val _bodyRead = MutableStateFlow<BodyRead?>(null)
    private val readingHealth = Mutex()
    @Volatile private var healthReadings: Pair<Long, List<BodyMeasurement>>? = null
    private val healthGeneration = AtomicInteger()

    val todaysTargets: StateFlow<TodaysTargets?> = _todaysTargets.asStateFlow()
    val chatPending: StateFlow<Pending?> = _chat.asStateFlow()
    val weekPending: StateFlow<Pending?> = _week.asStateFlow()
    val reviewsPending: StateFlow<Map<String, Pending>> = _reviews.asStateFlow()
    val bodyRead: StateFlow<BodyRead?> = _bodyRead.asStateFlow()
    val keySavedFor: StateFlow<String?> = vault.savedFor
    val ready: StateFlow<Boolean> = combine(settings.settings, vault.savedFor) { coach, savedFor -> coachReady(coach, savedFor) }
        .stateIn(scope, SharingStarted.Eagerly, coachReady(settings.settings.value, vault.savedFor.value))

    /** Checks the key against [baseUrl] before keeping either, and keeps them together; run it where leaving the screen can't cut it short. */
    suspend fun connect(baseUrl: String, typedKey: String?): List<String> {
        val typed = typedKey?.takeIf { it.isNotBlank() }
        val key = typed ?: vault.read(baseUrl) ?: "".takeIf { Connection.keyOptional(baseUrl) } ?: throw CoachException(CoachFailure.UNAUTHORIZED, "No key for this address")
        val models = client.models(Connection(baseUrl, key))
        typed?.let { vault.write(it, baseUrl) }
        settings.update { current ->
            if (current.baseUrl == baseUrl) {
                current
            } else {
                current.copy(baseUrl = baseUrl, model = current.model.takeIf { it in models }.orEmpty(), taskModels = current.taskModels.filterValues { it in models })
            }
        }
        return models
    }

    fun removeKey() = vault.clear()

    /** Loads once per workout and set of exercises; [force] asks again after a failure. */
    suspend fun loadTargets(active: ActiveWorkout, force: Boolean = false) = loadingTargets.withLock {
        val uuid = active.workout.uuid
        val ids = active.exercises.map { it.exerciseId }.toSet()
        val loaded = _todaysTargets.value?.takeIf { it.workoutUuid == uuid }
        if (loaded != null && loaded.exerciseIds == ids && !force) return@withLock
        val plan = plan(active)
        val result = catching { Coach.targets(completer(CoachTask.TARGETS), profile(withBody = false), plan, unit(), LocalDate.now()) }
        val targets = result.getOrElse { Coach.ruleOnly(plan, unit()) }.filter { it.weightKg != null || it.reps != null }
        _todaysTargets.update { current ->
            val kept = current?.takeIf { it.workoutUuid == uuid }
            TodaysTargets(uuid, ids, targets, result.exceptionOrNull(), kept?.applied.orEmpty(), kept?.dismissed == true)
        }
    }

    fun markApplied(exerciseIds: Collection<String>) = _todaysTargets.update { it?.copy(applied = it.applied + exerciseIds) }

    fun dismissTargets() = _todaysTargets.update { it?.copy(dismissed = true) }

    /** Does nothing while a review of the same workout is on its way, or when [onlyIfMissing] and one is stored. */
    fun requestReview(workout: Workout, onlyIfMissing: Boolean = false) {
        val before = _reviews.getAndUpdate { if (it[workout.uuid] == Pending.Running) it else it + (workout.uuid to Pending.Running) }
        if (before[workout.uuid] == Pending.Running) return
        scope.launch {
            val result = catching {
                if (!onlyIfMissing || store.current().reviews.none { it.workoutUuid == workout.uuid }) review(workout)
            }
            _reviews.update { pending -> result.exceptionOrNull()?.let { pending + (workout.uuid to Pending.Failed(it)) } ?: (pending - workout.uuid) }
        }
    }

    /** The message shows at once and is kept even if its reply fails; replies come one at a time, each to the newest message. */
    fun send(message: String) {
        val text = message.trim()
        if (text.isEmpty()) return
        _chat.value = Pending.Running
        scope.launch(replies) {
            catching {
                store.update { it.copy(chat = it.chat + ChatTurn(fromUser = true, text = text, at = System.currentTimeMillis())) }
                replying.withLock { reply() }
            }.onFailure { _chat.value = Pending.Failed(it) }
        }
    }

    fun retryReply() {
        scope.launch(replies) {
            catching { replying.withLock { reply() } }.onFailure { _chat.value = Pending.Failed(it) }
        }
    }

    /** Changes the workout no longer accepts, or one that ended meanwhile, are left unapplied. */
    suspend fun applyChanges(turnAt: Long, indices: Set<Int>) = applying.withLock {
        val changes = store.current().chat.reply(turnAt)?.changes ?: return@withLock
        val active = workouts.active.value ?: return@withLock
        val chosen = changes.withIndex().filter { (index, change) -> index in indices && active.accepts(change) }.associate { it.index to it.value }
        if (chosen.isEmpty()) return@withLock
        val known = exercises.exercises.value.map { it.id }.toSet()
        val added = chosen.values.filter { it.action == ChangeAction.ADD && it.exerciseId in known }.associate { it.exerciseId to newBlock(it) }
        var applied = emptySet<Int>()
        workouts.update { current -> current.applying(chosen, added, Instant.now()).also { applied = it.indices }.workout }
        if (applied.isEmpty()) return@withLock
        store.update { data -> data.copy(chat = data.chat.updatedReply(turnAt) { it.copy(changes = it.changes.markedApplied(applied)) }) }
    }

    private suspend fun newBlock(change: Change): NewBlock {
        val exercise = exercises.find(change.exerciseId)
        val current = appSettings.settings.value
        val previous = workouts.previousSets(change.exerciseId)
        val sets = current.warmedUp(exercise, setsToAdd(change, exercise.kind, previous), previous)
        return NewBlock(sets, restToAdd(change, exercise.kind, current.defaultRestSeconds))
    }

    suspend fun saveMemory(turnAt: Long, index: Int) = store.update { data ->
        val offer = data.chat.reply(turnAt)?.remember?.getOrNull(index)?.takeIf { !it.done } ?: return@update data
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val until = offer.days?.let { untilFor(it, today) } ?: data.memories.firstOrNull { it.key == offer.key }?.until
        val saved = if (offer.forget) data.forgetting(offer.key, now) else data.remembering(offer.key, offer.text, until, now, today)
        saved.copy(chat = saved.chat.updatedReply(turnAt) { it.copy(remember = it.remember.updated(index) { r -> r.copy(done = true) }) })
    }

    suspend fun clearChat() {
        replies.cancelChildren()
        store.update { it.copy(chat = emptyList(), chatSummary = "", summarizedUntil = System.currentTimeMillis()) }
        _chat.value = null
    }

    suspend fun preview(): String? = workouts.active.value?.let { Coach.preview(profile(withBody = false), plan(it), unit(), LocalDate.now()) }

    private suspend fun review(requested: Workout) {
        // The screen's copy can predate an effort rating stored a moment ago, which is what the review waited for.
        val workout = workouts.observe(requested.id).first() ?: requested
        val date = dateOf(workout.startedAt)
        val review = Coach.review(
            completer = completer(CoachTask.REVIEW),
            profile = profile(),
            workout = PastWorkout(date, done(workout), workout.effort),
            recent = recent(before = workout.startedAt),
            unit = unit(),
            workoutUuid = workout.uuid,
            withFood = settings.settings.value.foodGuide && date == LocalDate.now(),
        )
        store.keepReview(review)
    }

    private suspend fun reply() {
        val data = store.current()
        val asked = data.chat.lastOrNull()?.takeIf { it.fromUser } ?: return
        _chat.value = Pending.Running
        val active = workouts.active.value
        val now = Instant.now()
        val result = catching {
            Coach.chat(
                completer = completer(CoachTask.CHAT),
                profile = profile(),
                history = data.unsummarized().dropLast(1),
                message = asked.text,
                plan = active?.let { plan(it, withToday = true) }.orEmpty(),
                recent = recent(before = now),
                unit = unit(),
                today = LocalDate.now(),
                earlier = data.chatSummary,
                exercisesInApp = exercises.exercises.value.map { NamedExercise(it, it.name(context)) },
                inProgress = active?.let { inProgress(it, now) },
            )
        }
        result.onSuccess { turn ->
            val stamped = turn.copy(at = System.currentTimeMillis(), changes = turn.changes.map { it.copy(workoutUuid = active?.workout?.uuid) })
            store.update { it.answered(asked, stamped) }
            scope.launch { summarizeIfDue() }
        }
        _chat.value = result.exceptionOrNull()?.takeIf { store.current().chat.lastOrNull() == asked }?.let { Pending.Failed(it) }
    }

    /** Best effort: if it fails, the older turns are folded in with the next batch. */
    private suspend fun summarizeIfDue() {
        if (!summarizing.tryLock()) return
        try {
            val data = store.current()
            val due = Coach.toSummarize(data.chat, data.summarizedUntil).ifEmpty { return }
            val summary = catching { Coach.summarize(completer(CoachTask.CHAT), profile(withBody = false), data.chatSummary, due) { exercises.find(it).name(context) } }.getOrNull() ?: return
            store.update { it.summarized(from = data.summarizedUntil, summary = summary, until = due.last().at) }
        } finally {
            summarizing.unlock()
        }
    }

    private suspend fun plan(active: ActiveWorkout, withToday: Boolean = false): List<Planned> {
        // The routine's reps are the bottom of each rep range; the sets lose them once a target with more reps is applied.
        val routine = active.workout.routineId?.let { routines.find(it) }
        return active.exercises.map { it.exerciseId }.distinct().map { id ->
            val info = exercises.find(id)
            val sessions = workouts.recentWith(id, Progression.HISTORY_SESSIONS).first().map { workout ->
                PastSession(dateOf(workout.startedAt), workout.exercises.filter { it.exerciseId == id }.flatMap { it.sets })
            }
            val plannedReps = routine?.exercises?.firstOrNull { it.exerciseId == id }?.reps
            if (withToday) {
                Planned(info, info.name(context), sessions, active.exercises.filter { it.exerciseId == id }.flatMap { it.sets }, active.restSecondsOf(id), plannedReps)
            } else {
                Planned(info, info.name(context), sessions, plannedReps = plannedReps)
            }
        }
    }

    private fun inProgress(active: ActiveWorkout, now: Instant) = InProgress(
        minutes = Duration.between(active.workout.startedAt, now).toMinutes(),
        restLeftSeconds = active.restClock(now)?.left?.seconds?.takeIf { it > 0 },
    )

    private suspend fun recent(before: Instant): List<PastWorkout> =
        workouts.finishedBefore(before, RECENT_WORKOUTS).map { PastWorkout(dateOf(it.startedAt), done(it), it.effort) }

    private fun done(workout: Workout): List<Done> = workout.exercises.map { exercise ->
        val info = exercises.find(exercise.exerciseId)
        Done(info.name(context), info.kind, exercise.sets)
    }

    /** Today's targets, summaries and picture reads use no body data, so they skip reading it. */
    private suspend fun profile(withBody: Boolean = true): Profile {
        val coach = settings.settings.value
        val memories = store.current().memories
        if (!withBody) return Profile(coach.bodyweightKg, coach.goal, memories, coach.shareNotes, language(), coach.sex)
        val today = LocalDate.now()
        val history = bodyHistory()
        val bodyweight = currentBodyweight(history, coach.bodyweightKg, today)
        return Profile(bodyweight, coach.goal, memories, coach.shareNotes, language(), coach.sex, caloriesFor(bodyweight, history, today), recentBody(history, today))
    }

    /** Saved measurements with Health Connect's weight and body fat filled in, unless reading it is off or not allowed. */
    suspend fun bodyHistory(): List<BodyMeasurement> = store.current().let { data -> bodyHistory(data.measurements, healthReadings(), data.removedMeasurements.keys) }

    private suspend fun healthReadings(): List<BodyMeasurement> = readingHealth.withLock {
        if (!appSettings.settings.value.readBodyFromHealth) return@withLock emptyList()
        val now = System.currentTimeMillis()
        val generation = healthGeneration.get()
        healthReadings?.takeIf { now - it.first < HEALTH_READINGS_MILLIS }?.second
            ?: catching { healthBody.since(Instant.now().minus(Duration.ofDays(BODY_HISTORY_DAYS))) }
                .onSuccess { if (generation == healthGeneration.get()) healthReadings = now to it }
                .onFailure { Log.w(LOG_TAG, "Reading body measurements from Health Connect failed", it) }
                .getOrDefault(emptyList())
    }

    /** After access to Health Connect changes, the next read goes to it rather than to what was read before. */
    fun forgetHealthReadings() {
        healthGeneration.incrementAndGet()
        healthReadings = null
    }

    /** Runs where leaving or rotating the screen can't cut it short; the result waits in [bodyRead] until [clearBodyRead]. */
    fun readBodyPhoto(picture: Uri) {
        if (_bodyRead.value == BodyRead.Reading) return
        _bodyRead.value = BodyRead.Reading
        scope.launch {
            val image = catching { imageDataUrl(context, picture) }.getOrElse {
                _bodyRead.value = BodyRead.Unreadable
                return@launch
            }
            _bodyRead.value = catching { Coach.readBody(completer(CoachTask.CHAT), profile(withBody = false), image, LocalDate.now()) }
                .fold({ found -> found?.let(BodyRead::Found) ?: BodyRead.NothingFound }, { BodyRead.Failed(it) })
        }
    }

    fun clearBodyRead() {
        _bodyRead.value = null
    }

    suspend fun calories(history: List<BodyMeasurement>): Calories? {
        val today = LocalDate.now()
        return caloriesFor(currentBodyweight(history, settings.settings.value.bodyweightKg, today), history, today)
    }

    private suspend fun caloriesFor(bodyweightKg: Double?, history: List<BodyMeasurement>, today: LocalDate): Calories? {
        val coach = settings.settings.value
        val weight = bodyweightKg ?: return null
        val sex = coach.sex ?: return null
        val born = coach.birthDate ?: return null
        val logged = workouts.countFinishedSince(Instant.now().minus(Duration.ofDays(7L * ACTIVITY_WEEKS)))
        val age = Period.between(born, today).years
        return Coach.caloriesFor(weight, coach.heightCm, recentLeanMassKg(history, today), sex, age, logged.toDouble() / ACTIVITY_WEEKS, coach.goal)
    }

    private fun language(): String =
        context.getSystemService(LocaleManager::class.java).applicationLocales.get(0)?.language ?: context.resources.configuration.locales[0].language

    private suspend fun completer(task: CoachTask): Completer {
        val coach = settings.settings.value
        val key = vault.read(coach.baseUrl) ?: "".takeIf { Connection.keyOptional(coach.baseUrl) } ?: throw CoachException(CoachFailure.UNAUTHORIZED, "No API key for this address")
        val connection = Connection(coach.baseUrl, key)
        val model = coach.modelFor(task)
        return Completer { messages, schemaName, schema -> client.complete(connection, model, messages, schemaName, schema) }
    }

    private fun unit() = appSettings.settings.value.unit

    private fun dateOf(instant: Instant): LocalDate = instant.atZone(ZoneId.systemDefault()).toLocalDate()

    /** Runs where leaving the screen can't cut it short; the review waits in the store. */
    fun lookBackOn(summary: WeekSummary) {
        if (_week.value == Pending.Running) return
        _week.value = Pending.Running
        scope.launch {
            val result = catching {
                val week = workouts.history.first().filter { dateOf(it.startedAt) in summary.start..summary.end }.sortedBy { it.startedAt }
                Coach.week(
                    completer = completer(CoachTask.REVIEW),
                    profile = profile(),
                    summary = summary,
                    nameOf = { exercises.find(it).name(context) },
                    workouts = week.map { PastWorkout(dateOf(it.startedAt), done(it), it.effort) },
                    routines = routines.all().map { routine ->
                        RoutinePlan(routine.id, routine.name, routine.exercises.map { planned -> exercises.find(planned.exerciseId).let { NamedExercise(it, it.name(context)) } to planned })
                    },
                    exercisesInApp = exercises.exercises.value.map { NamedExercise(it, it.name(context)) },
                    unit = unit(),
                    today = LocalDate.now(),
                )
            }
            result.onSuccess { store.keepWeek(it) }
            _week.value = result.exceptionOrNull()?.let { Pending.Failed(it) }
        }
    }

    suspend fun applyWeekChanges(weekStart: String, indices: Set<Int>) = applying.withLock {
        val review = store.current().weeks.find { it.weekStart == weekStart } ?: return@withLock
        val chosen = indices.mapNotNull { index -> review.changes.getOrNull(index)?.let { index to it } }.toMap()
        val applied = chosen.entries.groupBy { it.value.routineId }.flatMap { (routineId, entries) ->
            val routine = routines.find(routineId) ?: return@flatMap emptySet()
            val picked = entries.associate { it.key to it.value }
            val added = picked.values.filter { it.action == ChangeAction.ADD }.associate { it.exerciseId to newRoutineExercise(it) }
            val result = routine.applying(picked, added)
            if (result.indices.isNotEmpty()) routines.save(result.routine)
            result.indices
        }.toSet()
        if (applied.isNotEmpty()) store.update { it.weekApplied(weekStart, applied, System.currentTimeMillis()) }
    }

    private fun newRoutineExercise(change: RoutineChange): RoutineExercise {
        val kind = exercises.find(change.exerciseId).kind
        return RoutineExercise(
            exerciseId = change.exerciseId,
            sets = change.sets ?: kind.defaultSets(),
            reps = change.reps,
            weightKg = null,
            restSeconds = kind.defaultRestSeconds(appSettings.settings.value.defaultRestSeconds),
            durationSeconds = change.minutes?.let { it * 60 },
        )
    }

    private companion object {
        const val RECENT_WORKOUTS = 5
        const val ACTIVITY_WEEKS = 4
        const val BODY_HISTORY_DAYS = 365L
        const val HEALTH_READINGS_MILLIS = 5 * 60 * 1000L

    }
}

private fun List<ChatTurn>.reply(at: Long): ChatTurn? = firstOrNull { !it.fromUser && it.at == at }

private fun List<ChatTurn>.updatedReply(at: Long, change: (ChatTurn) -> ChatTurn): List<ChatTurn> = map { if (!it.fromUser && it.at == at) change(it) else it }

private fun <T> List<T>.updated(index: Int, change: (T) -> T): List<T> = mapIndexed { i, item -> if (i == index) change(item) else item }

private fun List<Change>.markedApplied(indices: Set<Int>): List<Change> = mapIndexed { i, change -> if (i in indices) change.copy(applied = true) else change }

internal fun coachReady(coach: CoachSettings, keySavedFor: String?) =
    coach.enabled && coach.isConnected && (keySavedFor == coach.baseUrl || Connection.keyOptional(coach.baseUrl))
