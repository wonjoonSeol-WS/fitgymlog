package io.fitgymlog.app.ui.workout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.fitgymlog.app.R
import io.fitgymlog.app.container
import io.fitgymlog.app.data.Settings
import io.fitgymlog.app.model.Exercise
import io.fitgymlog.app.model.ExerciseKind
import io.fitgymlog.app.model.SetType
import io.fitgymlog.app.model.WeightUnit
import io.fitgymlog.app.model.WorkoutExercise
import io.fitgymlog.app.model.WorkoutSet
import io.fitgymlog.app.ui.ChoiceDialog
import io.fitgymlog.app.ui.ConfirmDialog
import io.fitgymlog.app.ui.NumberStyles
import io.fitgymlog.app.ui.ReadableWidth
import io.fitgymlog.app.ui.TwoPaneMinWidth
import io.fitgymlog.app.ui.exercise.ExerciseAvatar
import io.fitgymlog.app.ui.exercise.ExercisePickerDialog
import io.fitgymlog.app.ui.exercise.FormGuide
import io.fitgymlog.app.ui.exercise.FormGuideSheet
import io.fitgymlog.app.ui.formatClock
import io.fitgymlog.app.ui.formatRest
import io.fitgymlog.app.ui.formatWeightNumber
import io.fitgymlog.app.ui.lift
import io.fitgymlog.app.ui.localizedName
import io.fitgymlog.app.ui.rememberNow
import io.fitgymlog.app.workout.ActiveWorkout
import io.fitgymlog.app.workout.SetRef
import io.fitgymlog.app.workout.bandText
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface PickerTarget {
    data object Add : PickerTarget
    data class Replace(val index: Int) : PickerTarget
}

private sealed interface SetEditor {
    val ref: SetRef

    data class Weight(override val ref: SetRef) : SetEditor
    data class Amount(override val ref: SetRef) : SetEditor
}

