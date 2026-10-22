package io.fitgymlog.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.fitgymlog.app.R
import io.fitgymlog.app.container
import io.fitgymlog.app.model.Routine
import io.fitgymlog.app.ui.NumberStyles
import io.fitgymlog.app.ui.ReadableWidth
import io.fitgymlog.app.ui.TwoPaneMinWidth
import io.fitgymlog.app.ui.formatClock
import io.fitgymlog.app.ui.lift
import io.fitgymlog.app.ui.localizedName
import io.fitgymlog.app.ui.rememberNow
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(onOpenWorkout: () -> Unit, onEditRoutine: (Long) -> Unit, onOpenBandGuide: () -> Unit) {
    val app = LocalContext.current.container
    val active by app.workouts.active.collectAsStateWithLifecycle()
    val routines by app.routines.routines.collectAsStateWithLifecycle(emptyList())
    val exercises by app.exercises.exercises.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val now = rememberNow()

    fun start(block: suspend () -> Unit) {
        app.scope.launch {
            block()
            withContext(Dispatchers.Main) { onOpenWorkout() }
        }
    }

    val title: @Composable () -> Unit = {
        Text(
            stringResource(R.string.tab_workout),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
    }
    val startItems: LazyListScope.() -> Unit = {
        active?.let { current ->
            item {
                Surface(onClick = onOpenWorkout, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primary) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.workout_in_progress), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f))
                            Text(current.workout.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary)
                        }
                        Text(formatClock(Duration.between(current.workout.startedAt, now)), style = NumberStyles.stat, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
        if (!settings.bandGuideDismissed) {
            item {
                BandGuideCard(
                    onOpen = onOpenBandGuide,
                    onDismiss = { app.settings.update { it.copy(bandGuideDismissed = true) } },
                )
            }
        }
        item { SectionTitle(stringResource(R.string.quick_start)) }
        item {
            GreyButton(
                text = stringResource(R.string.start_empty_workout),
                enabled = active == null,
                onClick = { start { app.actions.startEmpty() } },
            )
        }
    }
    val routineItems: LazyListScope.() -> Unit = {
        item { SectionTitle(stringResource(R.string.routines)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GreyButton(stringResource(R.string.new_routine), Modifier.weight(1f)) { onEditRoutine(0) }
                GreyButton(stringResource(R.string.stronglifts_5x5), Modifier.weight(1f)) {
                    app.scope.launch { StrongLiftsRoutines.addTo(app.routines, app.settings.settings.value.defaultRestSeconds) }
                }
            }
        }
        if (routines.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.no_routines),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        items(routines, key = { it.id }) { routine ->
            RoutineCard(
                routine = routine,
                exerciseNames = routine.exercises.map { planned ->
                    (exercises.firstOrNull { it.id == planned.exerciseId } ?: app.exercises.find(planned.exerciseId)).localizedName()
                },
                canStart = active == null,
                onStart = { start { app.actions.startRoutine(routine) } },
                onEdit = { onEditRoutine(routine.id) },
                onDelete = { app.scope.launch { app.routines.delete(routine.id) } },
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
        if (maxWidth >= TwoPaneMinWidth) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.padding(start = 16.dp, top = 12.dp)) { title() }
                Row(Modifier.weight(1f)) {
                    HomeColumn(Modifier.weight(0.8f), startItems)
                    HomeColumn(Modifier.weight(1.2f), routineItems)
                }
            }
        } else {
            ReadableWidth { HomeColumn(content = { item { title() }; startItems(); routineItems() }) }
        }
    }
}

@Composable
private fun HomeColumn(modifier: Modifier = Modifier, content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun GreyButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.lift.field, contentColor = MaterialTheme.colorScheme.onSurface),
        modifier = modifier.fillMaxWidth().height(48.dp),
    ) {
        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BandGuideCard(onOpen: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.band_guide_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.band_guide_card_text), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
        }
    }
}

@Composable
private fun RoutineCard(
    routine: Routine,
    exerciseNames: List<String>,
    canStart: Boolean,
    onStart: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        onClick = onEdit,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(routine.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.edit_routine)) }, onClick = { menuOpen = false; onEdit() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete_routine)) }, onClick = { menuOpen = false; onDelete() })
                }
            }
            Text(
                exerciseNames.joinToString(", "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 12.dp),
            )
            Button(
                onClick = onStart,
                enabled = canStart,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(end = 12.dp, top = 6.dp).height(46.dp),
            ) { Text(stringResource(R.string.start_routine), style = MaterialTheme.typography.titleSmall) }
        }
    }
}
