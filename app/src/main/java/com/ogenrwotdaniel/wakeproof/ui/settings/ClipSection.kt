package com.ogenrwotdaniel.wakeproof.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.clip.ClipConfig
import com.ogenrwotdaniel.wakeproof.clip.ClipHub
import com.ogenrwotdaniel.wakeproof.clip.ClipPairActivity
import com.ogenrwotdaniel.wakeproof.data.sync.AccountRepository
import com.ogenrwotdaniel.wakeproof.data.sync.SyncAccount
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
            val photosGranted = remember {
                context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Send screenshots automatically", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (photosGranted) {
                            "Every new screenshot goes straight to your other devices' clipboards."
                        } else {
                            "Needs photo access, which only the sideloaded build holds."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = cfg.sendScreenshots,
                    onCheckedChange = viewModel::setSendScreenshots,
                    enabled = cfg.enabled
                )
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
