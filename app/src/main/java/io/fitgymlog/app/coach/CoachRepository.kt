package io.fitgymlog.app.coach

import android.content.Context
import io.fitgymlog.app.data.ExerciseRepository
import io.fitgymlog.app.data.SettingsRepository
import io.fitgymlog.app.data.WorkoutRepository
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.ui.name
import io.fitgymlog.app.workout.ActiveWorkout
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first

class CoachRepository(
    private val context: Context,
    val settings: CoachSettingsRepository,
    val store: CoachStore,
    private val vault: KeyVault,
    private val workouts: WorkoutRepository,
    private val exercises: ExerciseRepository,
    private val appSettings: SettingsRepository,
) {
    private val client = ChatClient()

    val hasKey: Boolean get() = vault.hasKey

    fun saveKey(apiKey: String?) = vault.write(apiKey)

    /** With [apiKey] null, the saved key is used. */
    suspend fun models(baseUrl: String, apiKey: String?): List<String> = client.models(Connection(baseUrl, apiKey ?: savedKey()))

    suspend fun targets(active: ActiveWorkout): List<Target> =
        Coach.targets(completer(CoachTask.TARGETS), profile(), plan(active), unit(), LocalDate.now())

    suspend fun ruleTargets(active: ActiveWorkout): List<Target> = Coach.ruleTargets(plan(active), unit()).map { rule ->
        Target(rule.exerciseId, rule.reason, rule.weightKg, rule.reps, rule.lastWeightKg, note = null)
    }

    suspend fun review(workout: Workout): Review {
        val coach = settings.settings.value
        val review = Coach.review(
            completer = completer(CoachTask.REVIEW),
            profile = profile(),
            done = done(workout),
            recent = recent(except = workout.uuid),
            unit = unit(),
            today = dateOf(workout.startedAt),
            workoutUuid = workout.uuid,
            withFood = coach.foodGuide && coach.bodyweightKg != null,
        )
        store.keepReview(review)
        return review
    }

    /** The user's message is kept even if the reply fails, so it can be sent again. */
    suspend fun send(message: String, active: ActiveWorkout?) {
        val history = store.data.value.chat
        store.update { it.copy(chat = it.chat + ChatTurn(fromUser = true, text = message)) }
        val today = active?.let { plan(it) }.orEmpty().let { plan -> plan.zip(Coach.ruleTargets(plan, unit())) }
        val reply = Coach.chat(completer(CoachTask.CHAT), profile(), history, message, today, recent(except = null), unit(), LocalDate.now())
        store.update { it.copy(chat = it.chat + reply) }
    }

    suspend fun preview(active: ActiveWorkout?): String = Coach.preview(profile(), active?.let { plan(it) }.orEmpty(), unit(), LocalDate.now())

    private suspend fun plan(active: ActiveWorkout): List<Planned> = active.exercises.map { exercise ->
        val info = exercises.find(exercise.exerciseId)
        val sessions = workouts.recentWith(exercise.exerciseId, RECENT_SESSIONS).first().map { workout ->
            PastSession(dateOf(workout.startedAt), workout.exercises.filter { it.exerciseId == exercise.exerciseId }.flatMap { it.sets })
        }
        Planned(info, info.name(context), sessions)
    }

    private suspend fun recent(except: String?): List<Pair<LocalDate, List<Done>>> =
        workouts.recentFinished(RECENT_WORKOUTS + 1).filter { it.uuid != except }.take(RECENT_WORKOUTS).map { dateOf(it.startedAt) to done(it) }

    private fun done(workout: Workout): List<Done> = workout.exercises.map { Done(exercises.find(it.exerciseId).name(context), it.sets) }

    private fun profile(): Profile {
        val coach = settings.settings.value
        val notes = if (coach.shareNotes) store.data.value.memories else emptyList()
        return Profile(coach.bodyweightKg, coach.goal, notes, context.resources.configuration.locales[0].language)
    }

    private fun completer(task: CoachTask): Completer {
        val coach = settings.settings.value
        val connection = Connection(coach.baseUrl, savedKey())
        val model = coach.modelFor(task)
        return Completer { messages, schemaName, schema -> client.complete(connection, model, messages, schemaName, schema) }
    }

    private fun savedKey(): String = vault.read() ?: throw CoachException(CoachFailure.UNAUTHORIZED, "No API key saved")

    private fun unit() = appSettings.settings.value.unit

    private fun dateOf(instant: Instant): LocalDate = instant.atZone(ZoneId.systemDefault()).toLocalDate()

    private companion object {
        const val RECENT_SESSIONS = 3
        const val RECENT_WORKOUTS = 5
    }
}
