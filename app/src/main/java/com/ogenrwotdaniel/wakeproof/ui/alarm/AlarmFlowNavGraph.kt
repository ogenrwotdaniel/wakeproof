package com.ogenrwotdaniel.wakeproof.ui.alarm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ogenrwotdaniel.wakeproof.data.db.AlarmType
import com.ogenrwotdaniel.wakeproof.data.db.GoalKind
import com.ogenrwotdaniel.wakeproof.ui.navigation.AlarmRingingRoute
import com.ogenrwotdaniel.wakeproof.ui.navigation.MathVerifyRoute
import com.ogenrwotdaniel.wakeproof.ui.navigation.PushupVerifyRoute
import com.ogenrwotdaniel.wakeproof.ui.navigation.ReviewVerifyRoute
import com.ogenrwotdaniel.wakeproof.ui.verify.MathVerifyScreen
import com.ogenrwotdaniel.wakeproof.ui.verify.PushupVerifyScreen
import com.ogenrwotdaniel.wakeproof.ui.verify.ReviewVerifyScreen

/**
 * Navigation for the lock-screen alarm flow: ringing → the verification screen
 * matching the alarm type. [onVerified] silences the alarm the moment the goal
 * is met; [onFinish] closes the flow once the success screen has been shown.
 * Lives in [com.ogenrwotdaniel.wakeproof.alarm.AlarmRingingActivity],
 * separate from the main app graph.
 */
@Composable
fun AlarmFlowNavGraph(
    viewModel: AlarmRingingViewModel,
    onVerified: () -> Unit,
    onFinish: () -> Unit,
    onEmergencyDismiss: () -> Unit,
    onSnooze: () -> Unit
) {
    val navController = rememberNavController()
    val alarm by viewModel.alarm.collectAsStateWithLifecycle()
    val snoozesLeft by viewModel.snoozesLeft.collectAsStateWithLifecycle()
    val strict by viewModel.strict.collectAsStateWithLifecycle()
    // Sums standing in for a review alarm with no card due are logged as the
    // review. Kept by alarm id: this screen is reused when another alarm fires
    // (onNewIntent), and that one's sums are its own.
    var reviewStandInFor by rememberSaveable { mutableStateOf(NO_STAND_IN) }
    val alarmId = alarm?.id ?: -1L

    NavHost(navController = navController, startDestination = AlarmRingingRoute()) {
        composable<AlarmRingingRoute> {
            AlarmRingingScreen(
                alarm = alarm,
                onStartVerification = { type ->
                    when (type) {
                        AlarmType.REVIEW -> navController.navigate(ReviewVerifyRoute)
                        AlarmType.MATH -> {
                            reviewStandInFor = NO_STAND_IN
                            navController.navigate(MathVerifyRoute)
                        }
                        else -> navController.navigate(PushupVerifyRoute)
                    }
                }
            )
        }
        composable<PushupVerifyRoute> {
            PushupVerifyScreen(
                onVerified = onVerified,
                onFinish = onFinish,
                onEmergencyDismiss = onEmergencyDismiss,
                snoozesLeft = snoozesLeft,
                onSnooze = { viewModel.snooze(onSnooze) },
                strict = strict,
                onCameraFallback = {
                    reviewStandInFor = NO_STAND_IN
                    navController.navigate(MathVerifyRoute)
                }
            )
        }
        composable<MathVerifyRoute> {
            MathVerifyScreen(
                onVerified = onVerified,
                onFinish = onFinish,
                onEmergencyDismiss = onEmergencyDismiss,
                snoozesLeft = snoozesLeft,
                onSnooze = { viewModel.snooze(onSnooze) },
                strict = strict,
                goalKind = if (reviewStandInFor == alarmId) GoalKind.REVIEW else GoalKind.MATH
            )
        }
        composable<ReviewVerifyRoute> {
            ReviewVerifyScreen(
                onVerified = onVerified,
                onFinish = onFinish,
                onEmergencyDismiss = onEmergencyDismiss,
                snoozesLeft = snoozesLeft,
                onSnooze = { viewModel.snooze(onSnooze) },
                // No card is due (or the cards cannot be read before the first
                // unlock): sums instead, never a free exit.
                onNoCards = {
                    reviewStandInFor = alarmId
                    navController.navigate(MathVerifyRoute) {
                        popUpTo<ReviewVerifyRoute> { inclusive = true }
                    }
                }
            )
        }
    }
}

/** No alarm's sums stand in for a review; distinct from -1, the id of an alarm that could not be read. */
private const val NO_STAND_IN = Long.MIN_VALUE
