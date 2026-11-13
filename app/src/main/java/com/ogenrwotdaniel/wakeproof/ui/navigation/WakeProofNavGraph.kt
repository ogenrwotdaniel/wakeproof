package com.ogenrwotdaniel.wakeproof.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import kotlinx.coroutines.flow.first
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ogenrwotdaniel.wakeproof.ui.agenda.AgendaScreen
import com.ogenrwotdaniel.wakeproof.ui.flashcards.FlashcardsScreen
import com.ogenrwotdaniel.wakeproof.ui.forest.ForestScreen
import com.ogenrwotdaniel.wakeproof.ui.home.HomeScreen
import com.ogenrwotdaniel.wakeproof.ui.onboarding.OnboardingScreen
import com.ogenrwotdaniel.wakeproof.ui.plans.SessionSummariesScreen
import com.ogenrwotdaniel.wakeproof.ui.plans.TopThreeScreen
import com.ogenrwotdaniel.wakeproof.ui.pomodoro.PomodoroScreen
import com.ogenrwotdaniel.wakeproof.ui.pomodoro.SummaryCaptureScreen
import com.ogenrwotdaniel.wakeproof.ui.settings.SettingsScreen
import com.ogenrwotdaniel.wakeproof.ui.usage.UsageScreen

/**
 * Main in-app navigation graph. The lock-screen alarm flow (ringing +
 * verification) lives in AlarmRingingActivity's own graph, not here.
 */
@Composable
fun WakeProofNavGraph(
    startDestination: Any = HomeRoute,
    navController: NavHostController = rememberNavController(),
    immersiveFocus: Boolean = false,
    /** Bumped by a notification that asks for the focus timer; 0 = never asked. */
    openTimer: Int = 0,
    /** Bumped when a break ends and the full-screen timer comes back; 0 = never. */
    landTimer: Int = 0
) {
    // Only from Home: any other screen (flashcards, a summary photo, a setting
    // half typed) is work in hand that a pop would lose. The timer waits under it.
    androidx.compose.runtime.LaunchedEffect(landTimer) {
        if (landTimer > 0) runCatching {
            // Started cold, the graph is not set yet: wait for its first screen.
            val at = kotlinx.coroutines.withTimeoutOrNull(3_000L) {
                navController.currentBackStackEntryFlow.first()
            }?.destination ?: navController.currentDestination
            if (at?.hasRoute<HomeRoute>() == true) {
                navController.navigate(PomodoroRoute) { launchSingleTop = true }
            }
        }
    }
    // After the graph is set below. Back to the timer when it is already under
    // the screen in front (the capture screen, the library): a second timer on
    // top would leave a camera running beneath it.
    androidx.compose.runtime.LaunchedEffect(openTimer) {
        if (openTimer > 0) runCatching {
            if (!navController.popBackStack(PomodoroRoute, inclusive = false)) {
                navController.navigate(PomodoroRoute) { launchSingleTop = true }
            }
        }
    }
    AdaptiveShell(navController = navController, immersiveFocus = immersiveFocus) {
        NavHost(navController = navController, startDestination = startDestination) {
            composable<HomeRoute> {
                HomeScreen(
                    onOpenOnboarding = { navController.navigate(OnboardingRoute) },
                    onOpenPomodoro = { navController.navigate(PomodoroRoute) },
                    onOpenSettings = { navController.navigate(SettingsRoute) },
                    onOpenUsage = { navController.navigate(UsageRoute) },
                    onOpenAgenda = { navController.navigate(AgendaRoute) },
                    onOpenFlashcards = { navController.navigate(FlashcardsRoute) },
                    onOpenForest = { navController.navigate(ForestRoute) },
                    onOpenSummaries = { navController.navigate(SessionSummariesRoute) },
                    onOpenTopThree = { navController.navigate(TopThreeRoute) { launchSingleTop = true } },
                )
            }
            composable<TopThreeRoute> {
                // Popped by name: Save and a second tap must not pop the screen under it too.
                TopThreeScreen(onFinish = { navController.popBackStack(TopThreeRoute, inclusive = true) })
            }
            composable<OnboardingRoute> {
                OnboardingScreen(
                    onDone = {
                        navController.navigate(HomeRoute) { popUpTo(0) { inclusive = true } }
                    }
                )
            }
            composable<PomodoroRoute> {
                PomodoroScreen(
                    onBack = { navController.popBackStack() },
                    onPhotographSummary = { navController.navigate(SummaryCaptureRoute) },
                    onOpenSummaries = { navController.navigate(SessionSummariesRoute) }
                )
            }
            composable<SummaryCaptureRoute> {
                // Popped by name: a second tap must not pop the screen under it too.
                SummaryCaptureScreen(onFinish = { navController.popBackStack(SummaryCaptureRoute, inclusive = true) })
            }
            composable<SessionSummariesRoute> {
                SessionSummariesScreen(
                    onBack = { navController.popBackStack() },
                    onAdd = { navController.navigate(SummaryCaptureRoute) }
                )
            }
            composable<SettingsRoute> {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
            composable<UsageRoute> {
                UsageScreen(onBack = { navController.popBackStack() })
            }
            composable<AgendaRoute> {
                AgendaScreen(onBack = { navController.popBackStack() })
            }
            composable<FlashcardsRoute> {
                FlashcardsScreen(onBack = { navController.popBackStack() })
            }
            composable<ForestRoute> {
                ForestScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
