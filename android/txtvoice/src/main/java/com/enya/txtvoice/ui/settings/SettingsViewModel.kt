package com.enya.txtvoice.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.enya.txtvoice.data.EngineType
import com.enya.txtvoice.data.SettingsRepository
import com.enya.txtvoice.data.TtsSettings
import com.enya.txtvoice.tts.Player
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class VoiceOption(val name: String, val label: String)

class SettingsViewModel(private val repo: SettingsRepository) : ViewModel() {

    /** Null until the stored settings have been read, so text fields are initialised only once. */
    val settings: StateFlow<TtsSettings?> = repo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _voices = MutableStateFlow<List<VoiceOption>>(emptyList())
    val voices: StateFlow<List<VoiceOption>> = _voices.asStateFlow()

    private val debounced = HashMap<String, Job>()

    fun loadVoices() {
        viewModelScope.launch {
            _voices.value = runCatching { Player.systemVoices() }.getOrDefault(emptyList()).map { v ->
                val quality = if (v.isNetworkConnectionRequired) " · online" else ""
                VoiceOption(v.name, "${v.locale.displayName} — ${v.name}$quality")
            }
        }
    }

    fun setEngine(engine: EngineType) = viewModelScope.launch { repo.setEngine(engine) }
    fun setRate(rate: Float) = viewModelScope.launch { repo.setRate(rate) }
    fun setPitch(pitch: Float) = viewModelScope.launch { repo.setPitch(pitch) }
    fun setSystemVoice(name: String) = viewModelScope.launch { repo.setSystemVoice(name) }
    fun setFontSize(size: Int) = viewModelScope.launch { repo.setFontSize(size) }
    fun setOpenAiVoice(voice: String) = debounce("voice") { repo.setOpenAiVoice(voice) }
    fun setOpenAiBaseUrl(url: String) = debounce("url") { repo.setOpenAiBaseUrl(url) }
    fun setOpenAiKey(key: String) = debounce("key") { repo.setOpenAiKey(key) }
    fun setOpenAiModel(model: String) = debounce("model") { repo.setOpenAiModel(model) }
    fun setOpenAiInstructions(text: String) = debounce("instructions") { repo.setOpenAiInstructions(text) }

    /** Text fields write on every keystroke; wait for a pause so the engine is not rebuilt per character. */
    private fun debounce(key: String, block: suspend () -> Unit) {
        debounced[key]?.cancel()
        debounced[key] = viewModelScope.launch {
            delay(600)
            block()
        }
    }

    fun testVoice(sample: String) = Player.speakSample(sample)
}
