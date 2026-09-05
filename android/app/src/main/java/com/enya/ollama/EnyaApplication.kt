package com.enya.ollama

import android.app.Application
import com.enya.ollama.data.SettingsRepository
import com.enya.ollama.data.db.AppDatabase
import com.enya.ollama.data.net.OllamaApi
import com.enya.ollama.data.repo.ChatRepository

class EnyaApplication : Application() {

    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var chatRepository: ChatRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val database = AppDatabase.getInstance(this)
        val api = OllamaApi()
        settingsRepository = SettingsRepository(this)
        chatRepository = ChatRepository(database, api, settingsRepository)
    }
}
