package com.enya.pdfreader.ui.reader

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.enya.pdfreader.data.RecentFilesStore
import com.enya.pdfreader.pdf.PdfDocument
import com.enya.pdfreader.pdf.PdfOpenException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface ReaderState {
    data object Loading : ReaderState
    data class Error(val kind: PdfOpenException.Kind) : ReaderState
    data class Ready(
        val name: String,
        val pageCount: Int,
        val initialPage: Int,
        val sizeBytes: Long,
    ) : ReaderState
}

/**
 * Owns the open [PdfDocument] so it survives rotation. The document is closed explicitly when
 * the reader is left ([release]) and as a safety net in [onCleared].
 */
class ReaderViewModel(app: Application, val uri: Uri) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<ReaderState>(ReaderState.Loading)
    val state: StateFlow<ReaderState> = _state

    private var document: PdfDocument? = null
    private var openJob: Job? = null

    fun ensureOpen() {
        if (document != null || openJob?.isActive == true) return
        _state.value = ReaderState.Loading
        openJob = viewModelScope.launch {
            try {
                val doc = PdfDocument.open(getApplication(), uri)
                val store = RecentFilesStore.get(getApplication())
                store.touch(uri.toString(), doc.name, doc.pageCount)
                val initial = store.get(uri.toString())?.lastPage ?: 0
                document = doc
                _state.value = ReaderState.Ready(
                    name = doc.name,
                    pageCount = doc.pageCount,
                    initialPage = initial.coerceIn(0, (doc.pageCount - 1).coerceAtLeast(0)),
                    sizeBytes = doc.sizeBytes,
                )
            } catch (e: PdfOpenException) {
                _state.value = ReaderState.Error(e.kind)
            } catch (e: Exception) {
                _state.value = ReaderState.Error(PdfOpenException.Kind.CORRUPT)
            }
        }
    }

    suspend fun render(pageIndex: Int, widthPx: Int): Bitmap {
        val doc = document ?: throw IllegalStateException("document not open")
        return doc.render(pageIndex, widthPx)
    }

    fun release() {
        openJob?.cancel()
        openJob = null
        document?.close()
        document = null
        _state.value = ReaderState.Loading
    }

    override fun onCleared() {
        release()
    }

    class Factory(private val app: Application, private val uri: Uri) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(app, uri) as T
    }
}
