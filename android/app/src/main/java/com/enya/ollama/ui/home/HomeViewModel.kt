package com.enya.ollama.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enya.ollama.data.SettingsRepository
import com.enya.ollama.data.db.ChatEntity
import com.enya.ollama.data.db.ProjectEntity
import com.enya.ollama.data.repo.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val projects: List<ProjectEntity> = emptyList(),
    val chats: List<ChatEntity> = emptyList()
)

class HomeViewModel(
    private val repo: ChatRepository,
    private val settings: SettingsRepository
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(repo.observeProjects(), repo.observeChats()) { projects, chats ->
        HomeUiState(projects, chats)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    private val _availableModels = MutableStateFlow<List<String>>(emptyList())
    val availableModels: StateFlow<List<String>> = _availableModels.asStateFlow()

    private val _modelsError = MutableStateFlow<String?>(null)
    val modelsError: StateFlow<String?> = _modelsError.asStateFlow()

    init {
        refreshModels()
    }

    fun refreshModels() {
        viewModelScope.launch {
            repo.fetchModels()
                .onSuccess {
                    _availableModels.value = it
                    _modelsError.value = null
                }
                .onFailure { _modelsError.value = it.message ?: "Failed to load models" }
        }
    }

    /**
     * Creates and opens a chat immediately, with no dialog: the model defaults to whichever
     * was last used (falling back to the first available one), and the title is filled in
     * automatically from the first message once the user sends it.
     */
    fun quickCreateChat(projectId: Long?, onCreated: (Long) -> Unit, onNoModelsAvailable: () -> Unit) {
        viewModelScope.launch {
            val model = repo.lastOrFirstAvailableModel()
            if (model == null) {
                onNoModelsAvailable()
                return@launch
            }
            val id = repo.createChat(projectId, "New chat", model)
            settings.setLastModel(model)
            onCreated(id)
        }
    }

    fun renameChat(chat: ChatEntity, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch { repo.renameChat(chat.id, title.trim()) }
    }

    fun createProject(name: String, systemPrompt: String?) {
        if (name.isBlank()) return
        viewModelScope.launch { repo.createProject(name.trim(), systemPrompt) }
    }

    fun deleteChat(chat: ChatEntity) {
        viewModelScope.launch { repo.deleteChat(chat) }
    }

    fun deleteProject(project: ProjectEntity) {
        viewModelScope.launch { repo.deleteProject(project) }
    }
}
