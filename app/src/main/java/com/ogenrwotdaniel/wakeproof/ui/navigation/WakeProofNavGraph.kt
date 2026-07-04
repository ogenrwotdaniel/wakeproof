package com.ogenrwotdaniel.wakeproof.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.ogenrwotdaniel.wakeproof.ui.alarm.AlarmRingingScreen
import com.ogenrwotdaniel.wakeproof.ui.home.HomeScreen
import com.ogenrwotdaniel.wakeproof.ui.onboarding.OnboardingScreen
import com.ogenrwotdaniel.wakeproof.ui.pomodoro.PomodoroScreen
import com.ogenrwotdaniel.wakeproof.ui.settings.SettingsScreen
import com.ogenrwotdaniel.wakeproof.ui.verify.PushupVerifyScreen
import com.ogenrwotdaniel.wakeproof.ui.verify.WaterVerifyScreen

/**
 * Single navigation graph for the whole app.
 */
@Composable
fun WakeProofNavGraph(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = HomeRoute) {
        composable<HomeRoute> {
            HomeScreen(
                onOpenOnboarding = { navController.navigate(OnboardingRoute) },
                onOpenAlarmRinging = { navController.navigate(AlarmRingingRoute()) },
                onOpenPushupVerify = { navController.navigate(PushupVerifyRoute) },
                onOpenWaterVerify = { navController.navigate(WaterVerifyRoute) },
                onOpenPomodoro = { navController.navigate(PomodoroRoute) },
                onOpenSettings = { navController.navigate(SettingsRoute) }
            )
        }
        composable<OnboardingRoute> {
            OnboardingScreen(onDone = { navController.popBackStack() })
        }
        composable<AlarmRingingRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<AlarmRingingRoute>()
            AlarmRingingScreen(
                alarmId = route.alarmId,
                onStartPushupVerification = { navController.navigate(PushupVerifyRoute) },
                onStartWaterVerification = { navController.navigate(WaterVerifyRoute) }
            )
        }
        composable<PushupVerifyRoute> {
            PushupVerifyScreen(onVerified = { navController.popBackStack(HomeRoute, inclusive = false) })
        }
        composable<WaterVerifyRoute> {
            WaterVerifyScreen(onVerified = { navController.popBackStack(HomeRoute, inclusive = false) })
        }
        composable<PomodoroRoute> {
            PomodoroScreen(onBack = { navController.popBackStack() })
        }
        composable<SettingsRoute> {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
