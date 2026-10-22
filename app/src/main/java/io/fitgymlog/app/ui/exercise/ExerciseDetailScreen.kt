package io.fitgymlog.app.ui.exercise

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.fitgymlog.app.R
import io.fitgymlog.app.container
import io.fitgymlog.app.ui.ReadableWidth
import io.fitgymlog.app.ui.history.SessionLines
import io.fitgymlog.app.ui.history.recentSessions
import io.fitgymlog.app.ui.localizedName
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseDetailScreen(exerciseId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.container
    val exercises by app.exercises.exercises.collectAsStateWithLifecycle()
    val history by app.workouts.history.collectAsStateWithLifecycle(emptyList())
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val exercise = exercises.firstOrNull { it.id == exerciseId } ?: app.exercises.find(exerciseId)
    var addingLink by remember { mutableStateOf(false) }

    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(exercise.localizedName()) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        ReadableWidth(Modifier.padding(padding)) {
            LazyColumn(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Column {
                        if (exercise.localizedName() != exercise.englishName) {
                            Text(exercise.englishName, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(kindLabel(exercise.kind), style = MaterialTheme.typography.bodySmall)
                    }
                }
                item { Text(stringResource(R.string.form_guide), style = MaterialTheme.typography.titleMedium) }
                if (exercise.links.isEmpty()) {
                    item { Text(stringResource(R.string.no_form_links), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                items(exercise.links) { url ->
                    ListItem(
                        headlineContent = { Text(linkTitle(url)) },
                        supportingContent = { Text(url, maxLines = 1) },
                        leadingContent = { Icon(Icons.Default.PlayArrow, null) },
                        trailingContent = if (exercise.isCustom) {
                            {
                                IconButton(onClick = { app.scope.launch { app.exercises.updateCustom(exercise.copy(links = exercise.links - url)) } }) {
                                    Icon(Icons.Default.Delete, stringResource(R.string.delete))
                                }
                            }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth().clickable { open(url) },
                    )
                }
                if (exercise.isCustom) {
                    item {
                        TextButton(onClick = { addingLink = true }) {
                            Icon(Icons.Default.Add, null)
                            Text(stringResource(R.string.add_link))
                        }
                    }
                }
                item { Spacer(Modifier.padding(4.dp)) }
                item { Text(stringResource(R.string.recent_sessions), style = MaterialTheme.typography.titleMedium) }
                item {
                    val sessions = recentSessions(history, exerciseId)
                    if (sessions.isEmpty()) {
                        Text(stringResource(R.string.no_sessions_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        SessionLines(context, exercise, sessions, settings.unit)
                    }
                }
            }
        }
    }

    if (addingLink) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addingLink = false },
            title = { Text(stringResource(R.string.add_link)) },
            text = { OutlinedTextField(url, { url = it }, placeholder = { Text("https://") }, singleLine = true) },
            confirmButton = {
                TextButton(
                    enabled = url.startsWith("http"),
                    onClick = {
                        app.scope.launch { app.exercises.updateCustom(exercise.copy(links = exercise.links + url.trim())) }
                        addingLink = false
                    },
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { addingLink = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun linkTitle(url: String): String = when {
    "youtube.com" in url || "youtu.be" in url -> stringResource(R.string.link_video)
    "stronglifts.com" in url -> stringResource(R.string.link_stronglifts)
    else -> url.toUri().host ?: url
}
