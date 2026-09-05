package com.enya.ollama.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.enya.ollama.data.SettingsRepository
import com.enya.ollama.data.repo.ChatRepository
import com.enya.ollama.ui.chat.ChatViewModel
import com.enya.ollama.ui.home.HomeViewModel
import com.enya.ollama.ui.settings.SettingsViewModel

class ViewModelFactory(
    private val chatRepository: ChatRepository,
    private val settingsRepository: SettingsRepository
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(HomeViewModel::class.java) ->
            HomeViewModel(chatRepository, settingsRepository) as T
        modelClass.isAssignableFrom(ChatViewModel::class.java) ->
            ChatViewModel(chatRepository) as T
        modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
            SettingsViewModel(chatRepository, settingsRepository) as T
        else -> throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
    }
}
