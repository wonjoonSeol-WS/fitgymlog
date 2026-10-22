package io.fitgymlog.app.ui.workout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.fitgymlog.app.R
import io.fitgymlog.app.container
import io.fitgymlog.app.data.byId
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.ui.ChoiceDialog
import io.fitgymlog.app.ui.ConfirmDialog
import io.fitgymlog.app.ui.REST_CHOICES
import io.fitgymlog.app.ui.ReadableWidth
import io.fitgymlog.app.ui.Stat
import io.fitgymlog.app.ui.TwoPaneMinWidth
import io.fitgymlog.app.ui.exercise.ExercisePickerDialog
import io.fitgymlog.app.ui.exercise.FormGuideSheet
import io.fitgymlog.app.ui.formatClock
import io.fitgymlog.app.ui.formatRest
import io.fitgymlog.app.ui.formatWeightNumber
import io.fitgymlog.app.ui.lift
import io.fitgymlog.app.ui.rememberNow
import io.fitgymlog.app.ui.unitLabel
import io.fitgymlog.app.workout.ActiveWorkout
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveWorkoutScreen(onBack: () -> Unit, onFinished: (Long?) -> Unit, onOpenExercise: (String) -> Unit) {
    val app = LocalContext.current.container
    val active by app.workouts.active.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val exercises by app.exercises.exercises.collectAsStateWithLifecycle()
    var leaving by remember { mutableStateOf(false) }
    val lastShown = remember { mutableStateOf(active) }
    SideEffect { active?.let { lastShown.value = it } }
    val workout = active ?: lastShown.value?.takeIf { leaving }
    if (workout == null) {
        // The entry stays composed through its exit transition, so only go back once it is resumed.
        LifecycleResumeEffect(Unit) {
            onBack()
            onPauseOrDispose { }
        }
        return
    }
    val listState = rememberLazyListState()
    var picker by rememberSaveable(stateSaver = PickerTargetSaver) { mutableStateOf<PickerTarget?>(null) }
    var restEditor by rememberSaveable { mutableStateOf<Int?>(null) }
    var setEditor by rememberSaveable(stateSaver = SetEditorSaver) { mutableStateOf<SetEditor?>(null) }
    var guideFor by rememberSaveable { mutableStateOf<String?>(null) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmFinish by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }

    fun change(transform: (ActiveWorkout) -> ActiveWorkout) {
        app.scope.launch { app.workouts.update(transform) }
    }

    fun exerciseOf(id: String): Exercise = exercises.byId(id)

    fun leave(end: suspend () -> Long?) {
        if (leaving) return
        leaving = true
        app.scope.launch {
            val savedId = try {
                end()
            } catch (error: Exception) {
                withContext(Dispatchers.Main) { leaving = false }
                throw error
            }
            withContext(Dispatchers.Main) { onFinished(savedId) }
        }
    }

    val exerciseIds = workout.exercises.map { it.exerciseId }.distinct()
    val previous by produceState(emptyMap<String, List<WorkoutSet>>(), exerciseIds) {
        value = exerciseIds.associateWith { app.workouts.previousSets(it) }
    }

    val workoutList: @Composable () -> Unit = {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 140.dp)) {
            item { WorkoutStats(workout, settings.unit) }
            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            itemsIndexed(workout.exercises, key = { _, exercise -> exercise.key }) { index, exercise ->
                val focused = index == workout.focus
                ExerciseSection(
                    index = index,
                    exercise = exercise,
                    info = exerciseOf(exercise.exerciseId),
                    previous = previous[exercise.exerciseId].orEmpty(),
                    focused = focused,
                    bandHint = focused && settings.bandRemoteEnabled,
                    isLast = index == workout.exercises.lastIndex,
                    unit = settings.unit,
                    onChange = ::change,
                    onEditSet = { setEditor = it },
                    onReplace = { picker = PickerTarget.Replace(index) },
                    onEditRest = { restEditor = index },
                    onGuide = { guideFor = exercise.exerciseId },
                    onOpenExercise = { onOpenExercise(exercise.exerciseId) },
                )
            }
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { picker = PickerTarget.Add },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.add_exercise), style = MaterialTheme.typography.titleSmall)
                    }
                    Button(
                        onClick = { confirmDiscard = true },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.lift.field, contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) { Text(stringResource(R.string.discard_workout), style = MaterialTheme.typography.titleSmall) }
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Text(workout.workout.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    Button(
                        onClick = { confirmFinish = true },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp),
                        modifier = Modifier.height(38.dp),
                    ) { Text(stringResource(R.string.finish), fontWeight = FontWeight.SemiBold) }
                    WorkoutMenu(onRename = { renaming = true }, onDiscard = { confirmDiscard = true })
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            if (maxWidth >= TwoPaneMinWidth) {
                // Sets on the right keep their done boxes at the screen edge, under the thumb.
                Row(Modifier.fillMaxSize()) {
                    FocusPanel(
                        workout = workout,
                        exerciseOf = ::exerciseOf,
                        unit = settings.unit,
                        bandRemote = settings.bandRemoteEnabled,
                        onComplete = { expected -> change { it.complete(expected, Instant.now()) } },
                        onNextExercise = { change { it.focusNextExercise() } },
                        onAdjustRest = { seconds -> change { it.adjustRest(Duration.ofSeconds(seconds), Instant.now()) } },
                        onSkipRest = { change { it.restOver() } },
                        modifier = Modifier.weight(1f),
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(Modifier.weight(1.1f).fillMaxHeight()) { workoutList() }
                }
            } else {
                ReadableWidth { workoutList() }
                AnimatedVisibility(
                    visible = workout.restEndsAt != null,
                    enter = slideInVertically { it * 2 },
                    exit = slideOutVertically { it * 2 },
                    modifier = Modifier.align(Alignment.BottomCenter),
                ) {
                    RestBar(
                        workout = workout,
                        onAdjust = { seconds -> change { it.adjustRest(Duration.ofSeconds(seconds), Instant.now()) } },
                        onSkip = { change { it.restOver() } },
                        modifier = Modifier.navigationBarsPadding().padding(12.dp),
                    )
                }
            }
        }
    }

    setEditor?.let { editor ->
        val exercise = workout.exercises.getOrNull(editor.ref.exerciseIndex)
        val set = exercise?.sets?.getOrNull(editor.ref.setIndex)
        if (exercise == null || set == null) {
            LaunchedEffect(editor) { setEditor = null }
        } else {
            key(editor) {
                SetEditorSheet(
                    editor = editor,
                    exercise = exercise,
                    set = set,
                    info = exerciseOf(exercise.exerciseId),
                    previous = previous[exercise.exerciseId].orEmpty(),
                    settings = settings,
                    updateSettings = app.settings::update,
                    onChange = ::change,
                ) { setEditor = null }
            }
        }
    }

    guideFor?.let { id -> FormGuideSheet(exerciseOf(id)) { guideFor = null } }

    picker?.let { target ->
        ExercisePickerDialog(
            exercises = exercises,
            onPick = { id ->
                picker = null
                app.scope.launch {
                    when (target) {
                        PickerTarget.Add -> app.actions.addExercise(id)
                        is PickerTarget.Replace -> app.actions.replaceExercise(target.index, id)
                    }
                }
            },
            onCreate = { new ->
                picker = null
                app.scope.launch {
                    val created = app.exercises.createCustom(new)
                    when (target) {
                        PickerTarget.Add -> app.actions.addExercise(created.id)
                        is PickerTarget.Replace -> app.actions.replaceExercise(target.index, created.id)
                    }
                }
            },
            onDismiss = { picker = null },
        )
    }

    restEditor?.let { index ->
        ChoiceDialog(
            title = stringResource(R.string.rest_timer),
            options = REST_CHOICES,
            selected = workout.exercises.getOrNull(index)?.restSeconds ?: 0,
            label = { if (it == 0) stringResource(R.string.rest_off) else formatRest(it) },
            onSelect = { seconds -> change { it.setRestSeconds(index, seconds) } },
            onDismiss = { restEditor = null },
        )
    }

    if (renaming) {
        RenameDialog(workout.workout.title, onRename = { title -> change { it.rename(title) } }, onDismiss = { renaming = false })
    }

    if (confirmFinish) {
        val open = workout.exercises.sumOf { exercise -> exercise.sets.count { !it.isCompleted } }
        ConfirmDialog(
            title = stringResource(R.string.finish_workout_title),
            text = if (open > 0) pluralStringResource(R.plurals.finish_drops_open_sets, open, open) else null,
            confirm = stringResource(R.string.finish),
            onConfirm = { leave { app.actions.finish()?.id } },
            onDismiss = { confirmFinish = false },
        )
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.discard_workout_title),
            text = stringResource(R.string.discard_workout_text),
            confirm = stringResource(R.string.discard),
            onConfirm = {
                leave {
                    app.actions.discard()
                    null
                }
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

@Composable
private fun WorkoutStats(workout: ActiveWorkout, unit: WeightUnit) {
    val done = workout.exercises.flatMap { it.sets }.filter { it.isCompleted }
    val volumeKg = done.sumOf { (it.weightKg ?: 0.0) * (it.reps ?: 0) }
    Row(horizontalArrangement = Arrangement.spacedBy(32.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        ElapsedStat(workout.workout.startedAt)
        Stat(stringResource(R.string.volume), "${formatWeightNumber(volumeKg, unit)} ${unitLabel(unit)}")
        Stat(stringResource(R.string.sets), done.size.toString())
    }
}

@Composable
private fun ElapsedStat(startedAt: Instant) {
    val now = rememberNow()
    Stat(stringResource(R.string.duration), formatClock(Duration.between(startedAt, now)), highlight = true)
}
