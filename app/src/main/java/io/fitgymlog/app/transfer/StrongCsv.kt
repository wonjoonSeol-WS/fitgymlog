package io.fitgymlog.app.transfer

import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.WorkoutSet
import java.time.Duration
import java.time.format.DateTimeFormatter

/**
 * Strong's export, in the layouts seen in the wild: older files with a separate "Weight Unit" column,
 * newer ones with "Weight (kg)" and "Duration (sec)". Columns are found by name, so either works.
 * Writing uses the newer layout, which is the one Hevy's importer accepts.
 */
object StrongCsv {
    private val header = listOf(
        "Workout #", "Date", "Workout Name", "Duration (sec)", "Exercise Name", "Set Order", "Weight (kg)",
        "Reps", "RPE", "Distance (meters)", "Seconds", "Notes", "Workout Notes",
    )
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val durationPart = Regex("""(\d+)\s*([hms])""")

    fun looksLike(text: String): Boolean {
        val columns = Csv.read(text.lineSequence().firstOrNull().orEmpty()).firstOrNull().orEmpty().map { it.trim().lowercase() }
        return "exercise name" in columns && "set order" in columns
    }

    fun parse(text: String): ParseResult {
        val errors = mutableListOf<RowError>()
        val rows = csvRecords(text).mapNotNull { record ->
            parseRow(record).onFailure { errors += RowError(record.rowNumber, it.message.orEmpty()) }.getOrNull()
        }
        return ParseResult(groupRows(rows), errors)
    }

    /** Returns success(null) for rows that are not sets, such as Strong's "Rest Timer" entries. */
    private fun parseRow(record: CsvRecord): Result<ParsedRow<String>?> = runCatching {
        val type = setType(record["set order"]) ?: return@runCatching null
        val dateText = requireNotNull(record["date"]) { "Date is empty" }
        val startedAt = requireNotNull(LocalizedDateTime.parse(dateText)) { "unreadable date \"$dateText\"" }
        val title = record["workout name"].orEmpty()
        val duration = durationSeconds(record)
        ParsedRow(
            workoutKey = record["workout #"] ?: "$dateText|$title",
            title = title,
            startedAt = startedAt,
            endedAt = duration?.let { startedAt.plusSeconds(it) },
            notes = record["workout notes"].orEmpty(),
            exerciseName = requireNotNull(record["exercise name"]) { "Exercise Name is empty" },
            exerciseNotes = record["notes"].orEmpty(),
            set = WorkoutSet(
                type = type,
                weightKg = weightKg(record),
                reps = record.number("reps")?.toInt()?.takeIf { it > 0 || record.number("seconds") == null },
                targetReps = null,
                rpe = record.number("rpe"),
                distanceMeters = record.number("distance (meters)") ?: record.number("distance (km)")?.times(1000)
                    ?: record.number("distance"),
                durationSeconds = record.number("seconds")?.toInt()?.takeIf { it > 0 },
            ),
        )
    }

    private fun setType(order: String?): SetType? = when {
        order == null -> SetType.NORMAL
        order.all(Char::isDigit) -> SetType.NORMAL
        order.equals("W", ignoreCase = true) -> SetType.WARMUP
        order.equals("D", ignoreCase = true) -> SetType.DROP
        order.equals("F", ignoreCase = true) -> SetType.FAILURE
        else -> null
    }

    private fun weightKg(record: CsvRecord): Double? {
        record.number("weight (kg)")?.let { return it }
        record.number("weight (lbs)")?.let { return it * KG_PER_LB }
        val weight = record.number("weight") ?: return null
        val unit = record["weight unit"]?.lowercase()
        return if (unit == "lbs" || unit == "lb") weight * KG_PER_LB else weight
    }

    private fun durationSeconds(record: CsvRecord): Long? {
        record.number("duration (sec)")?.let { return it.toLong() }
        val text = record["duration"] ?: return null
        text.toLongOrNull()?.let { return it }
        val parts = durationPart.findAll(text.lowercase()).toList()
        if (parts.isEmpty()) return null
        return parts.sumOf { part ->
            val amount = part.groupValues[1].toLong()
            when (part.groupValues[2]) {
                "h" -> amount * 3600
                "m" -> amount * 60
                else -> amount
            }
        }
    }

    fun write(workouts: List<ExportWorkout>): String {
        val rows = workouts.flatMapIndexed { workoutIndex, workout ->
            val duration = workout.endedAt?.let { Duration.between(workout.startedAt, it).seconds }
            workout.exercises.flatMap { exercise ->
                exercise.sets.zip(setOrders(exercise.sets)) { set, order ->
                    listOf(
                        (workoutIndex + 1).toString(),
                        workout.startedAt.format(dateFormat),
                        workout.title,
                        duration?.toString().orEmpty(),
                        exercise.name,
                        order,
                        set.weightKg.plain(),
                        set.reps?.toString().orEmpty(),
                        set.rpe.plain(),
                        set.distanceMeters.plain(),
                        set.durationSeconds?.toString().orEmpty(),
                        exercise.notes,
                        workout.notes,
                    )
                }
            }
        }
        return Csv.write(listOf(header) + rows, delimiter = ';', quoteAll = true)
    }

    private fun setOrders(sets: List<WorkoutSet>): List<String> {
        var normal = 0
        return sets.map { set ->
            when (set.type) {
                SetType.NORMAL -> (++normal).toString()
                SetType.WARMUP -> "W"
                SetType.DROP -> "D"
                SetType.FAILURE -> "F"
            }
        }
    }
}
