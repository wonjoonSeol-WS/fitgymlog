package io.fitgymlog.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.fitgymlog.app.R
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

    fun routine(id: Long) = "routine/$id"
    fun workout(id: Long) = "workout/$id"
    fun exercise(id: String) = "exercise/$id"
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
    Scaffold(
        bottomBar = {
            if (tabs.any { it.route == currentRoute }) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
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
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = if (openActiveWorkout) Routes.ACTIVE else Routes.HOME,
            modifier = Modifier
                .padding(bottom = padding.calculateBottomPadding())
                .consumeWindowInsets(PaddingValues(bottom = padding.calculateBottomPadding())),
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onOpenWorkout = { navController.navigate(Routes.ACTIVE) { launchSingleTop = true } },
                    onEditRoutine = { navController.navigate(Routes.routine(it)) },
                    onOpenBandGuide = { navController.navigate(Routes.BAND_GUIDE) },
                )
            }
            composable(Routes.HISTORY) { HistoryScreen(onOpenWorkout = { navController.navigate(Routes.workout(it)) }) }
            composable(Routes.SETTINGS) { SettingsScreen(onOpenBandGuide = { navController.navigate(Routes.BAND_GUIDE) }) }
            composable(Routes.BAND_GUIDE) { BandGuideScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.ACTIVE) {
                ActiveWorkoutScreen(
                    onBack = { if (!navController.popBackStack()) navController.navigate(Routes.HOME) },
                    onFinished = { savedId ->
                        navController.navigate(savedId?.let(Routes::workout) ?: Routes.HOME) {
                            popUpTo(Routes.ACTIVE) { inclusive = true }
                        }
                    },
                    onOpenExercise = { navController.navigate(Routes.exercise(it)) },
                )
            }
            composable(Routes.ROUTINE, listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                RoutineEditorScreen(routineId = entry.arguments?.getLong("id") ?: 0L, onDone = { navController.popBackStack() })
            }
            composable(Routes.WORKOUT, listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                WorkoutDetailScreen(
                    workoutId = entry.arguments?.getLong("id") ?: 0L,
                    onBack = { if (!navController.popBackStack()) navController.navigate(Routes.HOME) },
                    onOpenExercise = { navController.navigate(Routes.exercise(it)) },
                )
            }
            composable(Routes.EXERCISE, listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                ExerciseDetailScreen(exerciseId = entry.arguments?.getString("id").orEmpty(), onBack = { navController.popBackStack() })
            }
        }
    }
}
