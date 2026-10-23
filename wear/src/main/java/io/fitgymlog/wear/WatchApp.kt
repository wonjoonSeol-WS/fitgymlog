package io.fitgymlog.wear

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import io.fitgymlog.watchlink.WatchCommand
import io.fitgymlog.watchlink.WatchRoutine
import io.fitgymlog.watchlink.WatchSetRef
import io.fitgymlog.watchlink.WatchWorkout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop

/**
 * A page per exercise with its sets and a check to log the next one, dials for weight and reps, and a full-screen
 * rest timer with the next set on it. The first page is the session itself.
 */
@Composable
fun WatchApp(send: (WatchCommand) -> Unit) {
    val state by PhoneLink.state.collectAsStateWithLifecycle()
    val unreachable by PhoneLink.unreachable.collectAsStateWithLifecycle()
    // Finishing here asks how hard it was, when the phone says to, before the start screen comes back.
    var ratingEffort by rememberSaveable { mutableStateOf(false) }
    val sendAndAsk: (WatchCommand) -> Unit = { command ->
        send(command)
        if (command == WatchCommand.Finish && state?.askEffort == true) ratingEffort = true
    }
    AppScaffold {
        val workout = state?.workout
        when {
            ratingEffort -> EffortScreen(onRate = { send(WatchCommand.RateEffort(it)); ratingEffort = false }, onSkip = { ratingEffort = false })
            state == null -> Message(stringResource(R.string.open_phone))
            workout != null -> Workout(workout, unreachable, sendAndAsk)
            else -> StartScreen(state?.routines.orEmpty(), unreachable, sendAndAsk)
        }
    }
}

@Composable
private fun StartScreen(routines: List<WatchRoutine>, unreachable: Boolean, send: (WatchCommand) -> Unit) {
    Screen {
        item { ListHeader { Text(stringResource(R.string.start_workout)) } }
        if (unreachable) item { Unreachable() }
        if (routines.isEmpty()) item { Hint(stringResource(R.string.no_routines)) }
        routines.forEach { routine ->
            item(key = routine.id) {
                Button(
                    onClick = { send(WatchCommand.StartRoutine(routine.id)) },
                    modifier = Modifier.fillMaxWidth(),
                    secondaryLabel = { Text(routine.summary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                ) { Text(routine.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun Workout(workout: WatchWorkout, unreachable: Boolean, send: (WatchCommand) -> Unit) {
    var editing by remember { mutableStateOf<WatchSetRef?>(null) }
    // The rest screen can be put aside to look at the workout; the next rest brings it back.
    var restHiddenFor by remember { mutableStateOf<Long?>(null) }
    // Ticks a few times a second so the count lands on time, but the screens only recompose when the second shown changes.
    val now = rememberNow(if (workout.restEndsAtMillis != null) REST_TICK_MILLIS else null)
    val restEndsAt = workout.restEndsAtMillis
    val restLeft = remember(restEndsAt) {
        derivedStateOf { restEndsAt?.let { it - now.value }?.takeIf { it > 0 }?.let { (it + 999) / 1000 * 1000 } }
    }.value

    val edited = editing?.takeIf { workout.setAt(it) != null }
    when {
        edited != null -> {
            BackHandler { editing = null }
            SetEditor(workout, edited, onLog = { command -> send(command); editing = null })
        }
        restLeft != null && restHiddenFor != workout.restEndsAtMillis -> RestScreen(
            workout = workout,
            leftMillis = restLeft,
            unreachable = unreachable,
            send = send,
            onHide = { restHiddenFor = workout.restEndsAtMillis },
        )
        else -> WorkoutPager(workout, restLeft, unreachable, send, onEdit = { editing = it }, onShowRest = { restHiddenFor = null })
    }
}

@Composable
private fun WorkoutPager(
    workout: WatchWorkout,
    restLeft: Long?,
    unreachable: Boolean,
    send: (WatchCommand) -> Unit,
    onEdit: (WatchSetRef) -> Unit,
    onShowRest: () -> Unit,
) {
    val pageCount = workout.exercises.size + 1
    val pager = rememberPagerState(initialPage = workout.focus + 1) { pageCount }
    // The phone moves on by itself after the last set of an exercise; follow it, unless the session page is open.
    LaunchedEffect(workout.focus) {
        if (pager.currentPage != 0 && pager.currentPage != workout.focus + 1) pager.animateScrollToPage(workout.focus + 1)
    }
    // Swiping to an exercise moves the phone, and the band, onto it.
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.drop(1).collect { page ->
            if (page > 0 && page - 1 != PhoneLink.state.value?.workout?.focus) send(WatchCommand.Focus(page - 1))
        }
    }
    HorizontalPagerScaffold(pagerState = pager) {
        HorizontalPager(state = pager) { page ->
            AnimatedPage(pageIndex = page, pagerState = pager) {
                if (page == 0) {
                    SessionPage(workout, unreachable, send)
                } else {
                    ExercisePage(workout, page - 1, restLeft, unreachable, send, onEdit, onShowRest)
                }
            }
        }
    }
}

@Composable
private fun SessionPage(workout: WatchWorkout, unreachable: Boolean, send: (WatchCommand) -> Unit) {
    val now by rememberNow(CLOCK_TICK_MILLIS)
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(FINISH_CONFIRM_MILLIS)
            armed = false
        }
    }
    Screen(
        edge = {
            EdgeButton(onClick = { if (armed) send(WatchCommand.Finish) else armed = true }) {
                Text(stringResource(if (armed) R.string.finish_confirm else R.string.finish))
            }
        },
    ) {
        item { ListHeader { Text(workout.title, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        if (unreachable) item { Unreachable() }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(clock((now - workout.startedAtMillis).coerceAtLeast(0)), style = WatchType.clock)
                Text(stringResource(R.string.duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Hint(pluralStringResource(R.plurals.sets_done, workout.setsTotal, workout.setsDone, workout.setsTotal)) }
        workout.exercises.getOrNull(workout.focus)?.let { current -> item { Hint(stringResource(R.string.now_doing, current.name)) } }
    }
}

@Composable
fun Unreachable() {
    Hint(stringResource(R.string.phone_unreachable), color = MaterialTheme.colorScheme.error)
}

private const val FINISH_CONFIRM_MILLIS = 3_000L
