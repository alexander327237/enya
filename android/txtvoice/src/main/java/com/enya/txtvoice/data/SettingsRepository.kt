package com.enya.txtvoice.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "txtvoice_settings")

enum class EngineType { SYSTEM, OPENAI }

data class TtsSettings(
    val engine: EngineType = EngineType.SYSTEM,
    val rate: Float = 1.0f,
    val pitch: Float = 1.0f,
    /** Name of the system voice, or empty for automatic. */
    val systemVoice: String = "",
    val openAiBaseUrl: String = DEFAULT_BASE_URL,
    val openAiKey: String = "",
    val openAiModel: String = DEFAULT_MODEL,
    val openAiVoice: String = DEFAULT_VOICE,
    val openAiInstructions: String = "",
    val fontSize: Int = 18
) {
    /** Everything that changes the produced audio; used to decide whether the engine must be rebuilt. */
    val engineKey: String
        get() = listOf(engine.name, systemVoice, openAiBaseUrl, openAiKey, openAiModel, openAiVoice, openAiInstructions)
            .joinToString("|")

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini-tts"
        const val DEFAULT_VOICE = "alloy"
        val OPENAI_VOICES = listOf("alloy", "ash", "ballad", "coral", "echo", "fable", "onyx", "nova", "sage", "shimmer", "verse")
    }
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val ENGINE = stringPreferencesKey("engine")
        val RATE = floatPreferencesKey("rate")
        val PITCH = floatPreferencesKey("pitch")
        val SYSTEM_VOICE = stringPreferencesKey("system_voice")
        val BASE_URL = stringPreferencesKey("openai_base_url")
        val API_KEY = stringPreferencesKey("openai_key")
        val MODEL = stringPreferencesKey("openai_model")
        val VOICE = stringPreferencesKey("openai_voice")
        val INSTRUCTIONS = stringPreferencesKey("openai_instructions")
        val FONT_SIZE = intPreferencesKey("font_size")
    }

    val settings: Flow<TtsSettings> = context.settingsStore.data.map { p ->
        TtsSettings(
            engine = p[Keys.ENGINE]?.let { runCatching { EngineType.valueOf(it) }.getOrNull() } ?: EngineType.SYSTEM,
            rate = p[Keys.RATE] ?: 1.0f,
            pitch = p[Keys.PITCH] ?: 1.0f,
            systemVoice = p[Keys.SYSTEM_VOICE] ?: "",
            openAiBaseUrl = p[Keys.BASE_URL] ?: TtsSettings.DEFAULT_BASE_URL,
            openAiKey = p[Keys.API_KEY] ?: "",
            openAiModel = p[Keys.MODEL] ?: TtsSettings.DEFAULT_MODEL,
            openAiVoice = p[Keys.VOICE] ?: TtsSettings.DEFAULT_VOICE,
            openAiInstructions = p[Keys.INSTRUCTIONS] ?: "",
            fontSize = p[Keys.FONT_SIZE] ?: 18
        )
    }

    suspend fun setEngine(engine: EngineType) = context.settingsStore.edit { it[Keys.ENGINE] = engine.name }
    suspend fun setRate(rate: Float) = context.settingsStore.edit { it[Keys.RATE] = rate.coerceIn(0.5f, 3.0f) }
    suspend fun setPitch(pitch: Float) = context.settingsStore.edit { it[Keys.PITCH] = pitch.coerceIn(0.5f, 2.0f) }
    suspend fun setSystemVoice(name: String) = context.settingsStore.edit { it[Keys.SYSTEM_VOICE] = name }
    suspend fun setOpenAiBaseUrl(url: String) = context.settingsStore.edit { it[Keys.BASE_URL] = url.trim().trimEnd('/') }
    suspend fun setOpenAiKey(key: String) = context.settingsStore.edit { it[Keys.API_KEY] = key.trim() }
    suspend fun setOpenAiModel(model: String) = context.settingsStore.edit { it[Keys.MODEL] = model.trim() }
    suspend fun setOpenAiVoice(voice: String) = context.settingsStore.edit { it[Keys.VOICE] = voice.trim() }
    suspend fun setOpenAiInstructions(text: String) = context.settingsStore.edit { it[Keys.INSTRUCTIONS] = text }
    suspend fun setFontSize(size: Int) = context.settingsStore.edit { it[Keys.FONT_SIZE] = size.coerceIn(12, 32) }
}
