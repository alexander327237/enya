package com.enya.txtvoice.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.enya.txtvoice.data.BookRepository
import com.enya.txtvoice.data.SettingsRepository
import com.enya.txtvoice.ui.library.LibraryViewModel
import com.enya.txtvoice.ui.settings.SettingsViewModel

class ViewModelFactory(
    private val books: BookRepository,
    private val settings: SettingsRepository
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(LibraryViewModel::class.java) -> LibraryViewModel(books) as T
        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(settings) as T
        else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
    }
}
