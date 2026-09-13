package com.enya.txtvoice.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.enya.txtvoice.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

private val Context.libraryStore by preferencesDataStore(name = "txtvoice_library")

/**
 * Keeps imported texts as UTF-8 files under filesDir/books and an index of [Book] entries in DataStore.
 * Copying the text on import means the app keeps working even if the original SAF permission is lost.
 */
class BookRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val indexKey = stringPreferencesKey("books")
    private val serializer = ListSerializer(Book.serializer())

    val books: Flow<List<Book>> = context.libraryStore.data.map { prefs ->
        prefs[indexKey]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()
    }.map { list -> list.sortedByDescending { it.lastOpenedAt } }

    private val booksDir: File
        get() = File(context.filesDir, "books").apply { mkdirs() }

    private fun fileFor(id: String) = File(booksDir, "$id.txt")

    suspend fun importUri(uri: Uri): Book = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException(context.getString(R.string.error_open_file))
        val text = TextDecoder.decode(bytes)
        val title = displayName(uri) ?: uri.lastPathSegment ?: "text"
        importText(title.removeSuffix(".txt").removeSuffix(".TXT"), text)
    }

    suspend fun importText(title: String, rawText: String): Book = withContext(Dispatchers.IO) {
        val text = normalize(rawText)
        if (text.isBlank()) throw IllegalStateException(context.getString(R.string.error_empty_text))
        val id = UUID.randomUUID().toString()
        fileFor(id).writeText(text, Charsets.UTF_8)
        val now = System.currentTimeMillis()
        val book = Book(
            id = id,
            title = title.ifBlank { context.getString(R.string.pasted_text_default_title) },
            length = text.length,
            charOffset = 0,
            addedAt = now,
            lastOpenedAt = now
        )
        update { it + book }
        book
    }

    suspend fun loadText(id: String): String = withContext(Dispatchers.IO) {
        fileFor(id).takeIf { it.exists() }?.readText(Charsets.UTF_8) ?: ""
    }

    suspend fun get(id: String): Book? = books.first().firstOrNull { it.id == id }

    suspend fun touch(id: String) = update { list ->
        list.map { if (it.id == id) it.copy(lastOpenedAt = System.currentTimeMillis()) else it }
    }

    suspend fun saveProgress(id: String, charOffset: Int) = update { list ->
        list.map { if (it.id == id) it.copy(charOffset = charOffset) else it }
    }

    suspend fun delete(id: String) {
        update { list -> list.filterNot { it.id == id } }
        withContext(Dispatchers.IO) { fileFor(id).delete() }
    }

    private suspend fun update(transform: (List<Book>) -> List<Book>) {
        context.libraryStore.edit { prefs ->
            val current = prefs[indexKey]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()
            prefs[indexKey] = json.encodeToString(serializer, transform(current))
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun normalize(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n').replace(' ', ' ').trim()
}
