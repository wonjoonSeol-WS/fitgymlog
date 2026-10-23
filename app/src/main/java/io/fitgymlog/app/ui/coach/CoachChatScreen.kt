package io.fitgymlog.app.ui.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.fitgymlog.app.R
import io.fitgymlog.app.coach.Change
import io.fitgymlog.app.coach.ChatTurn
import io.fitgymlog.app.coach.Pending
import io.fitgymlog.app.coach.Remember
import io.fitgymlog.app.coach.accepts
import io.fitgymlog.app.container
import io.fitgymlog.app.data.byId
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.ui.ConfirmDialog
import io.fitgymlog.app.ui.ReadableWidth
import io.fitgymlog.app.ui.localizedName
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachChatScreen(onBack: () -> Unit, onOpenMemory: () -> Unit, onOpenSettings: () -> Unit) {
    val app = LocalContext.current.container
    val ready by app.coach.ready.collectAsStateWithLifecycle()
    val data by app.coach.store.data.collectAsStateWithLifecycle()
    val pending by app.coach.chatPending.collectAsStateWithLifecycle()
    val active by app.workouts.active.collectAsStateWithLifecycle()
    val exercises by app.exercises.exercises.collectAsStateWithLifecycle()
    val unit = app.settings.settings.collectAsStateWithLifecycle().value.unit
    var draft by rememberSaveable { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val unanswered = pending != Pending.Running && data.chat.lastOrNull()?.fromUser == true

    LaunchedEffect(data.chat.size, pending) {
        val last = data.chat.size + if (pending == Pending.Running || unanswered) 1 else 0
        if (last > 0) listState.animateScrollToItem(last - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(stringResource(R.string.coach)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    if (ready) {
                        TextButton(onClick = onOpenMemory) { Text(stringResource(R.string.coach_memory)) }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.coach_clear_chat)) },
                                    onClick = { menuOpen = false; confirmClear = true },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        ReadableWidth(Modifier.padding(padding).imePadding()) { gutter ->
            Column(Modifier.fillMaxSize()) {
                if (!ready) {
                    Column(Modifier.padding(horizontal = 16.dp + gutter, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.coach_not_set_up))
                        Button(onClick = onOpenSettings) { Text(stringResource(R.string.coach_open_settings)) }
                    }
                    return@Column
                }
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp + gutter, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    if (data.chat.isEmpty()) {
                        item { Text(stringResource(R.string.coach_chat_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    itemsIndexed(data.chat, key = { index, turn -> if (turn.at == 0L) "${turn.fromUser}-at-$index" else "${turn.fromUser}-${turn.at}" }) { _, turn ->
                        Turn(
                            turn = turn,
                            nameOf = { exercises.byId(it).localizedName() },
                            accepts = { change -> active?.accepts(change) == true },
                            activeWorkout = active?.workout?.uuid,
                            unit = unit,
                            onApply = { indices -> app.scope.launch { app.coach.applyChanges(turn.at, indices) } },
                            onSave = { index -> app.scope.launch { app.coach.saveMemory(turn.at, index) } },
                        )
                    }
                    if (pending == Pending.Running) {
                        item { Text(stringResource(R.string.coach_thinking), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else if (unanswered) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val error = (pending as? Pending.Failed)?.error
                                if (error != null) FailureText(error, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                                TextButton(onClick = app.coach::retryReply) { Text(stringResource(R.string.coach_retry)) }
                            }
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp + gutter, vertical = 10.dp),
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text(stringResource(R.string.coach_ask)) },
                        shape = RoundedCornerShape(24.dp),
                        maxLines = 4,
                        modifier = Modifier.weight(1f),
                    )
                    FilledIconButton(
                        enabled = draft.isNotBlank() && pending != Pending.Running,
                        onClick = {
                            app.coach.send(draft)
                            draft = ""
                        },
                    ) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.coach_send)) }
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.coach_clear_chat_confirm),
            text = null,
            confirm = stringResource(R.string.coach_clear_chat),
            onConfirm = { app.scope.launch { app.coach.clearChat() } },
            onDismiss = { confirmClear = false },
        )
    }
}

@Composable
private fun Turn(
    turn: ChatTurn,
    nameOf: @Composable (String) -> String,
    accepts: (Change) -> Boolean,
    activeWorkout: String?,
    unit: WeightUnit,
    onApply: (Set<Int>) -> Unit,
    onSave: (Int) -> Unit,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (turn.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (turn.fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
            contentColor = if (turn.fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (turn.text.isNotBlank()) Text(turn.text)
                if (turn.changes.isNotEmpty()) ChangesCard(turn.changes, nameOf, accepts, inThisWorkout = turn.changes.any { it.workoutUuid != null && it.workoutUuid == activeWorkout }, unit, onApply)
                turn.remember.forEachIndexed { index, offer -> RememberCard(offer) { onSave(index) } }
            }
        }
    }
}

@Composable
private fun ChangesCard(
    changes: List<Change>,
    nameOf: @Composable (String) -> String,
    accepts: (Change) -> Boolean,
    inThisWorkout: Boolean,
    unit: WeightUnit,
    onApply: (Set<Int>) -> Unit,
) {
    val rows = changes.map { change ->
        ChangeRow(
            title = changeTitle(change.action, nameOf(change.exerciseId)),
            detail = planText(change.sets, change.reps, change.minutes, change.weightKg?.let { targetText(it, null, unit) }, change.restSeconds),
            checkable = accepts(change),
            applied = change.applied,
        )
    }
    val footer = if (changes.any { !it.applied }) stringResource(if (inThisWorkout) R.string.coach_change_no_longer_fits else R.string.coach_change_needs_workout) else null
    ChangeChecklist(stringResource(R.string.coach_changes_title), rows, footer, onApply)
}

@Composable
private fun RememberCard(offer: Remember, onSave: () -> Unit) {
    val label = when {
        offer.done && offer.forget -> R.string.coach_forgotten
        offer.done -> R.string.coach_saved
        offer.forget -> R.string.coach_forget_offer
        offer.previous != null -> R.string.coach_update_offer
        else -> R.string.coach_remember_offer
    }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        offer.done || offer.forget || offer.days == null -> stringResource(label, offer.text)
                        offer.previous != null -> pluralStringResource(R.plurals.coach_update_for, offer.days, offer.days, offer.text)
                        else -> pluralStringResource(R.plurals.coach_remember_for, offer.days, offer.days, offer.text)
                    },
                )
                if (!offer.done && !offer.forget) offer.previous?.let { Text(it, style = MaterialTheme.typography.bodySmall, textDecoration = TextDecoration.LineThrough) }
            }
            if (!offer.done) OutlinedButton(onClick = onSave) { Text(stringResource(if (offer.forget) R.string.coach_forget else R.string.save)) }
        }
    }
}
