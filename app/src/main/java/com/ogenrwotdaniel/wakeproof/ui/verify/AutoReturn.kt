package com.ogenrwotdaniel.wakeproof.ui.verify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Seconds the success screen stays up before the phone goes back to normal. */
const val AUTO_RETURN_SECONDS = 5

/**
 * The bottom of a verification success screen: a short countdown, then
 * [onFinish] runs by itself.
 *
 * The goal is met and the alarm already silenced by the time this shows, so a
 * required tap was one more thing standing between the user and their morning —
 * and a phone left on the counter after the last pushup sat on the alarm screen
 * indefinitely. The button stays for anyone who has read the line and wants out
 * sooner.
 */
@Composable
fun AutoReturnFooter(onFinish: () -> Unit, modifier: Modifier = Modifier) {
    val finish by rememberUpdatedState(onFinish)
    var secondsLeft by remember { mutableIntStateOf(AUTO_RETURN_SECONDS) }
    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft--
        }
        finish()
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Alarm off. Closing in $secondsLeft s…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = finish, modifier = Modifier.fillMaxWidth()) {
            Text("Done")
        }
    }
}
