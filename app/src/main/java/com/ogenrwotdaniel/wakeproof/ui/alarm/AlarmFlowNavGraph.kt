package com.ogenrwotdaniel.wakeproof.ui.alarm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ogenrwotdaniel.wakeproof.data.db.AlarmType
import com.ogenrwotdaniel.wakeproof.ui.navigation.AlarmRingingRoute
import com.ogenrwotdaniel.wakeproof.ui.navigation.PushupVerifyRoute
import com.ogenrwotdaniel.wakeproof.ui.navigation.WaterVerifyRoute
import com.ogenrwotdaniel.wakeproof.ui.verify.PushupVerifyScreen
import com.ogenrwotdaniel.wakeproof.ui.verify.WaterVerifyScreen

/**
 * Navigation for the lock-screen alarm flow: ringing → the verification screen
 * matching the alarm type. Lives in [com.ogenrwotdaniel.wakeproof.alarm.AlarmRingingActivity],
 * separate from the main app graph.
 */
@Composable
fun AlarmFlowNavGraph(
    viewModel: AlarmRingingViewModel,
    onVerified: () -> Unit,
    onEmergencyDismiss: () -> Unit,
    onSnooze: () -> Unit
) {
    val navController = rememberNavController()
    val alarm by viewModel.alarm.collectAsStateWithLifecycle()
    val snoozesLeft by viewModel.snoozesLeft.collectAsStateWithLifecycle()

    NavHost(navController = navController, startDestination = AlarmRingingRoute()) {
        composable<AlarmRingingRoute> {
            AlarmRingingScreen(
                alarm = alarm,
                onStartVerification = { type ->
                    when (type) {
                        AlarmType.WATER -> navController.navigate(WaterVerifyRoute)
                        else -> navController.navigate(PushupVerifyRoute)
                    }
                }
            )
        }
        composable<PushupVerifyRoute> {
            PushupVerifyScreen(
                onVerified = onVerified,
                onEmergencyDismiss = onEmergencyDismiss,
                snoozesLeft = snoozesLeft,
                onSnooze = { viewModel.snooze(onSnooze) }
            )
        }
        composable<WaterVerifyRoute> {
            WaterVerifyScreen(
                onVerified = onVerified,
                onEmergencyDismiss = onEmergencyDismiss,
                snoozesLeft = snoozesLeft,
                onSnooze = { viewModel.snooze(onSnooze) }
            )
        }
    }
}
