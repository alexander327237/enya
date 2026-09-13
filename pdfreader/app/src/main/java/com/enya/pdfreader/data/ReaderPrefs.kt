package com.enya.pdfreader.data

import android.content.Context

/** Reader display settings that should stick between documents. */
class ReaderPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)

    var nightMode: Boolean
        get() = prefs.getBoolean("night_mode", false)
        set(value) = prefs.edit().putBoolean("night_mode", value).apply()

    var verticalScroll: Boolean
        get() = prefs.getBoolean("vertical_scroll", false)
        set(value) = prefs.edit().putBoolean("vertical_scroll", value).apply()
}
