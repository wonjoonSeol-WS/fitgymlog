package io.fitgymlog.app.transfer

import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.WorkoutSet
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Hevy's workout export: one row per set. Text follows the app's display language, and the weight
 * and distance columns follow its unit setting (weight_kg or weight_lbs, distance_km or distance_miles).
 */
object HevyCsv {
    private val header = listOf(
        "title", "start_time", "end_time", "description", "exercise_title", "superset_id", "exercise_notes",
        "set_index", "set_type", "weight_kg", "reps", "distance_km", "duration_seconds", "rpe",
    )
    private val exportDate = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)

    fun looksLike(text: String): Boolean {
        val columns = Csv.read(text.lineSequence().firstOrNull().orEmpty()).firstOrNull().orEmpty().map { it.trim().lowercase() }
        return "exercise_title" in columns && "start_time" in columns
    }

    fun parse(text: String): ParseResult {
        val errors = mutableListOf<RowError>()
        val rows = csvRecords(text).mapNotNull { record ->
            parseRow(record).onFailure { errors += RowError(record.rowNumber, it.message.orEmpty()) }.getOrNull()
        }
        return ParseResult(groupRows(rows), errors)
    }

    private fun parseRow(record: CsvRecord): Result<ParsedRow<Triple<String, String, String>>> = runCatching {
        val startText = requireNotNull(record["start_time"]) { "start_time is empty" }
        val startedAt = requireNotNull(LocalizedDateTime.parse(startText)) { "unreadable date \"$startText\"" }
        val exerciseName = requireNotNull(record["exercise_title"]) { "exercise_title is empty" }
        val title = record["title"].orEmpty()
        ParsedRow(
            workoutKey = Triple(title, startText, record["end_time"].orEmpty()),
            title = title,
            startedAt = startedAt,
            endedAt = record["end_time"]?.let(LocalizedDateTime::parse),
            notes = record["description"].orEmpty(),
            exerciseName = exerciseName,
            exerciseNotes = record["exercise_notes"].orEmpty(),
            set = WorkoutSet(
                type = setType(record["set_type"]),
                weightKg = record.number("weight_kg") ?: record.number("weight_lbs")?.times(KG_PER_LB),
                reps = record.number("reps")?.toInt(),
                rpe = record.number("rpe"),
                distanceMeters = record.number("distance_km")?.times(1000) ?: record.number("distance_miles")?.times(METERS_PER_MILE),
                durationSeconds = record.number("duration_seconds")?.toInt(),
            ),
        )
    }

    private fun setType(value: String?): SetType = when (value?.lowercase()) {
        "warmup" -> SetType.WARMUP
        "dropset" -> SetType.DROP
        "failure" -> SetType.FAILURE
        else -> SetType.NORMAL
    }

    fun write(workouts: List<ExportWorkout>): String {
        val rows = workouts.flatMap { workout ->
            workout.exercises.flatMap { exercise ->
                exercise.sets.mapIndexed { index, set ->
                    listOf(
                        workout.title,
                        workout.startedAt.format(exportDate),
                        workout.endedAt?.format(exportDate).orEmpty(),
                        workout.notes,
                        exercise.name,
                        "",
                        exercise.notes,
                        index.toString(),
                        hevySetType(set.type),
                        set.weightKg.plain(),
                        set.reps?.toString().orEmpty(),
                        set.distanceMeters?.div(1000).plain(),
                        set.durationSeconds?.toString().orEmpty(),
                        set.rpe.plain(),
                    )
                }
            }
        }
        return Csv.write(listOf(header) + rows)
    }

    private fun hevySetType(type: SetType): String = when (type) {
        SetType.NORMAL -> "normal"
        SetType.WARMUP -> "warmup"
        SetType.DROP -> "dropset"
        SetType.FAILURE -> "failure"
    }
}

/** A finished workout with exercise names already resolved for export. */
data class ExportWorkout(
    val title: String,
    val startedAt: LocalDateTime,
    val endedAt: LocalDateTime?,
    val notes: String,
    val exercises: List<ImportedExercise>,
)

internal fun CsvRecord.number(column: String): Double? = this[column]?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() }

internal fun Double?.plain(): String = when {
    this == null -> ""
    this == Math.floor(this) && !isInfinite() -> toLong().toString()
    else -> "%.3f".format(Locale.ROOT, this).trimEnd('0').trimEnd('.')
}
