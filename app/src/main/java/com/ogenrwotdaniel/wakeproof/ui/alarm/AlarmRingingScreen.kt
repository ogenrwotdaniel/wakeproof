package com.ogenrwotdaniel.wakeproof.ui.alarm

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

import com.ogenrwotdaniel.wakeproof.ui.components.PlaceholderScreen

/**
 * Shown while an alarm rings. There is deliberately NO dismiss button here —
 * the only way out is starting a verification. The lock-screen presentation and
 * ringtone service arrive with the alarm engine phase.
 */
@Composable
fun AlarmRingingScreen(
    alarmId: Long,
    onStartPushupVerification: () -> Unit,
    onStartWaterVerification: () -> Unit,
    modifier: Modifier = Modifier
) {
    PlaceholderScreen(
        title = "Alarm ringing",
        description = "Alarm #$alarmId is ringing. The only way to silence it is to verify.",
        modifier = modifier
    ) {
        Button(onClick = onStartPushupVerification, modifier = Modifier.fillMaxWidth()) {
            Text("Start pushup verification")
        }
        Button(onClick = onStartWaterVerification, modifier = Modifier.fillMaxWidth()) {
            Text("Start water verification")
        }
    }
}
