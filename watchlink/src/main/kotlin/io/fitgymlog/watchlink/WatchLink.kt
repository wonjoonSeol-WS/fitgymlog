package io.fitgymlog.watchlink

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The phone keeps the workout; the watch shows it and asks for changes. The phone publishes [WatchState]
 * as one data item, which the Data Layer only sends on when it changes, and the watch sends [WatchCommand]
 * as messages. Names and set lines arrive already written in the phone's language and unit.
 */
object WatchPaths {
    const val STATE = "/fitgymlog/state"
    const val COMMAND = "/fitgymlog/command"

    /** Declared by the phone app in wear.xml, so the watch can find a phone that has it. */
    const val PHONE_CAPABILITY = "fitgymlog_phone"
}

@Serializable
data class WatchState(
    val workout: WatchWorkout? = null,
    val routines: List<WatchRoutine> = emptyList(),
    /** The phone's clock when it sent this, so a watch whose clock has drifted can count rest and time in the phone's. */
    val sentAtMillis: Long = 0,
)

@Serializable
data class WatchRoutine(val id: Long, val name: String, val summary: String)

@Serializable
data class WatchSetRef(val exercise: Int, val set: Int)

/** The weight unit the user enters weights in; weights travel in kilograms either way. */
@Serializable
data class WatchUnit(val label: String, val kgPerUnit: Double) {
    fun toDisplay(kg: Double): Double = kg / kgPerUnit

    fun toKg(display: Double): Double = display * kgPerUnit
}

@Serializable
data class WatchWorkout(
    val title: String,
    val startedAtMillis: Long,
    val exercises: List<WatchExercise>,
    val unit: WatchUnit,
    /** The exercise the phone and the band are on. */
    val focus: Int,
    /** What the check button logs; during rest, the set after the rest. */
    val next: WatchSetRef? = null,
    val restEndsAtMillis: Long? = null,
    val restLengthMillis: Long? = null,
) {
    val setsDone: Int get() = exercises.sumOf { exercise -> exercise.sets.count { it.done } }
    val setsTotal: Int get() = exercises.sumOf { it.sets.size }

    fun restLeftMillis(nowMillis: Long): Long? = restEndsAtMillis?.let { (it - nowMillis).coerceAtLeast(0) }

    fun exerciseAt(ref: WatchSetRef): WatchExercise? = exercises.getOrNull(ref.exercise)

    fun setAt(ref: WatchSetRef): WatchSetRow? = exerciseAt(ref)?.sets?.getOrNull(ref.set)
}

@Serializable
data class WatchExercise(
    val name: String,
    val hasWeight: Boolean,
    val hasReps: Boolean,
    /** The exercise's usual increment, in the display unit: 2.5 kg for a barbell, 5 lb, 2 kg dumbbells. */
    val weightStep: Double,
    val sets: List<WatchSetRow>,
)

@Serializable
data class WatchSetRow(
    /** "60 kg x 5", or "20 min" for cardio. */
    val line: String,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val done: Boolean = false,
    val warmup: Boolean = false,
)

@Serializable
sealed interface WatchCommand {
    /** The commands that change the workout in progress, as opposed to starting, finishing or waking the phone. */
    @Serializable
    sealed interface WorkoutEdit : WatchCommand

    /**
     * Logs a set from the dial screen: a null value leaves that field as it is. Later open sets take the new weight
     * too, as when the whole 5x5 goes up. With [complete] the set is also ticked, which starts the rest.
     */
    @Serializable @SerialName("log")
    data class Log(val ref: WatchSetRef, val weightKg: Double? = null, val reps: Int? = null, val complete: Boolean) : WorkoutEdit

    @Serializable @SerialName("focus")
    data class Focus(val exercise: Int) : WorkoutEdit

    @Serializable @SerialName("rest")
    data class AdjustRest(val seconds: Int) : WorkoutEdit

    @Serializable @SerialName("skip_rest")
    data object SkipRest : WorkoutEdit

    @Serializable @SerialName("start")
    data class StartRoutine(val routineId: Long) : WatchCommand

    @Serializable @SerialName("finish")
    data object Finish : WatchCommand

    /** Sent when the watch app opens, so a phone app that hasn't run since boot wakes and publishes. */
    @Serializable @SerialName("hello")
    data object Hello : WatchCommand
}

object WatchCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun encode(state: WatchState): ByteArray = json.encodeToString(WatchState.serializer(), state).encodeToByteArray()

    fun decodeState(bytes: ByteArray): WatchState? = runCatching { json.decodeFromString(WatchState.serializer(), bytes.decodeToString()) }.getOrNull()

    fun encode(command: WatchCommand): ByteArray = json.encodeToString(WatchCommand.serializer(), command).encodeToByteArray()

    /** Null for a command this version doesn't know, such as one from a newer watch app. */
    fun decodeCommand(bytes: ByteArray): WatchCommand? = runCatching { json.decodeFromString(WatchCommand.serializer(), bytes.decodeToString()) }.getOrNull()
}

/**
 * The values a dial offers for a weight, in the display unit: every step from zero up to well past the current
 * weight. A weight off the steps, such as 61.25 kg on a 2.5 kg dial, gets a stop of its own so it isn't rounded away.
 */
fun weightStops(current: Double?, step: Double, ceiling: Double): List<Double> {
    val top = maxOf(ceiling, (current ?: 0.0) * 2)
    val count = (top / step).toInt()
    val stops = (0..count).map { roundStop(it * step) }
    val exact = current?.let(::roundStop)
    return if (exact == null || exact in stops) stops else (stops + exact).sorted()
}

/** Weights compare as dial stops at a thousandth, so 0.1 + 0.2 and 0.3 land on the same stop. */
fun roundStop(value: Double): Double = Math.round(value * 1000) / 1000.0
