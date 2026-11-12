package com.ogenrwotdaniel.wakeproof.ui.pomodoro

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.study.SessionSummaryStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Photographs the page a focus session's summary was written on: take it, look
 * at it once, keep it. Kept in [SessionSummaryStore]; nothing reads or judges
 * the page.
 *
 * The summary holds the focus wall until a photo is kept, so a camera that
 * cannot take one (broken, permission refused) or a photo that cannot be saved
 * opens [finishWithoutPhoto]: never a wall with no way out.
 */
@HiltViewModel
class SummaryCaptureViewModel @Inject constructor(
    private val store: SessionSummaryStore,
    private val wearStatsSync: com.ogenrwotdaniel.wakeproof.wear.WearStatsSync,
    private val liveSummaryHold: com.ogenrwotdaniel.wakeproof.data.sync.LiveSummaryHold
) : ViewModel() {

    enum class Step { CAPTURE, CONFIRM, SAVING, DONE }

    data class UiState(
        val step: Step = Step.CAPTURE,
        val photo: ByteArray? = null,
        /** The camera failed to start or to take a photo; offered a retry. */
        val cameraFailed: Boolean = false,
        /** The last photo could not be written to storage. */
        val saveFailed: Boolean = false,
        /** Finishing without a photo was asked for before the writing time was up. */
        val tooEarly: Boolean = false,
        /** The photo was kept, but inside the writing time: the summary is still owed. */
        val stillOwed: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    /** The way out when no photo can be taken or kept: the summary is let go without one. */
    fun finishWithoutPhoto() {
        viewModelScope.launch {
            // Refused inside the ten minutes of writing; the screen says so.
            val owedHere = store.load().pending != null
            if (store.release()) {
                if (owedHere) tellPeers()
                _uiState.update { it.copy(step = Step.DONE, photo = null) }
            } else {
                _uiState.update { it.copy(tooEarly = true) }
            }
        }
    }

    fun onCameraUnavailable() {
        _uiState.update { it.copy(cameraFailed = true) }
    }

    fun retryCamera() {
        _uiState.update { it.copy(cameraFailed = false) }
    }

    fun onCaptured(bytes: ByteArray) {
        if (_uiState.value.step != Step.CAPTURE) return
        _uiState.update { it.copy(step = Step.CONFIRM, photo = bytes, cameraFailed = false) }
    }

    fun onRetake() {
        if (_uiState.value.step != Step.CONFIRM) return
        _uiState.update { it.copy(step = Step.CAPTURE, photo = null) }
    }

    fun onKeep() {
        val state = _uiState.value
        val photo = state.photo
        // The step check is what makes a double tap save one photo, not two.
        if (state.step != Step.CONFIRM || photo == null) return
        _uiState.update { it.copy(step = Step.SAVING) }
        viewModelScope.launch {
            val owedHere = runCatching { store.load().pending != null }.getOrDefault(false)
            val saved = try {
                store.submit(photo)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "session summary not saved", e)
                false
            }
            val stillOwed = saved && runCatching {
                store.owed(store.load()) != null
            }.getOrDefault(false)
            // Only once it is really settled: a photo kept inside the writing
            // time must not free the tablet while the phone still owes it.
            if (saved && !stillOwed && owedHere) tellPeers()
            _uiState.update {
                if (saved) it.copy(step = Step.DONE, photo = null, saveFailed = false, stillOwed = stillOwed)
                else it.copy(step = Step.CONFIRM, saveFailed = true)
            }
        }
    }

    /**
     * Settled: the tablet and the watch may start sessions again at once. Only
     * by the device that owed it: a photo kept on the tablet, where nothing is
     * owed, must not clear the phone's hold.
     * Before the screen is told it is done, and not cancellable: done closes
     * the screen, which ends this scope, and a push cut short would leave the
     * watch refusing starts for the rest of the half hour.
     */
    private suspend fun tellPeers() = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
        liveSummaryHold.publish(0L)
        runCatching { wearStatsSync.push() }
    }

    private companion object {
        const val TAG = "SummaryCapture"
    }
}
