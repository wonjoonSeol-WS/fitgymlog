package io.fitgymlog.app.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.fitgymlog.app.R
import io.fitgymlog.app.ui.coach.CoachChatScreen
import io.fitgymlog.app.ui.coach.CoachMemoryScreen
import io.fitgymlog.app.ui.coach.CoachSettingsScreen
import io.fitgymlog.app.ui.exercise.ExerciseDetailScreen
import io.fitgymlog.app.ui.guide.BandGuideScreen
import io.fitgymlog.app.ui.history.HistoryScreen
import io.fitgymlog.app.ui.history.WorkoutDetailScreen
import io.fitgymlog.app.ui.home.HomeScreen
import io.fitgymlog.app.ui.routine.RoutineEditorScreen
import io.fitgymlog.app.ui.settings.SettingsScreen
import io.fitgymlog.app.ui.workout.ActiveWorkoutScreen

private object Routes {
    const val HOME = "home"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val ACTIVE = "active"
    const val ROUTINE = "routine/{id}"
    const val WORKOUT = "workout/{id}"
    const val EXERCISE = "exercise/{id}"
    const val BAND_GUIDE = "band-guide"
    const val COACH = "coach"
    const val COACH_SETTINGS = "coach-settings"
    const val COACH_MEMORY = "coach-memory"

    fun routine(id: Long) = "routine/$id"
    fun workout(id: Long) = "workout/$id"
    fun exercise(id: String) = "exercise/${Uri.encode(id)}"
}

private data class Tab(val route: String, val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, R.string.tab_workout, Icons.Default.Home),
    Tab(Routes.HISTORY, R.string.tab_history, Icons.Default.DateRange),
    Tab(Routes.SETTINGS, R.string.tab_settings, Icons.Default.Settings),
)

@Composable
fun AppNavigation(openActiveWorkout: Boolean, navController: NavHostController = rememberNavController()) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showTabs = tabs.any { it.route == currentRoute }

    fun open(tab: Tab) {
        navController.navigate(tab.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val rail = maxWidth >= NavigationRailMinWidth
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = { if (showTabs && !rail) TabBar(currentRoute, ::open) },
        ) { padding ->
            val bottom = padding.calculateBottomPadding()
            Row(Modifier.fillMaxSize().padding(bottom = bottom).consumeWindowInsets(PaddingValues(bottom = bottom))) {
                if (showTabs && rail) TabRail(currentRoute, ::open)
                AppNavHost(navController, openActiveWorkout, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TabBar(currentRoute: String?, onOpen: (Tab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.route,
                onClick = { onOpen(tab) },
                icon = { Icon(tab.icon, null) },
                label = { Text(stringResource(tab.label)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.background,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
private fun TabRail(currentRoute: String?, onOpen: (Tab) -> Unit) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.background, modifier = Modifier.statusBarsPadding()) {
        Spacer(Modifier.height(12.dp))
        tabs.forEach { tab ->
            NavigationRailItem(
                selected = currentRoute == tab.route,
                onClick = { onOpen(tab) },
                icon = { Icon(tab.icon, null) },
                label = { Text(stringResource(tab.label)) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

/** Ignores a second tap that arrives after [entry] has already left, and never leaves the stack empty. */
private fun NavHostController.popFrom(entry: NavBackStackEntry) {
    if (currentBackStackEntry?.id != entry.id) return
    if (!popBackStack()) navigate(Routes.HOME)
}

private fun NavHostController.leaveWorkout(savedId: Long?) {
    navigate(savedId?.let(Routes::workout) ?: Routes.HOME) {
        popUpTo(Routes.HOME)
        launchSingleTop = true
    }
}

@Composable
private fun AppNavHost(navController: NavHostController, openActiveWorkout: Boolean, modifier: Modifier) {
    NavHost(navController = navController, startDestination = Routes.HOME, modifier = modifier) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenWorkout = { navController.navigate(Routes.ACTIVE) { launchSingleTop = true } },
                onEditRoutine = { navController.navigate(Routes.routine(it)) },
                onOpenBandGuide = { navController.navigate(Routes.BAND_GUIDE) },
                onOpenCoach = { navController.navigate(Routes.COACH) },
            )
        }
        composable(Routes.HISTORY) {
            HistoryScreen(
                onOpenWorkout = { navController.navigate(Routes.workout(it)) },
                onOpenExercise = { navController.navigate(Routes.exercise(it)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onOpenBandGuide = { navController.navigate(Routes.BAND_GUIDE) },
                onOpenCoach = { navController.navigate(Routes.COACH_SETTINGS) },
            )
        }
        composable(Routes.COACH) { entry ->
            CoachChatScreen(
                onBack = { navController.popFrom(entry) },
                onOpenMemory = { navController.navigate(Routes.COACH_MEMORY) },
                onOpenSettings = { navController.navigate(Routes.COACH_SETTINGS) },
            )
        }
        composable(Routes.COACH_SETTINGS) { entry ->
            CoachSettingsScreen(onBack = { navController.popFrom(entry) }, onOpenMemory = { navController.navigate(Routes.COACH_MEMORY) })
        }
        composable(Routes.COACH_MEMORY) { entry -> CoachMemoryScreen(onBack = { navController.popFrom(entry) }) }
        composable(Routes.BAND_GUIDE) { entry -> BandGuideScreen(onBack = { navController.popFrom(entry) }) }
        composable(Routes.ACTIVE) { entry ->
            ActiveWorkoutScreen(
                onBack = { navController.popFrom(entry) },
                onFinished = navController::leaveWorkout,
                onOpenExercise = { navController.navigate(Routes.exercise(it)) },
                onOpenCoach = { navController.navigate(Routes.COACH) },
            )
        }
        composable(Routes.ROUTINE, listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            RoutineEditorScreen(routineId = entry.arguments?.getLong("id") ?: 0L, onDone = { navController.popFrom(entry) })
        }
        composable(Routes.WORKOUT, listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            WorkoutDetailScreen(
                workoutId = entry.arguments?.getLong("id") ?: 0L,
                onBack = { navController.popFrom(entry) },
                onOpenExercise = { navController.navigate(Routes.exercise(it)) },
            )
        }
        composable(Routes.EXERCISE, listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            ExerciseDetailScreen(exerciseId = entry.arguments?.getString("id").orEmpty(), onBack = { navController.popFrom(entry) })
        }
    }

    // MainActivity recomputes openActiveWorkout on every recreation; only the first launch may act on it.
    var launchHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (launchHandled) return@LaunchedEffect
        launchHandled = true
        if (openActiveWorkout) navController.navigate(Routes.ACTIVE) { launchSingleTop = true }
    }
}
