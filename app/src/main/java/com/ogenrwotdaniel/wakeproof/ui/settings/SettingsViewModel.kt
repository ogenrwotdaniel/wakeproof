package com.ogenrwotdaniel.wakeproof.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ogenrwotdaniel.wakeproof.data.KimiRepository
import com.ogenrwotdaniel.wakeproof.data.KimiResult
import com.ogenrwotdaniel.wakeproof.data.remote.ApiKeyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Settings state. Alarm/goal settings join in the dashboard phase. */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val keyStore: ApiKeyStore,
    private val kimiRepository: KimiRepository
) : ViewModel() {

    data class UiState(
        val hasKey: Boolean = false,
        val testing: Boolean = false,
        val testResult: String? = null
    )

    private val _uiState = MutableStateFlow(UiState(hasKey = keyStore.hasKey()))
    val uiState: StateFlow<UiState> = _uiState

    fun saveKey(key: String) {
        if (key.isBlank()) return
        keyStore.setKey(key)
        _uiState.update { it.copy(hasKey = true, testResult = null) }
    }

    fun clearKey() {
        keyStore.clear()
        _uiState.update { it.copy(hasKey = false, testResult = null) }
    }

    fun testConnection() {
        _uiState.update { it.copy(testing = true, testResult = null) }
        viewModelScope.launch {
            val message = when (val result = kimiRepository.testConnection()) {
                is KimiResult.Success -> "Connected — the key works."
                is KimiResult.ApiError ->
                    "API error${result.code?.let { " $it" } ?: ""}: ${result.message}"
                KimiResult.NetworkError -> "Network error — check your connection."
                KimiResult.NoKey -> "Save a key first."
            }
            _uiState.update { it.copy(testing = false, testResult = message) }
        }
    }
}
