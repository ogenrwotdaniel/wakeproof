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
import com.ogenrwotdaniel.wakeproof.ui.plans.DayPlansScreen
import com.ogenrwotdaniel.wakeproof.ui.pomodoro.PomodoroScreen
import com.ogenrwotdaniel.wakeproof.ui.settings.SettingsScreen
import com.ogenrwotdaniel.wakeproof.ui.usage.UsageScreen
import com.ogenrwotdaniel.wakeproof.ui.verify.PlanVerifyScreen
import com.ogenrwotdaniel.wakeproof.ui.verify.ShutdownVerifyScreen

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
                    onOpenForest = { navController.navigate(ForestRoute) },
                    onOpenPlans = { navController.navigate(DayPlansRoute) },
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
            composable<ForestRoute> {
                ForestScreen(onBack = { navController.popBackStack() })
            }
            composable<DayPlansRoute> {
                DayPlansScreen(
                    onBack = { navController.popBackStack() },
                    onAddPlan = { navController.navigate(PlanCaptureRoute) },
                    onReviewDay = { navController.navigate(ShutdownNowRoute) }
                )
            }
            composable<ShutdownNowRoute> {
                ShutdownVerifyScreen(
                    onVerified = {},
                    onFinish = { navController.popBackStack(DayPlansRoute, inclusive = false) },
                    onEmergencyDismiss = {},
                    snoozesLeft = 0,
                    onSnooze = {},
                    ringing = false
                )
            }
            composable<PlanCaptureRoute> {
                // The alarm's own screen, without an alarm behind it.
                PlanVerifyScreen(
                    onVerified = {},
                    // To the library by name: a second tap must not pop that too.
                    onFinish = { navController.popBackStack(DayPlansRoute, inclusive = false) },
                    onEmergencyDismiss = {},
                    snoozesLeft = 0,
                    onSnooze = {},
                    ringing = false
                )
            }
        }
    }
}
