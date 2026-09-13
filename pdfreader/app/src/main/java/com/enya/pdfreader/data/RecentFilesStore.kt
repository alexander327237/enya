package com.enya.pdfreader.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class RecentFile(
    val uri: String,
    val name: String,
    val lastPage: Int,
    val pageCount: Int,
    val lastOpened: Long,
)

/**
 * Recently opened documents with reading progress, persisted as JSON in SharedPreferences.
 * Small enough that a full rewrite on every change is fine.
 */
class RecentFilesStore private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("recent_files", Context.MODE_PRIVATE)
    private val _files = MutableStateFlow(load())
    val files: StateFlow<List<RecentFile>> = _files

    fun get(uri: String): RecentFile? = _files.value.firstOrNull { it.uri == uri }

    /** Called when a document was opened successfully: adds it (or moves it) to the top. */
    fun touch(uri: String, name: String, pageCount: Int) {
        val existing = get(uri)
        val entry = RecentFile(
            uri = uri,
            name = name,
            lastPage = existing?.lastPage?.coerceIn(0, (pageCount - 1).coerceAtLeast(0)) ?: 0,
            pageCount = pageCount,
            lastOpened = System.currentTimeMillis(),
        )
        update(listOf(entry) + _files.value.filter { it.uri != uri })
    }

    fun updateProgress(uri: String, page: Int) {
        val list = _files.value
        val index = list.indexOfFirst { it.uri == uri }
        if (index < 0 || list[index].lastPage == page) return
        val copy = list.toMutableList()
        copy[index] = copy[index].copy(lastPage = page)
        update(copy)
    }

    fun remove(uri: String) {
        update(_files.value.filter { it.uri != uri })
    }

    private fun update(list: List<RecentFile>) {
        val trimmed = list.take(MAX_ENTRIES)
        _files.value = trimmed
        val array = JSONArray()
        trimmed.forEach { f ->
            array.put(
                JSONObject()
                    .put("uri", f.uri)
                    .put("name", f.name)
                    .put("lastPage", f.lastPage)
                    .put("pageCount", f.pageCount)
                    .put("lastOpened", f.lastOpened)
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun load(): List<RecentFile> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                RecentFile(
                    uri = o.getString("uri"),
                    name = o.optString("name", "document.pdf"),
                    lastPage = o.optInt("lastPage", 0),
                    pageCount = o.optInt("pageCount", 0),
                    lastOpened = o.optLong("lastOpened", 0L),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val KEY = "files"
        private const val MAX_ENTRIES = 50

        @Volatile
        private var instance: RecentFilesStore? = null

        fun get(context: Context): RecentFilesStore =
            instance ?: synchronized(this) {
                instance ?: RecentFilesStore(context).also { instance = it }
            }
    }
}
