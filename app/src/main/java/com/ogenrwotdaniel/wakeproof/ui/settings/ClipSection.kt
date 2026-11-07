package com.ogenrwotdaniel.wakeproof.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.BuildConfig
import com.ogenrwotdaniel.wakeproof.clip.ClipConfig
import com.ogenrwotdaniel.wakeproof.clip.ClipHub
import com.ogenrwotdaniel.wakeproof.clip.ClipPairActivity
import com.ogenrwotdaniel.wakeproof.data.sync.AccountRepository
import com.ogenrwotdaniel.wakeproof.data.sync.SyncAccount
import com.ogenrwotdaniel.wakeproof.distraction.AppBlockService
import com.ogenrwotdaniel.wakeproof.pomodoro.PomodoroTimerService
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What the clip section shows. */
data class ClipUiState(val config: ClipConfig, val signedIn: Boolean, val uid: String?)

/** Backs [ClipSection]: the clip pairing and its two switches. */
@HiltViewModel
class ClipViewModel @Inject constructor(
    private val hub: ClipHub,
    accounts: AccountRepository
) : ViewModel() {

    val state: StateFlow<ClipUiState> = combine(hub.keys.config, accounts.account) { cfg, account ->
        val uid = (account as? SyncAccount.SignedIn)?.uid
        ClipUiState(cfg, uid != null, uid)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClipUiState(hub.keys.current(), false, null))

    fun setEnabled(value: Boolean) = hub.keys.setEnabled(value)
    fun setSendScreenshots(value: Boolean) = hub.keys.setSendScreenshots(value)
    fun forget() = hub.forget()
}

/**
 * Settings → "Clipboard across devices" (WakeProof Clip): copy on the laptop,
 * paste on the phone or tablet and back. Pairing is a QR scan; focus sessions
 * deliberately do not switch it off.
 */
@Composable
fun ClipSection(
    modifier: Modifier = Modifier,
    viewModel: ClipViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val cfg = state.config
    var confirmForget by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Clipboard across devices", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Copy on your computer, paste here — and the other way: text and images, " +
                "end-to-end encrypted. Sent whenever you return to the home screen, from the " +
                "\"Send clipboard\" Quick Settings tile, or with Share → Send to my devices.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Share the clipboard", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = statusLine(state),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = cfg.enabled, onCheckedChange = viewModel::setEnabled, enabled = cfg.paired)
        }

        if (cfg.paired) {
            // Only the debug manifest declares the permission, so a release
            // build's request would come back refused without asking anyone.
            val canAsk = BuildConfig.DEBUG
            fun photosHeld() =
                context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            var photosGranted by remember { mutableStateOf(photosHeld()) }
            var photosRefused by rememberSaveable { mutableStateOf(false) }
            // Turned on, and still waiting for the grant that lets it take effect.
            var wantScreenshots by rememberSaveable { mutableStateOf(false) }
            val ownSession by PomodoroTimerService.state.collectAsStateWithLifecycle()
            var otherSession by remember { mutableStateOf(false) }
            // The grant may have come from Android Settings.
            LifecycleResumeEffect(Unit) {
                photosGranted = photosHeld()
                otherSession = AppBlockService.otherSessionActive()
                if (photosGranted && wantScreenshots) {
                    wantScreenshots = false
                    photosRefused = false
                    viewModel.setSendScreenshots(true)
                }
                // The switch reads off from here on, so it must also be off: a
                // later grant must not start sending what the screen said would not.
                if (!photosGranted && viewModel.state.value.config.sendScreenshots) {
                    viewModel.setSendScreenshots(false)
                }
                onPauseOrDispose { }
            }
            val askForPhotos = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                photosGranted = granted
                photosRefused = !granted
                if (granted) {
                    wantScreenshots = false
                    viewModel.setSendScreenshots(true)
                }
            }
            val row = clipScreenshotRow(
                stored = cfg.sendScreenshots,
                clipEnabled = cfg.enabled,
                photos = photosGranted,
                canAsk = canAsk,
                refused = photosRefused,
                inSession = ownSession.active || otherSession
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Send screenshots automatically", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = row.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    // Shown off without the grant: nothing is being sent, whatever is stored.
                    checked = row.checked,
                    onCheckedChange = { on ->
                        wantScreenshots = false
                        if (on && !photosGranted) {
                            wantScreenshots = true
                            askForPhotos.launch(Manifest.permission.READ_MEDIA_IMAGES)
                        } else {
                            viewModel.setSendScreenshots(on)
                        }
                    },
                    enabled = row.switchEnabled
                )
            }
            if (row.settingsLink) {
                // Android stops asking after a second refusal, and "selected
                // photos" lands here too: new screenshots would not be among them.
                TextButton(
                    enabled = row.settingsLinkEnabled,
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.fromParts("package", context.packageName, null))
                        )
                    }
                ) { Text("Allow all photos in Android Settings") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { context.startActivity(Intent(context, ClipPairActivity::class.java)) }) {
                Text(if (cfg.paired) "Pair again" else "Pair a computer")
            }
            if (cfg.paired) {
                OutlinedButton(onClick = { confirmForget = true }) { Text("Forget pairing") }
            }
        }
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget pairing?") },
            text = {
                Text(
                    "This device deletes its clip key and stops sharing the clipboard. " +
                        "Your other devices are not affected; pair again to rejoin."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.forget()
                    confirmForget = false
                }) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } }
        )
    }
}

private fun statusLine(state: ClipUiState): String {
    val cfg = state.config
    if (!cfg.paired) return "Not paired."
    val who = "Paired with ${cfg.computerName ?: "a computer"}"
    val how = if (state.signedIn) {
        "nearby, else through your cloud account"
    } else {
        "LAN only (sign in under Sync for the cloud fallback)"
    }
    val mismatch = cfg.computerUid != null && state.uid != null && cfg.computerUid != state.uid
    return buildString {
        append(who).append(" · ").append(how)
        if (!cfg.enabled) append(" · off")
        if (mismatch) append(" · the computer is signed in to a different account, so the cloud copy won't reach it")
        append('.')
    }
}
