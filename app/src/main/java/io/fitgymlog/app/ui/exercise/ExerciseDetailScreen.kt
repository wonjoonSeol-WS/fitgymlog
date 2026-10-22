package io.fitgymlog.app.ui.exercise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.fitgymlog.app.R
import io.fitgymlog.app.container
import io.fitgymlog.app.data.byId
import io.fitgymlog.app.ui.ReadableWidth
import io.fitgymlog.app.ui.TwoPaneMinWidth
import io.fitgymlog.app.ui.history.SessionLines
import io.fitgymlog.app.ui.history.recentSessions
import io.fitgymlog.app.ui.localizedName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseDetailScreen(exerciseId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.container
    val exercises by app.exercises.exercises.collectAsStateWithLifecycle()
    val recent by remember(exerciseId) { app.workouts.recentWith(exerciseId, RECENT_SESSIONS) }
        .collectAsStateWithLifecycle(null)
    val sessions = remember(recent, exerciseId) { recent?.let { recentSessions(it, exerciseId) } }
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val exercise = exercises.byId(exerciseId)
    val name = exercise.localizedName()

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(name, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        val guideItems: LazyListScope.() -> Unit = {
            if (name != exercise.englishName) {
                item { Text(exercise.englishName, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.form_guide), style = MaterialTheme.typography.titleLarge)
                    FormGuide(exercise)
                }
            }
        }
        val sessionItems: LazyListScope.() -> Unit = {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.recent_sessions), style = MaterialTheme.typography.titleLarge)
                    when {
                        sessions == null -> Unit
                        sessions.isEmpty() -> Text(stringResource(R.string.no_sessions_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> SessionLines(context, exercise, sessions, settings.unit)
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            if (maxWidth >= TwoPaneMinWidth) {
                Row(Modifier.fillMaxSize()) {
                    DetailColumn(Modifier.weight(1f), guideItems)
                    DetailColumn(Modifier.weight(1f), sessionItems)
                }
            } else {
                ReadableWidth { DetailColumn(content = { guideItems(); sessionItems() }) }
            }
        }
    }
}

private const val RECENT_SESSIONS = 10

@Composable
private fun DetailColumn(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp), content = content)
}
