package com.enya.txtvoice

import android.app.Application
import com.enya.txtvoice.data.BookRepository
import com.enya.txtvoice.data.SettingsRepository
import com.enya.txtvoice.tts.Player

class TxtVoiceApp : Application() {

    val bookRepository: BookRepository by lazy { BookRepository(this) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    override fun onCreate() {
        super.onCreate()
        Player.init(this, bookRepository, settingsRepository)
    }
}
