package com.ogenrwotdaniel.wakeproof.ui.settings

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.data.sync.AccountRepository
import com.ogenrwotdaniel.wakeproof.data.sync.CloudSyncAvailability
import com.ogenrwotdaniel.wakeproof.data.sync.SignInCancelled
import com.ogenrwotdaniel.wakeproof.data.sync.SyncAccount
import com.ogenrwotdaniel.wakeproof.data.sync.SyncEngine
import com.ogenrwotdaniel.wakeproof.data.sync.SyncPreferences
import com.ogenrwotdaniel.wakeproof.data.sync.SyncResult
import com.ogenrwotdaniel.wakeproof.data.sync.SyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the sync section renders from. */
data class SyncUiState(
    val available: Boolean = false,
    val account: SyncAccount = SyncAccount.SignedOut,
    val enabled: Boolean = false,
    val lastSyncedAtMillis: Long = 0L,
    val lastError: String = "",
    val busy: Boolean = false,
    /** Transient result of the last explicit action, shown once and cleared. */
    val message: String? = null
)

/** Drives the Settings "Sync across devices" section. */
@HiltViewModel
class SyncViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accounts: AccountRepository,
    private val syncEngine: SyncEngine,
    private val preferences: SyncPreferences,
    availability: CloudSyncAvailability
) : ViewModel() {

    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<SyncUiState> = combine(
        accounts.account,
        preferences.enabled,
        preferences.lastSyncedAt,
        preferences.lastError,
        combine(busy, message) { b, m -> b to m }
    ) { account, enabled, lastSyncedAt, lastError, (isBusy, msg) ->
        SyncUiState(
            available = availability.canSignIn,
            account = account,
            enabled = enabled,
            lastSyncedAtMillis = lastSyncedAt,
            lastError = lastError,
            busy = isBusy,
            message = msg
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SyncUiState(available = availability.canSignIn)
    )

    /**
     * Signs in and immediately runs a first pass, so the user sees their other
     * device's data straight away instead of waiting for the hourly worker.
     */
    fun signIn(activity: Activity) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            accounts.signIn(activity)
                .onSuccess {
                    SyncWorker.schedule(context)
                    runSync(announceNoChanges = false)
                }
                .onFailure { error ->
                    message.value = when (error) {
                        // Backing out of the account picker is a choice, not a fault.
                        is SignInCancelled -> error.message
                        else -> "Sign-in failed: ${error.message}"
                    }
                }
            busy.value = false
        }
    }

    fun signOut() {
        viewModelScope.launch {
            busy.value = true
            accounts.signOut()
            SyncWorker.cancel(context)
            message.value = "Signed out. Your data stays on this device."
            busy.value = false
        }
    }

    fun setEnabled(value: Boolean) {
        viewModelScope.launch {
            preferences.setEnabled(value)
            if (value) {
                SyncWorker.schedule(context)
                runSync(announceNoChanges = false)
            } else {
                SyncWorker.cancel(context)
            }
        }
    }

    fun syncNow() {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            runSync(announceNoChanges = true)
            busy.value = false
        }
    }

    fun clearMessage() {
        message.value = null
    }

    private suspend fun runSync(announceNoChanges: Boolean) {
        message.value = when (val result = syncEngine.sync()) {
            is SyncResult.Skipped -> if (announceNoChanges) "Sync is not set up yet." else null
            is SyncResult.Failure -> "Sync failed: ${result.message}"
            is SyncResult.Success -> when {
                result.changed > 0 -> "Synced — ${result.changed} updated from your other devices."
                announceNoChanges -> "Synced. Everything was already up to date."
                else -> null
            }
        }
    }
}
