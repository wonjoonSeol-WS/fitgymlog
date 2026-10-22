package io.fitgymlog.app.ui.exercise

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.fitgymlog.app.R
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.ui.ChoiceDialog
import io.fitgymlog.app.ui.ReadableWidth
import io.fitgymlog.app.ui.localizedName

data class NewExercise(val name: String, val kind: ExerciseKind, val link: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisePickerDialog(
    exercises: List<Exercise>,
    onPick: (String) -> Unit,
    onCreate: (NewExercise) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val named = exercises.map { it to it.localizedName() }
    val filtered = named
        .filter { (exercise, name) -> query.isBlank() || name.contains(query, ignoreCase = true) || exercise.englishName.contains(query, ignoreCase = true) }
        .sortedBy { (_, name) -> name.lowercase() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.pick_exercise)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
                    },
                    actions = {
                        TextButton(onClick = { creating = true }) { Text(stringResource(R.string.create_exercise)) }
                    },
                )
            },
        ) { padding ->
            ReadableWidth(Modifier.padding(padding)) {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.search_exercises)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        if (filtered.isEmpty() && query.isNotBlank()) {
                            item {
                                ListItem(
                                    headlineContent = { Text(stringResource(R.string.create_named_exercise, query)) },
                                    leadingContent = { Icon(Icons.Default.Add, null) },
                                    modifier = Modifier.clickable { creating = true },
                                )
                            }
                        }
                        items(filtered, key = { it.first.id }) { (exercise, name) ->
                            ListItem(
                                leadingContent = { ExerciseAvatar(exercise, size = 40.dp) },
                                headlineContent = { Text(name) },
                                supportingContent = if (name != exercise.englishName) {
                                    { Text(exercise.englishName) }
                                } else {
                                    null
                                },
                                modifier = Modifier.clickable { onPick(exercise.id) },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        CreateExerciseDialog(
            initialName = query,
            onCreate = { onCreate(it); creating = false },
            onDismiss = { creating = false },
        )
    }
}

@Composable
fun CreateExerciseDialog(initialName: String, onCreate: (NewExercise) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    var link by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ExerciseKind.WEIGHT_REPS) }
    var choosingKind by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_exercise)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.exercise_name)) }, singleLine = true)
                Row(modifier = Modifier.fillMaxWidth().clickable { choosingKind = true }.padding(vertical = 8.dp)) {
                    Text(stringResource(R.string.exercise_kind) + ": " + kindLabel(kind))
                }
                OutlinedTextField(
                    link,
                    { link = it },
                    label = { Text(stringResource(R.string.exercise_link)) },
                    placeholder = { Text("https://youtube.com/...") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onCreate(NewExercise(name.trim(), kind, link.trim().takeIf { it.isNotEmpty() })) },
            ) { Text(stringResource(R.string.create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
    if (choosingKind) {
        ChoiceDialog(
            title = stringResource(R.string.exercise_kind),
            options = ExerciseKind.entries,
            selected = kind,
            label = { kindLabel(it) },
            onSelect = { kind = it },
            onDismiss = { choosingKind = false },
        )
    }
}

@Composable
fun kindLabel(kind: ExerciseKind): String = stringResource(
    when (kind) {
        ExerciseKind.WEIGHT_REPS -> R.string.kind_weight_reps
        ExerciseKind.BODYWEIGHT_REPS -> R.string.kind_bodyweight
        ExerciseKind.WEIGHTED_BODYWEIGHT -> R.string.kind_weighted_bodyweight
        ExerciseKind.ASSISTED_BODYWEIGHT -> R.string.kind_assisted_bodyweight
        ExerciseKind.DURATION -> R.string.kind_duration
    },
)