val REST_CHOICES = listOf(0, 30, 60, 90, 120, 150, 180, 240, 300)
private val REP_CHOICES = (1..20).toList()
private val SECOND_CHOICES = listOf(10, 15, 20, 30, 40, 45, 60, 75, 90, 120)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveWorkoutScreen(onBack: () -> Unit, onFinished: (Long?) -> Unit, onOpenExercise: (String) -> Unit) {
    val app = LocalContext.current.container
    val active by app.workouts.active.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val exercises by app.exercises.exercises.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val workout = active ?: run {
        // Only when this screen is in front: after Finish it is still composed during the exit transition.
        LaunchedEffect(Unit) { if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onBack() }
        return
    }
    val now = rememberNow()
    var picker by remember { mutableStateOf<PickerTarget?>(null) }
    var restEditor by remember { mutableStateOf<Int?>(null) }
    var setEditor by remember { mutableStateOf<SetEditor?>(null) }
    var guideFor by remember { mutableStateOf<Exercise?>(null) }
    var renaming by remember { mutableStateOf(false) }
    var confirmFinish by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    fun change(transform: (ActiveWorkout) -> ActiveWorkout) {
        app.scope.launch { app.workouts.update(transform) }
    }

    fun infoOf(exercise: WorkoutExercise): Exercise =
        exercises.firstOrNull { it.id == exercise.exerciseId } ?: app.exercises.find(exercise.exerciseId)

    val exerciseIds = workout.exercises.map { it.exerciseId }.distinct()
    val previous by produceState(emptyMap<String, List<WorkoutSet>>(), exerciseIds) {
        value = exerciseIds.associateWith { app.workouts.previousSets(it) }
    }

    val workoutList: @Composable () -> Unit = {
        LazyColumn(contentPadding = PaddingValues(bottom = 140.dp)) {
            item { WorkoutStats(workout, settings.unit, now) }
            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            itemsIndexed(workout.exercises, key = { _, exercise -> exercise.key }) { index, exercise ->
                ExerciseSection(
                    index = index,
                    exercise = exercise,
                    info = infoOf(exercise),
                    previous = previous[exercise.exerciseId].orEmpty(),
                    focused = index == workout.focus,
                    isLast = index == workout.exercises.lastIndex,
                    unit = settings.unit,
                    onChange = ::change,
                    onEditSet = { setEditor = it },
                    onReplace = { picker = PickerTarget.Replace(index) },
                    onEditRest = { restEditor = index },
                    onGuide = { guideFor = infoOf(exercise) },
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
                        exerciseOf = { id -> exercises.firstOrNull { it.id == id } ?: app.exercises.find(id) },
                        now = now,
                        unit = settings.unit,
                        onComplete = { change { it.completeFocusedSet(Instant.now()) } },
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
                    val endsAt = workout.restEndsAt ?: now
                    val total = workout.exercises.getOrNull(workout.focus)?.restSeconds ?: 0
                    RestBar(
                        remaining = Duration.between(now, endsAt),
                        totalSeconds = total,
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
            setEditor = null
        } else {
            SetEditorSheet(editor, exercise, set, infoOf(exercise), previous[exercise.exerciseId].orEmpty(), settings, app.settings::update, ::change) { setEditor = null }
        }
    }

    guideFor?.let { exercise -> FormGuideSheet(exercise) { guideFor = null } }

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
                    val created = app.exercises.createCustom(new.name, new.kind, listOfNotNull(new.link))
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
            onConfirm = {
                app.scope.launch {
                    val saved = app.actions.finish()
                    withContext(Dispatchers.Main) { onFinished(saved?.id) }
                }
            },
            onDismiss = { confirmFinish = false },
        )
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.discard_workout_title),
            text = stringResource(R.string.discard_workout_text),
            confirm = stringResource(R.string.discard),
            onConfirm = {
                app.scope.launch {
                    app.actions.discard()
                    withContext(Dispatchers.Main) { onFinished(null) }
                }
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

/**
 * The second pane on wide screens: the set the band shows, the rest timer or a large complete
 * button, and the form guide for the exercise in focus, so a glance across the room is enough.
 */
@Composable
private fun FocusPanel(
    workout: ActiveWorkout,
    exerciseOf: (String) -> Exercise,
    now: Instant,
    unit: WeightUnit,
    onComplete: () -> Unit,
    onAdjustRest: (Long) -> Unit,
    onSkipRest: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    Column(
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        val view = workout.bandView()
        if (view == null) {
            Text(stringResource(R.string.band_add_exercise), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val exercise = exerciseOf(view.exerciseId)
        val text = bandText(context, workout, exerciseOf, unit)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text.title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text(text.subtitle, style = NumberStyles.timer)
        }
        val endsAt = workout.restEndsAt
        if (endsAt != null) {
            RestBar(
                remaining = Duration.between(now, endsAt),
                totalSeconds = workout.exercises.getOrNull(workout.focus)?.restSeconds ?: 0,
                onAdjust = onAdjustRest,
                onSkip = onSkipRest,
            )
        } else if (!view.isExerciseDone) {
            Button(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onComplete()
                },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.lift.doneCheck, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(64.dp),
            ) {
                Icon(Icons.Default.Check, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.complete_set), style = MaterialTheme.typography.titleMedium)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(stringResource(R.string.form_guide), style = MaterialTheme.typography.titleMedium)
        FormGuide(exercise)
    }
}

@Composable
private fun WorkoutMenu(onRename: () -> Unit, onDiscard: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.rename_workout)) }, onClick = { open = false; onRename() })
            DropdownMenuItem(text = { Text(stringResource(R.string.discard_workout)) }, onClick = { open = false; onDiscard() })
        }
    }
}

@Composable
private fun WorkoutStats(workout: ActiveWorkout, unit: WeightUnit, now: Instant) {
    val done = workout.exercises.flatMap { it.sets }.filter { it.isCompleted }
    val volumeKg = done.sumOf { (it.weightKg ?: 0.0) * (it.reps ?: 0) }
    Row(horizontalArrangement = Arrangement.spacedBy(32.dp), modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Stat(stringResource(R.string.duration), formatClock(Duration.between(workout.workout.startedAt, now)), highlight = true)
        Stat(stringResource(R.string.volume), "${formatWeightNumber(volumeKg, unit)} ${stringResource(if (unit == WeightUnit.KG) R.string.unit_kg else R.string.unit_lb)}")
        Stat(stringResource(R.string.sets), done.size.toString())
    }
}

