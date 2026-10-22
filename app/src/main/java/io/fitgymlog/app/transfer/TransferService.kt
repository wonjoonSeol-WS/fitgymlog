package io.fitgymlog.app.transfer

import android.content.Context
import android.net.Uri
import android.util.Log
import io.fitgymlog.app.LOG_TAG
import io.fitgymlog.app.data.ExerciseRepository
import io.fitgymlog.app.data.RoutineRepository
import io.fitgymlog.app.data.SettingsRepository
import io.fitgymlog.app.data.WorkoutRepository
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutExercise
import io.fitgymlog.app.model.WorkoutSource
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A file that has been read and matched, waiting for the user to confirm exercises we did not recognize. */
sealed interface PendingImport {
    data class Csv(
        val source: WorkoutSource,
        val workouts: List<ImportedWorkout>,
        val errors: List<RowError>,
        val matched: Map<String, String>,
        val unmatched: List<String>,
    ) : PendingImport

    data class Backup(val backup: BackupFile) : PendingImport
}

data class ImportSummary(val added: Int, val skipped: Int, val newExercises: Int)

enum class ExportFormat { BACKUP, HEVY, STRONG }

class TransferService(
    private val context: Context,
    private val exercises: ExerciseRepository,
    private val workouts: WorkoutRepository,
    private val routines: RoutineRepository,
    private val settings: SettingsRepository,
) {
    suspend fun read(uri: Uri): PendingImport = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: throw TransferException(TransferFailure.IO, "no input stream for $uri")
        val text = input.use { it.readAtMost(MAX_IMPORT_BYTES) }.decodeToString()
        when {
            BackupFile.looksLike(text) -> PendingImport.Backup(BackupFile.decode(text))
            HevyCsv.looksLike(text) -> csv(WorkoutSource.HEVY, HevyCsv.parse(text))
            StrongCsv.looksLike(text) -> csv(WorkoutSource.STRONG, StrongCsv.parse(text))
            else -> throw TransferException(TransferFailure.UNRECOGNIZED, "not a Hevy, Strong or FitGymLog file")
        }
    }

    /** Names in [choices] follow the user's pick; the other unrecognized names become custom exercises. */
    suspend fun apply(pending: PendingImport, choices: Map<String, ExerciseChoice> = emptyMap()): ImportSummary = withContext(Dispatchers.IO) {
        when (pending) {
            is PendingImport.Backup -> restore(pending.backup)
            is PendingImport.Csv -> importCsv(pending, choices)
        }
    }

    suspend fun export(uri: Uri, format: ExportFormat) = withContext(Dispatchers.IO) {
        val finished = workouts.allFinished().sortedBy { it.startedAt }
        val text = when (format) {
            ExportFormat.BACKUP -> BackupFile.encode(
                BackupFile(
                    exportedAt = Instant.now().toString(),
                    customExercises = exercises.exercises.value.filter { it.isCustom }.map {
                        BackupCustomExercise(it.id, it.customName.orEmpty(), it.kind.name, it.userLinks)
                    },
                    routines = routines.all().map { it.toBackup() },
                    workouts = finished.map { it.toBackup() },
                    exerciseLinks = exercises.exercises.value.filter { !it.isCustom }.flatMap { exercise ->
                        exercise.userLinks.map { BackupLink(exercise.id, it) }
                    },
                ),
            )
            ExportFormat.HEVY -> HevyCsv.write(finished.map { it.toExport() })
            ExportFormat.STRONG -> StrongCsv.write(finished.map { it.toExport() })
        }
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: throw TransferException(TransferFailure.IO, "no output stream for $uri")
        output.use { it.write(text.toByteArray()) }
    }

    private suspend fun csv(source: WorkoutSource, result: ParseResult): PendingImport.Csv {
        result.errors.firstOrNull()?.let { Log.w(LOG_TAG, "${result.errors.size} unreadable rows, first at row ${it.rowNumber}: ${it.message}") }
        return pendingCsv(source, result, exercises.matcher())
    }

    private suspend fun importCsv(pending: PendingImport.Csv, choices: Map<String, ExerciseChoice>): ImportSummary {
        val zone = ZoneId.systemDefault()
        val rest = settings.settings.value.defaultRestSeconds
        return importWorkouts(
            pending,
            choices,
            keepNew = { candidates -> workouts.notYetImported(candidates, { it.startedAt.atZone(zone).toInstant() }, { it.title }) },
            createCustom = { exercises.createCustom(it.name, it.kind, it.links).id },
            save = { fresh, ids -> workouts.importFinished(fresh.map { it.toWorkout(pending.source, zone, rest, ids) }) },
        )
    }

    private fun ImportedWorkout.toWorkout(source: WorkoutSource, zone: ZoneId, restSeconds: Int, exerciseIds: Map<String, String>): Workout {
        val start = startedAt.atZone(zone).toInstant()
        return Workout(
            title = title,
            startedAt = start,
            endedAt = endedAt?.atZone(zone)?.toInstant() ?: start,
            notes = notes,
            source = source,
            exercises = exercises.mapIndexed { index, exercise ->
                WorkoutExercise(index + 1L, exerciseIds.getValue(exercise.name), restSeconds, exercise.notes, exercise.sets)
            },
        )
    }

    private suspend fun restore(backup: BackupFile): ImportSummary {
        val converted = try {
            backup.workouts.map { it.toWorkout() }
        } catch (e: DateTimeParseException) {
            throw TransferException(TransferFailure.MALFORMED_BACKUP, e.message.orEmpty(), e)
        }
        val known = exercises.exercises.value.map { it.id }.toSet()
        val newCustom = backup.customExercises.filter { it.id !in known }
        newCustom.forEach { custom ->
            exercises.restoreCustom(custom.id, custom.name, custom.kindOrDefault())
            custom.links.forEach { exercises.addLink(custom.id, it) }
        }
        backup.exerciseLinks.forEach { exercises.addLink(it.exerciseId, it.url) }
        val existingRoutines = routines.all().map { it.name }.toSet()
        backup.routines.filter { it.name !in existingRoutines }.forEach { routines.save(it.toRoutine()) }
        val added = workouts.importFinished(converted)
        return ImportSummary(added, converted.size - added, newCustom.size)
    }

    private fun Workout.toExport(): ExportWorkout {
        val zone = ZoneId.systemDefault()
        return ExportWorkout(
            title = title,
            startedAt = startedAt.atZone(zone).toLocalDateTime(),
            endedAt = endedAt?.atZone(zone)?.toLocalDateTime(),
            notes = notes,
            exercises = exercises.map { exercise ->
                ImportedExercise(this@TransferService.exercises.find(exercise.exerciseId).englishName, exercise.notes, exercise.sets)
            },
        )
    }
}
