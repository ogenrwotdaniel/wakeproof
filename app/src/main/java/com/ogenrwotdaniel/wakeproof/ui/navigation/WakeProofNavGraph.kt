package com.ogenrwotdaniel.wakeproof.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ogenrwotdaniel.wakeproof.ui.home.HomeScreen
import com.ogenrwotdaniel.wakeproof.ui.onboarding.OnboardingScreen
import com.ogenrwotdaniel.wakeproof.ui.pomodoro.PomodoroScreen
import com.ogenrwotdaniel.wakeproof.ui.settings.SettingsScreen

/**
 * Main in-app navigation graph. The lock-screen alarm flow (ringing +
 * verification) lives in AlarmRingingActivity's own graph, not here.
 */
@Composable
fun WakeProofNavGraph(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = HomeRoute) {
        composable<HomeRoute> {
            HomeScreen(
                onOpenOnboarding = { navController.navigate(OnboardingRoute) },
                onOpenPomodoro = { navController.navigate(PomodoroRoute) },
                onOpenSettings = { navController.navigate(SettingsRoute) }
            )
        }
        composable<OnboardingRoute> {
            OnboardingScreen(onDone = { navController.popBackStack() })
        }
        composable<PomodoroRoute> {
            PomodoroScreen(onBack = { navController.popBackStack() })
        }
        composable<SettingsRoute> {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
