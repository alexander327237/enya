package com.enya.txtvoice.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enya.txtvoice.data.Book
import com.enya.txtvoice.data.BookRepository
import com.enya.txtvoice.tts.Player
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val repo: BookRepository) : ViewModel() {

    val books: StateFlow<List<Book>> = repo.books
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    suspend fun importUri(uri: Uri): Book? = try {
        repo.importUri(uri)
    } catch (e: Exception) {
        _messages.tryEmit(e.message ?: e.toString())
        null
    }

    suspend fun importText(title: String, text: String): Book? = try {
        repo.importText(title, text)
    } catch (e: Exception) {
        _messages.tryEmit(e.message ?: e.toString())
        null
    }

    fun delete(book: Book) {
        viewModelScope.launch {
            if (Player.state.value.book?.id == book.id) Player.stop()
            repo.delete(book.id)
        }
    }
}
