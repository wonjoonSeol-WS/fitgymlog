package io.fitgymlog.app.ui.coach

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.fitgymlog.app.R
import io.fitgymlog.app.coach.Memory
import io.fitgymlog.app.coach.MemoryTopic
import io.fitgymlog.app.container
import io.fitgymlog.app.ui.ReadableWidth
import io.fitgymlog.app.ui.settings.SettingsSection
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachMemoryScreen(onBack: () -> Unit) {
    val app = LocalContext.current.container
    val data by app.coach.store.data.collectAsStateWithLifecycle()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(stringResource(R.string.coach_memory)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        ReadableWidth(Modifier.padding(padding)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.coach_memory_text),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                if (data.memories.isEmpty()) {
                    Text(stringResource(R.string.coach_memory_empty), modifier = Modifier.padding(16.dp))
                }
                MemoryTopic.entries.forEach { topic ->
                    val notes = data.memories.filter { it.topic == topic }
                    if (notes.isEmpty()) return@forEach
                    SettingsSection(stringResource(topicText(topic)))
                    notes.forEach { memory ->
                        ListItem(
                            headlineContent = { Text(memory.text) },
                            supportingContent = { Text(stringResource(R.string.coach_memory_since, memory.savedAt)) },
                            trailingContent = {
                                Row {
                                    TextButton(onClick = { editing = memory.id }) { Text(stringResource(R.string.coach_edit)) }
                                    TextButton(onClick = { app.scope.launch { app.coach.store.forget(memory.id) } }) { Text(stringResource(R.string.delete)) }
                                }
                            },
                        )
                    }
                }
                TextButton(onClick = { adding = true }, modifier = Modifier.padding(8.dp)) { Text("+ " + stringResource(R.string.coach_memory_add)) }
            }
        }
    }

    data.memories.firstOrNull { it.id == editing }?.let { memory ->
        NoteDialog(
            title = stringResource(R.string.coach_memory_edit),
            initial = memory,
            onSave = { _, text -> app.scope.launch { app.coach.store.editMemory(memory.id, text) } },
            onDismiss = { editing = null },
        )
    }
    if (adding) {
        NoteDialog(
            title = stringResource(R.string.coach_memory_add),
            initial = null,
            onSave = { topic, text -> app.scope.launch { app.coach.store.remember(topic, text) } },
            onDismiss = { adding = false },
        )
    }
}

@Composable
private fun NoteDialog(title: String, initial: Memory?, onSave: (MemoryTopic, String) -> Unit, onDismiss: () -> Unit) {
    var topic by rememberSaveable { mutableStateOf(initial?.topic ?: MemoryTopic.HEALTH) }
    var text by rememberSaveable { mutableStateOf(initial?.text.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (initial == null) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MemoryTopic.entries.forEach { option ->
                            FilterChip(selected = topic == option, onClick = { topic = option }, label = { Text(stringResource(topicText(option))) })
                        }
                    }
                }
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onSave(topic, text); onDismiss() }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
