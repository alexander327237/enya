package com.enya.ollama.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enya.ollama.data.SettingsRepository
import com.enya.ollama.data.repo.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
    val authHeader: String = "",
    val savedServerUrl: String = "",
    val savedAuthHeader: String = "",
    val testStatus: ConnectionTestStatus = ConnectionTestStatus.Idle
) {
    val isDirty: Boolean get() = serverUrl != savedServerUrl || authHeader != savedAuthHeader
}

class SettingsViewModel(
    private val repo: ChatRepository,
    private val settings: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(settings.serverUrl, settings.authHeader) { url, auth -> url to (auth ?: "") }
                .collect { (url, auth) ->
                    _uiState.update {
                        it.copy(
                            serverUrl = url,
                            authHeader = auth,
                            savedServerUrl = url,
                            savedAuthHeader = auth
                        )
                    }
                }
        }
    }

    fun updateServerUrlDraft(url: String) {
        _uiState.update { it.copy(serverUrl = url, testStatus = ConnectionTestStatus.Idle) }
    }

    fun updateAuthHeaderDraft(value: String) {
        _uiState.update { it.copy(authHeader = value, testStatus = ConnectionTestStatus.Idle) }
    }

    fun save() {
        viewModelScope.launch { persist() }
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(testStatus = ConnectionTestStatus.Testing) }
            persist()
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

    private suspend fun persist() {
        val state = _uiState.value
        settings.setServerUrl(state.serverUrl)
        settings.setAuthHeader(state.authHeader)
        _uiState.update { it.copy(savedServerUrl = state.serverUrl, savedAuthHeader = state.authHeader) }
    }
}