@Composable
fun Stat(label: String, value: String, highlight: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = NumberStyles.stat, color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun ExerciseSection(
    index: Int,
    exercise: WorkoutExercise,
    info: Exercise,
    previous: List<WorkoutSet>,
    focused: Boolean,
    isLast: Boolean,
    unit: WeightUnit,
    onChange: ((ActiveWorkout) -> ActiveWorkout) -> Unit,
    onEditSet: (SetEditor) -> Unit,
    onReplace: () -> Unit,
    onEditRest: () -> Unit,
    onGuide: () -> Unit,
    onOpenExercise: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.padding(top = 18.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
            ExerciseAvatar(info, onClick = onOpenExercise, onClickLabel = stringResource(R.string.open_exercise_details))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    info.localizedName(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClick = onOpenExercise),
                )
                if (focused) {
                    Text(stringResource(R.string.band_controls_this), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (!focused) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.use_on_band)) }, onClick = { menuOpen = false; onChange { it.focusExercise(index) } })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.replace_exercise)) }, onClick = { menuOpen = false; onReplace() })
                    if (index > 0) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.move_up)) }, onClick = { menuOpen = false; onChange { it.moveExercise(index, index - 1) } })
                    }
                    if (!isLast) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.move_down)) }, onClick = { menuOpen = false; onChange { it.moveExercise(index, index + 1) } })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.remove_exercise)) }, onClick = { menuOpen = false; onChange { it.removeExercise(index) } })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 6.dp)) {
            Pill(text = "▶ " + stringResource(R.string.form_guide), selected = false, onClick = onGuide)
            Pill(
                text = stringResource(R.string.rest_label, if (exercise.restSeconds == 0) stringResource(R.string.rest_off) else formatRest(exercise.restSeconds)),
                selected = false,
                onClick = onEditRest,
            )
        }
        SetHeader(info.kind, unit)
        var normalNumber = 0
        exercise.sets.forEachIndexed { setIndex, set ->
            if (set.type == SetType.NORMAL) normalNumber++
            SetRow(
                label = setLabel(set.type, normalNumber),
                set = set,
                previous = previous.getOrNull(setIndex),
                kind = info.kind,
                unit = unit,
                ref = SetRef(index, setIndex),
                onChange = onChange,
                onEditSet = onEditSet,
            )
        }
        Surface(
            onClick = { onChange { it.addSet(index) } },
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.lift.field,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(40.dp),
        ) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.add_set), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun setLabel(type: SetType, normalNumber: Int): String = when (type) {
    SetType.NORMAL -> normalNumber.toString()
    SetType.WARMUP -> stringResource(R.string.set_type_warmup_short)
    SetType.DROP -> stringResource(R.string.set_type_drop_short)
    SetType.FAILURE -> stringResource(R.string.set_type_failure_short)
}

@Composable
private fun SetHeader(kind: ExerciseKind, unit: WeightUnit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        HeaderCell(stringResource(R.string.col_set), Modifier.width(32.dp), TextAlign.Center)
        HeaderCell(stringResource(R.string.col_previous), Modifier.weight(1.3f))
        if (kind.usesWeight()) HeaderCell(weightHeader(kind, unit), Modifier.weight(1f), TextAlign.Center)
        HeaderCell(stringResource(if (kind == ExerciseKind.DURATION) R.string.col_seconds else R.string.col_reps), Modifier.weight(1f), TextAlign.Center)
        Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun weightHeader(kind: ExerciseKind, unit: WeightUnit): String {
    val unitText = stringResource(if (unit == WeightUnit.KG) R.string.unit_kg else R.string.unit_lb)
    return when (kind) {
        ExerciseKind.WEIGHTED_BODYWEIGHT -> "+$unitText"
        ExerciseKind.ASSISTED_BODYWEIGHT -> "−$unitText"
        else -> unitText
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier, align: TextAlign = TextAlign.Start) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
        maxLines = 1,
        modifier = modifier,
    )
}

