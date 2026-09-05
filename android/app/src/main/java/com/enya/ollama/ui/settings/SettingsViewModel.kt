package com.enya.ollama.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enya.ollama.data.SettingsRepository
import com.enya.ollama.data.repo.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ConnectionTestStatus {
    data object Idle : ConnectionTestStatus
    data object Testing : ConnectionTestStatus
    data class Success(val modelCount: Int) : ConnectionTestStatus
    data class Failure(val message: String) : ConnectionTestStatus
}

data class SettingsUiState(
    val serverUrl: String = "",
    val testStatus: ConnectionTestStatus = ConnectionTestStatus.Idle
)

class SettingsViewModel(
    private val repo: ChatRepository,
    private val settings: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settings.serverUrl.collect { url -> _uiState.update { it.copy(serverUrl = url) } }
        }
    }

    fun updateServerUrlDraft(url: String) {
        _uiState.update { it.copy(serverUrl = url, testStatus = ConnectionTestStatus.Idle) }
    }

    fun save() {
        viewModelScope.launch { settings.setServerUrl(_uiState.value.serverUrl) }
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(testStatus = ConnectionTestStatus.Testing) }
            settings.setServerUrl(_uiState.value.serverUrl)
            repo.fetchModels()
                .onSuccess { models ->
                    _uiState.update { it.copy(testStatus = ConnectionTestStatus.Success(models.size)) }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(testStatus = ConnectionTestStatus.Failure(e.message ?: "Connection failed"))
                    }
                }
        }
    }
}
