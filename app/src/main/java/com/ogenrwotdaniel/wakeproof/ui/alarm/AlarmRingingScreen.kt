package com.ogenrwotdaniel.wakeproof.ui.alarm

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ogenrwotdaniel.wakeproof.data.db.Alarm
import com.ogenrwotdaniel.wakeproof.data.db.AlarmType
import com.ogenrwotdaniel.wakeproof.ui.formatAlarmTime
import java.time.LocalTime
import kotlinx.coroutines.delay

/**
 * The ringing screen. Deliberately has NO dismiss button — the single exit is
 * starting the verification for this alarm's type. Back presses are swallowed.
 *
 * The snooze deliberately is NOT offered here: it lives one screen in, on the
 * verify screens, so taking it costs a tap towards the thing being avoided.
 * This screen never needed it anyway — it already shows a way forward, whereas
 * the verify screens hide the emergency dismiss until its gates open and so read
 * as having no exit at all.
 */
@Composable
fun AlarmRingingScreen(
    alarm: Alarm?,
    onStartVerification: (AlarmType) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { /* There is no way back. That's the point. */ }

    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(1_000)
        }
    }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = formatAlarmTime(LocalContext.current, now.hour, now.minute),
                style = MaterialTheme.typography.displayLarge
            )
            Text(
                text = alarm?.label?.ifBlank { null } ?: "Alarm",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = when (alarm?.type) {
                    AlarmType.WATER -> "Drink your water and prove it with the camera."
                    AlarmType.PUSHUP -> "Do your pushups in front of the camera."
                    else -> "Complete your verification to silence the alarm."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(48.dp))
            Button(
                onClick = { onStartVerification(alarm?.type ?: AlarmType.PUSHUP) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start verification", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "No dismiss button. Verification is the way out; a snooze only delays it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