@Composable
private fun SetRow(
    label: String,
    set: WorkoutSet,
    previous: WorkoutSet?,
    kind: ExerciseKind,
    unit: WeightUnit,
    ref: SetRef,
    onChange: ((ActiveWorkout) -> ActiveWorkout) -> Unit,
    onEditSet: (SetEditor) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    var typeMenu by remember { mutableStateOf(false) }
    val rowColor by animateColorAsState(if (set.isCompleted) MaterialTheme.lift.doneRow else Color.Transparent, label = "row")
    val labelColor = when (set.type) {
        SetType.NORMAL -> MaterialTheme.colorScheme.onSurface
        SetType.WARMUP -> MaterialTheme.lift.warmup
        SetType.DROP -> MaterialTheme.lift.drop
        SetType.FAILURE -> MaterialTheme.lift.failure
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(rowColor)
            .padding(horizontal = 16.dp, vertical = 5.dp),
    ) {
        Box(Modifier.width(32.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = NumberStyles.cell,
                color = labelColor,
                modifier = Modifier.clickable { typeMenu = true }.padding(vertical = 6.dp, horizontal = 4.dp),
            )
            DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                SetType.entries.forEach { type ->
                    DropdownMenuItem(text = { Text(setTypeName(type)) }, onClick = { typeMenu = false; onChange { it.setType(ref, type) } })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.delete_set)) }, onClick = { typeMenu = false; onChange { it.removeSet(ref) } })
            }
        }
        Text(
            previousText(previous, kind, unit),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1.3f),
        )
        if (kind.usesWeight()) {
            ValueCell(
                text = set.weightKg?.let { formatWeightNumber(it, unit) },
                placeholder = previous?.weightKg?.let { formatWeightNumber(it, unit) },
                completed = set.isCompleted,
                modifier = Modifier.weight(1f),
            ) { onEditSet(SetEditor.Weight(ref)) }
        }
        ValueCell(
            text = (if (kind == ExerciseKind.DURATION) set.durationSeconds else set.reps)?.toString(),
            placeholder = (if (kind == ExerciseKind.DURATION) previous?.durationSeconds else previous?.reps)?.toString(),
            completed = set.isCompleted,
            modifier = Modifier.weight(1f),
        ) { onEditSet(SetEditor.Amount(ref)) }
        CheckBox(set.isCompleted) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onChange { it.toggleSet(ref, Instant.now()) }
        }
    }
}

