package com.ogenrwotdaniel.wakeproof.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ogenrwotdaniel.wakeproof.ui.agenda.AgendaScreen
import com.ogenrwotdaniel.wakeproof.ui.corpus.CorpusCaptureScreen
import com.ogenrwotdaniel.wakeproof.ui.flashcards.FlashcardsScreen
import com.ogenrwotdaniel.wakeproof.ui.home.HomeScreen
import com.ogenrwotdaniel.wakeproof.ui.onboarding.OnboardingScreen
import com.ogenrwotdaniel.wakeproof.ui.pomodoro.PomodoroScreen
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
    immersiveFocus: Boolean = false
) {
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
                    onOpenCorpusCapture = { navController.navigate(CorpusCaptureRoute) }
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
                PomodoroScreen(onBack = { navController.popBackStack() })
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
            composable<CorpusCaptureRoute> {
                CorpusCaptureScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
