package io.fitgymlog.app.transfer

import android.content.Context
import android.net.Uri
import io.fitgymlog.app.data.ExerciseRepository
import io.fitgymlog.app.data.RoutineRepository
import io.fitgymlog.app.data.SettingsRepository
import io.fitgymlog.app.data.WorkoutRepository
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.Workout
import io.fitgymlog.app.model.WorkoutExercise
import io.fitgymlog.app.model.WorkoutSource
import java.time.Instant
import java.time.ZoneId
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

data class ImportSummary(val added: Int, val skipped: Int, val newExercises: Int, val errors: List<RowError>)

enum class ExportFormat { BACKUP, HEVY, STRONG }

class UnrecognizedFileException : IllegalArgumentException("not a Hevy, Strong or FitGymLog file")

class TransferService(
    private val context: Context,
    private val exercises: ExerciseRepository,
    private val workouts: WorkoutRepository,
    private val routines: RoutineRepository,
    private val settings: SettingsRepository,
) {
    suspend fun read(uri: Uri): PendingImport = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }.orEmpty()
        when {
            BackupFile.looksLike(text) -> PendingImport.Backup(BackupFile.decode(text))
            HevyCsv.looksLike(text) -> matched(WorkoutSource.HEVY, HevyCsv.parse(text))
            StrongCsv.looksLike(text) -> matched(WorkoutSource.STRONG, StrongCsv.parse(text))
            else -> throw UnrecognizedFileException()
        }
    }

    /**
     * Adds the workouts. [choices] maps each unrecognized name to one of our exercise ids, or to null
     * to create a custom exercise with that name.
     */
    suspend fun apply(pending: PendingImport, choices: Map<String, String?> = emptyMap()): ImportSummary = when (pending) {
        is PendingImport.Backup -> restore(pending.backup)
        is PendingImport.Csv -> importCsv(pending, choices)
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
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
    }

    private suspend fun matched(source: WorkoutSource, result: ParseResult): PendingImport.Csv {
        val matcher = exercises.matcher()
        val names = result.workouts.flatMap { workout -> workout.exercises.map { it.name } }.distinct()
        val matched = names.mapNotNull { name -> matcher.match(name)?.let { name to it } }.toMap()
        return PendingImport.Csv(source, result.workouts, result.errors, matched, names - matched.keys)
    }

    private suspend fun importCsv(pending: PendingImport.Csv, choices: Map<String, String?>): ImportSummary {
        val ids = pending.matched.toMutableMap()
        var created = 0
        pending.unmatched.forEach { name ->
            ids[name] = choices[name] ?: exercises.createCustom(name, ExerciseKind.WEIGHT_REPS).id.also { created++ }
        }
        val zone = ZoneId.systemDefault()
        val rest = settings.settings.value.defaultRestSeconds
        val converted = pending.workouts.map { imported ->
            val startedAt = imported.startedAt.atZone(zone).toInstant()
            Workout(
                title = imported.title,
                startedAt = startedAt,
                endedAt = imported.endedAt?.atZone(zone)?.toInstant() ?: startedAt,
                notes = imported.notes,
                source = pending.source,
                exercises = imported.exercises.mapIndexed { index, exercise ->
                    WorkoutExercise(index + 1L, ids.getValue(exercise.name), rest, exercise.notes, exercise.sets)
                },
            )
        }
        val added = workouts.importFinished(converted)
        return ImportSummary(added, converted.size - added, created, pending.errors)
    }

    private suspend fun restore(backup: BackupFile): ImportSummary {
        val known = exercises.exercises.value.map { it.id }.toSet()
        val newCustom = backup.customExercises.filter { it.id !in known }
        newCustom.forEach { custom ->
            exercises.restoreCustom(custom.id, custom.name, custom.kindOrDefault())
            custom.links.forEach { exercises.addLink(custom.id, it) }
        }
        backup.exerciseLinks.forEach { exercises.addLink(it.exerciseId, it.url) }
        val existingRoutines = routines.all().map { it.name }.toSet()
        backup.routines.filter { it.name !in existingRoutines }.forEach { routines.save(it.toRoutine()) }
        val converted = backup.workouts.map { it.toWorkout() }
        val added = workouts.importFinished(converted)
        return ImportSummary(added, converted.size - added, newCustom.size, emptyList())
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
