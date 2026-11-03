package com.ogenrwotdaniel.wakeproof.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ogenrwotdaniel.wakeproof.admin.DeviceLock
import com.ogenrwotdaniel.wakeproof.admin.ReleaseState
import com.ogenrwotdaniel.wakeproof.admin.releaseState
import com.ogenrwotdaniel.wakeproof.ui.formatAlarmTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/**
 * "Uninstall lock": shown only while WakeProof is device owner
 * ([DeviceLock]). Says the app cannot be removed, and offers the one way out
 * that is not a factory reset — a release that waits [DeviceLock.RELEASE_DELAY_DAYS] days.
 */
@Composable
fun UninstallLockSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (!remember { DeviceLock.isDeviceOwner(context) }) return
    var requestedAt by remember { mutableStateOf(DeviceLock.requestedAt(context)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis()
        }
    }
    val state = releaseState(requestedAt, now)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Uninstall lock", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "WakeProof can't be uninstalled, force-stopped or cleared — only a " +
                "factory reset removes it. Releasing the lock waits ${DeviceLock.RELEASE_DELAY_DAYS} days after you ask.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        when (state) {
            ReleaseState.NOT_REQUESTED -> OutlinedButton(onClick = {
                DeviceLock.requestRelease(context)
                requestedAt = DeviceLock.requestedAt(context)
                now = System.currentTimeMillis()
            }) { Text("Ask to release (${DeviceLock.RELEASE_DELAY_DAYS} days)") }

            ReleaseState.WAITING -> {
                val at = Instant.ofEpochMilli((requestedAt ?: now) + DeviceLock.RELEASE_DELAY_MILLIS)
                    .atZone(ZoneId.systemDefault())
                Text(
                    text = "Release possible from ${at.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy"))} " +
                        formatAlarmTime(context, at.hour, at.minute) + ".",
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = {
                    DeviceLock.cancelRelease(context)
                    requestedAt = null
                }) { Text("Keep the lock") }
            }

            ReleaseState.READY -> {
                if (!DeviceLock.networkTime(context)) {
                    Text(
                        text = "Turn on automatic date and time first — the wait is checked against it.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                OutlinedButton(onClick = {
                    val done = DeviceLock.release(context)
                    Toast.makeText(
                        context,
                        if (done) "Released — WakeProof can be uninstalled now." else "Release failed.",
                        Toast.LENGTH_LONG
                    ).show()
                    if (done) requestedAt = null
                }) { Text("Release the lock now") }
                TextButton(onClick = {
                    DeviceLock.cancelRelease(context)
                    requestedAt = null
                }) { Text("Keep the lock") }
            }
        }
    }
}
