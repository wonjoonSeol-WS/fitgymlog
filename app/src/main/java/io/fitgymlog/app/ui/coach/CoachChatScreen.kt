package io.fitgymlog.app.ui.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.fitgymlog.app.R
import io.fitgymlog.app.catching
import io.fitgymlog.app.coach.Change
import io.fitgymlog.app.coach.ChatTurn
import io.fitgymlog.app.coach.Remember
import io.fitgymlog.app.container
import io.fitgymlog.app.data.byId
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.ui.NumberStyles
import io.fitgymlog.app.ui.ReadableWidth
import io.fitgymlog.app.ui.formatWeightNumber
import io.fitgymlog.app.ui.localizedName
import io.fitgymlog.app.ui.unitLabel
import kotlinx.coroutines.launch

private sealed interface Sending {
    data object No : Sending
    data object Yes : Sending
    data class Failed(val message: Int) : Sending
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoachChatScreen(onBack: () -> Unit, onOpenMemory: () -> Unit, onOpenSettings: () -> Unit) {
    val app = LocalContext.current.container
    val coach by app.coach.settings.settings.collectAsStateWithLifecycle()
    val data by app.coach.store.data.collectAsStateWithLifecycle()
    val active by app.workouts.active.collectAsStateWithLifecycle()
    val exercises by app.exercises.exercises.collectAsStateWithLifecycle()
    val unit = app.settings.settings.collectAsStateWithLifecycle().value.unit
    var draft by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf<Sending>(Sending.No) }
    var menuOpen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val ready = coach.enabled && coach.isConnected && app.coach.hasKey

    fun run(block: suspend () -> Unit) {
        sending = Sending.Yes
        app.scope.launch { sending = catching { block() }.fold({ Sending.No }, { Sending.Failed(failureText(it)) }) }
    }

    LaunchedEffect(data.chat.size, sending) {
        val last = data.chat.size + if (sending == Sending.No) 0 else 1
        if (last > 0) listState.animateScrollToItem(last - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = { Text(stringResource(R.string.coach)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    TextButton(onClick = onOpenMemory) { Text(stringResource(R.string.coach_memory)) }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.coach_clear_chat)) },
                                onClick = { menuOpen = false; app.scope.launch { app.coach.clearChat() } },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        ReadableWidth(Modifier.padding(padding).imePadding()) {
            Column(Modifier.fillMaxSize()) {
                if (!ready) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.coach_not_set_up))
                        Button(onClick = onOpenSettings) { Text(stringResource(R.string.coach)) }
                    }
                    return@Column
                }
                LazyColumn(
                    state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    if (data.chat.isEmpty()) {
                        item { Text(stringResource(R.string.coach_chat_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    itemsIndexed(data.chat) { index, turn ->
                        Turn(
                            turn = turn,
                            nameOf = { exercises.byId(it).localizedName() },
                            canApply = { id -> active?.exercises?.any { it.exerciseId == id } == true },
                            unit = unit,
                            onApply = { change -> app.scope.launch { app.coach.applyChange(index, change) } },
                            onSave = { offer -> app.scope.launch { app.coach.saveMemory(index, offer) } },
                        )
                    }
                    when (val state = sending) {
                        Sending.Yes -> item { Text(stringResource(R.string.coach_thinking), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        is Sending.Failed -> item {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(state.message), color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                                TextButton(onClick = { run { app.coach.reply(app.workouts.active.value) } }) { Text(stringResource(R.string.coach_retry)) }
                            }
                        }
                        Sending.No -> Unit
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
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
                        enabled = draft.isNotBlank() && sending != Sending.Yes,
                        onClick = {
                            val message = draft
                            draft = ""
                            run { app.coach.send(message, app.workouts.active.value) }
                        },
                    ) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.coach_send)) }
                }
            }
        }
    }
}

@Composable
private fun Turn(
    turn: ChatTurn,
    nameOf: @Composable (String) -> String,
    canApply: (String) -> Boolean,
    unit: WeightUnit,
    onApply: (Int) -> Unit,
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
                Text(turn.text)
                turn.changes.forEachIndexed { index, change -> ChangeCard(change, nameOf(change.exerciseId), canApply(change.exerciseId), unit) { onApply(index) } }
                turn.remember.forEachIndexed { index, offer -> RememberCard(offer) { onSave(index) } }
            }
        }
    }
}

@Composable
private fun ChangeCard(change: Change, name: String, canApply: Boolean, unit: WeightUnit, onApply: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.coach_change_title, name), style = MaterialTheme.typography.titleSmall)
            Text(
                listOfNotNull(change.weightKg?.let { "${formatWeightNumber(it, unit)} ${unitLabel(unit)}" }, change.reps?.let { "x $it" }).joinToString(" "),
                style = NumberStyles.cell,
            )
            when {
                change.applied -> Text(stringResource(R.string.coach_applied), color = MaterialTheme.colorScheme.primary)
                canApply -> Button(onClick = onApply) { Text(stringResource(R.string.coach_apply)) }
                else -> Text(stringResource(R.string.coach_change_needs_workout), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun RememberCard(offer: Remember, onSave: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(if (offer.saved) R.string.coach_saved else R.string.coach_remember_offer, offer.text),
                modifier = Modifier.weight(1f),
            )
            if (!offer.saved) OutlinedButton(onClick = onSave) { Text(stringResource(R.string.coach_save)) }
        }
    }
}
