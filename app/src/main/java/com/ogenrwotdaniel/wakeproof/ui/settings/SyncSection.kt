package com.ogenrwotdaniel.wakeproof.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ogenrwotdaniel.wakeproof.data.sync.SyncAccount
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "Sync across devices": link a Google account and mirror alarms, streak history,
 * flashcards, exams and parked thoughts between this device and the others.
 *
 * Signed out is a fully supported state, not a degraded one — the section says so
 * rather than nagging, because everything else in the app works on-device.
 */
@Composable
fun SyncSection(
    modifier: Modifier = Modifier,
    viewModel: SyncViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findActivity()

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Sync across devices", style = MaterialTheme.typography.titleMedium)

        if (!state.available) {
            Text(
                text = "This build has no cloud configuration, so sync is unavailable. " +
                    "Everything else works on-device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }

        when (val account = state.account) {
            SyncAccount.SignedOut -> {
                Text(
                    text = "Sign in with Google to keep your alarms, streak, flashcards, " +
                        "exams and parked thoughts the same on your phone and tablet. " +
                        "Your data is stored under your own account.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = { activity?.let(viewModel::signIn) },
                    enabled = activity != null && !state.busy
                ) {
                    Text(if (state.busy) "Signing in…" else "Sign in with Google")
                }
            }

            is SyncAccount.SignedIn -> {
                Text(
                    text = "Signed in as ${account.email ?: account.displayName ?: account.uid}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Sync this device", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = state.enabled, onCheckedChange = viewModel::setEnabled)
                }
                Text(
                    text = state.lastSyncedAtMillis.asLastSyncedLabel(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = viewModel::syncNow,
                        enabled = state.enabled && !state.busy
                    ) {
                        Text(if (state.busy) "Syncing…" else "Sync now")
                    }
                    OutlinedButton(onClick = viewModel::signOut, enabled = !state.busy) {
                        Text("Sign out")
                    }
                }
                Text(
                    text = "Your Galaxy Watch can upload its own focus sessions and live " +
                        "timer, so this list stays current when the phone is elsewhere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = { activity?.let(viewModel::signInWatch) },
                    enabled = activity != null && !state.busy
                ) {
                    Text("Sign in the watch")
                }
            }
        }

        state.message?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (text.startsWith("Synced") || text.startsWith("Sent to the watch")) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
        state.lastError.takeIf { it.isNotBlank() && state.message == null }?.let { error ->
            Text(
                text = "Last sync failed: $error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

private fun Long.asLastSyncedLabel(): String = if (this == 0L) {
    "Not synced yet."
} else {
    "Last synced " + LAST_SYNCED_FORMAT.format(
        Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())
    )
}

private val LAST_SYNCED_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")

/**
 * Credential Manager renders into an activity's window, so the section needs the
 * hosting Activity rather than the application context Compose hands out.
 */
private fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
