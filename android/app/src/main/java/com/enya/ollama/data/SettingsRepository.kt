package com.enya.ollama.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "enya_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val LAST_MODEL = stringPreferencesKey("last_model")
        val AUTH_HEADER = stringPreferencesKey("auth_header")
    }

    val serverUrl: Flow<String> = context.dataStore.data.map { it[Keys.SERVER_URL] ?: DEFAULT_SERVER_URL }
    val lastModel: Flow<String?> = context.dataStore.data.map { it[Keys.LAST_MODEL] }

    /**
     * Raw value sent as the "Authorization" header on every request, e.g. "Bearer sk-..." or
     * "Basic <base64>". Lets the server address be more than a bare local IP: a remote host
     * behind a reverse proxy or tunnel that requires auth. Null/blank means no header is sent.
     */
    val authHeader: Flow<String?> = context.dataStore.data.map { it[Keys.AUTH_HEADER]?.takeIf { h -> h.isNotBlank() } }

    suspend fun setServerUrl(url: String) {
        context.dataStore.edit { it[Keys.SERVER_URL] = url.trim() }
    }

    suspend fun setLastModel(model: String) {
        context.dataStore.edit { it[Keys.LAST_MODEL] = model }
    }

    suspend fun setAuthHeader(value: String) {
        context.dataStore.edit { it[Keys.AUTH_HEADER] = value.trim() }
    }

    companion object {
        const val DEFAULT_SERVER_URL = "http://192.168.1.1:11434"
    }
}
