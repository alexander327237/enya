package com.enya.ollama.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enya.ollama.data.db.ChatEntity
import com.enya.ollama.data.db.MessageEntity
import com.enya.ollama.data.repo.ChatRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val chat: ChatEntity? = null,
    val messages: List<MessageEntity> = emptyList(),
    val availableModels: List<String> = emptyList(),
    val modelsError: String? = null,
    val isSending: Boolean = false,
    val isModelPickerOpen: Boolean = false
)

class ChatViewModel(
    private val repo: ChatRepository
) : ViewModel() {

    private var chatId: Long = -1
    private var loaded = false

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var sendJob: Job? = null

    fun open(chatId: Long) {
        if (loaded && this.chatId == chatId) return
        loaded = true
        this.chatId = chatId

        viewModelScope.launch {
            repo.observeChat(chatId).collect { chat ->
                _uiState.update { it.copy(chat = chat) }
            }
        }
        viewModelScope.launch {
            repo.observeMessages(chatId).collect { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }
        refreshModels()
    }

    fun refreshModels() {
        viewModelScope.launch {
            repo.fetchModels()
                .onSuccess { models -> _uiState.update { it.copy(availableModels = models, modelsError = null) } }
                .onFailure { e -> _uiState.update { it.copy(modelsError = e.message ?: "Failed to load models") } }
        }
    }

    fun setModelPickerOpen(open: Boolean) {
        _uiState.update { it.copy(isModelPickerOpen = open) }
    }

    fun switchModel(model: String) {
        viewModelScope.launch { repo.switchModel(chatId, model) }
        setModelPickerOpen(false)
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || _uiState.value.isSending) return
        sendJob = viewModelScope.launch {
            _uiState.update { it.copy(isSending = true) }
            try {
                repo.sendMessage(chatId, text.trim())
            } finally {
                _uiState.update { it.copy(isSending = false) }
            }
        }
    }

    fun stopGenerating() {
        sendJob?.cancel()
    }
}
