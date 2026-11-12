package com.ogenrwotdaniel.wakeproof.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ogenrwotdaniel.wakeproof.ui.agenda.AgendaScreen
import com.ogenrwotdaniel.wakeproof.ui.flashcards.FlashcardsScreen
import com.ogenrwotdaniel.wakeproof.ui.forest.ForestScreen
import com.ogenrwotdaniel.wakeproof.ui.home.HomeScreen
import com.ogenrwotdaniel.wakeproof.ui.onboarding.OnboardingScreen
import com.ogenrwotdaniel.wakeproof.ui.plans.SessionSummariesScreen
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
    openTimer: Int = 0
) {
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
                )
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