@Composable
private fun ValueCell(text: String?, placeholder: String?, completed: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(40.dp)
            .background(if (completed) Color.Transparent else MaterialTheme.lift.field, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Text(
            text ?: placeholder ?: "–",
            style = NumberStyles.cell,
            color = if (text != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun CheckBox(checked: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (checked) 1f else 0.92f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "check")
    val color by animateColorAsState(if (checked) MaterialTheme.lift.doneCheck else MaterialTheme.lift.field, label = "checkColor")
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .scale(scale)
            .background(color, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        Icon(
            Icons.Default.Check,
            stringResource(R.string.complete_set),
            tint = if (checked) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun SetEditorSheet(
    editor: SetEditor,
    exercise: WorkoutExercise,
    set: WorkoutSet,
    info: Exercise,
    previous: List<WorkoutSet>,
    settings: Settings,
    updateSettings: ((Settings) -> Settings) -> Unit,
    onChange: ((ActiveWorkout) -> ActiveWorkout) -> Unit,
    onDismiss: () -> Unit,
) {
    val ref = editor.ref
    val setTitle = stringResource(R.string.set_editor_title, info.localizedName(), ref.setIndex + 1)
    when (editor) {
        is SetEditor.Weight -> {
            val later = exercise.sets.drop(ref.setIndex + 1).filter { !it.isCompleted }
            WeightSheet(
                title = setTitle,
                initialKg = set.weightKg ?: previous.getOrNull(ref.setIndex)?.weightKg,
                previousKg = previous.getOrNull(ref.setIndex)?.weightKg,
                equipment = info.equipment,
                unit = settings.unit,
                bar = settings.bar,
                laterSetsMatch = later.isNotEmpty() && later.all { it.weightKg == set.weightKg },
                onBarChange = { bar ->
                    updateSettings { if (it.unit == WeightUnit.KG) it.copy(barKg = bar) else it.copy(barLb = bar) }
                },
                onSave = { kg, fromHere ->
                    onChange { if (fromHere) it.setWeightFromHere(ref, kg) else it.setWeight(ref, kg) }
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
        is SetEditor.Amount -> {
            val duration = info.kind == ExerciseKind.DURATION
            NumberPadSheet(
                title = setTitle,
                options = if (duration) SECOND_CHOICES else REP_CHOICES,
                selected = if (duration) set.durationSeconds else set.reps,
                target = if (duration) null else set.targetReps,
                onPick = { value ->
                    onChange { if (duration) it.setDuration(ref, value) else it.setRepsFromHere(ref, value) }
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
fun setTypeName(type: SetType): String = stringResource(
    when (type) {
        SetType.NORMAL -> R.string.set_type_normal
        SetType.WARMUP -> R.string.set_type_warmup
        SetType.DROP -> R.string.set_type_drop
        SetType.FAILURE -> R.string.set_type_failure
    },
)

@Composable
private fun previousText(previous: WorkoutSet?, kind: ExerciseKind, unit: WeightUnit): String {
    if (previous == null) return "–"
    val weight = previous.weightKg?.let { formatWeightNumber(it, unit) }
    val amount = if (kind == ExerciseKind.DURATION) previous.durationSeconds?.let { "${it}s" } else previous.reps?.toString()
    return listOfNotNull(weight, amount).joinToString(" × ").ifEmpty { "–" }
}

fun ExerciseKind.usesWeight(): Boolean = this != ExerciseKind.BODYWEIGHT_REPS && this != ExerciseKind.DURATION

/** The StrongLifts-style floating timer: big countdown, a progress line, nudge and skip. */
@Composable
private fun RestBar(remaining: Duration, totalSeconds: Int, onAdjust: (Long) -> Unit, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.lift
    Surface(
        color = colors.restBar,
        contentColor = colors.onRestBar,
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 8.dp)) {
                Text(formatClock(remaining), style = NumberStyles.timer, modifier = Modifier.width(112.dp))
                Text(
                    stringResource(R.string.rest_label, formatRest(totalSeconds)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onRestBar.copy(alpha = 0.7f),
                    maxLines = 1,
                    modifier = Modifier.weight(1f).hideWhenClipped(),
                )
                RestButton("−15") { onAdjust(-15) }
                RestButton("+15") { onAdjust(15) }
                IconButton(onClick = onSkip) { Icon(Icons.Default.Close, stringResource(R.string.skip)) }
            }
            val progress = if (totalSeconds > 0) (remaining.seconds.toFloat() / totalSeconds).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                progress = { progress },
                color = MaterialTheme.colorScheme.primary,
                trackColor = colors.onRestBar.copy(alpha = 0.15f),
                drawStopIndicator = {},
                modifier = Modifier.fillMaxWidth().height(3.dp),
            )
        }
    }
}

/** Drops the text entirely when the row is too narrow for it, rather than wrapping it a letter per line. */
private fun Modifier.hideWhenClipped(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(maxWidth = Constraints.Infinity))
    val width = constraints.maxWidth
    layout(width, placeable.height) { if (placeable.width <= width) placeable.place(0, 0) }
}

@Composable
private fun RestButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.lift.onRestBar)) {
        Text(text, style = NumberStyles.cell.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize))
    }
}

@Composable
private fun RenameDialog(title: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_workout)) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onRename(text.trim()); onDismiss() }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
