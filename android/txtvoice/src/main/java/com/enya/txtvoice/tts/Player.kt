package com.enya.txtvoice.tts

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.core.content.ContextCompat
import com.enya.txtvoice.data.Book
import com.enya.txtvoice.data.BookRepository
import com.enya.txtvoice.data.EngineType
import com.enya.txtvoice.data.SettingsRepository
import com.enya.txtvoice.data.TtsSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Process-wide playback controller. Owns the current book, its segments and the speech engine.
 * The UI observes [state]; [PlayerService] keeps the process alive and shows the media notification.
 */
object Player {

    enum class Status { IDLE, LOADING, PLAYING, PAUSED, ERROR }

    data class State(
        val book: Book? = null,
        val segments: List<Segment> = emptyList(),
        val index: Int = 0,
        val status: Status = Status.IDLE,
        val error: String? = null,
        val sleepEndsAt: Long? = null,
        val engine: EngineType = EngineType.SYSTEM,
        val rate: Float = 1f,
        val finished: Boolean = false
    ) {
        val isActive: Boolean get() = status == Status.PLAYING || status == Status.LOADING
        val current: Segment? get() = segments.getOrNull(index)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private lateinit var app: Context
    private lateinit var books: BookRepository
    private lateinit var settingsRepo: SettingsRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var settings = TtsSettings()
    private var settingsLoaded = false
    private var engine: TtsEngine? = null
    private var engineKey: String? = null
    private var text: String = ""
    private var locale: Locale? = null
    private var playJob: Job? = null
    private var sleepJob: Job? = null
    private var focusRequest: AudioFocusRequest? = null

    fun init(context: Context, books: BookRepository, settingsRepo: SettingsRepository) {
        if (this::app.isInitialized) return
        app = context.applicationContext
        this.books = books
        this.settingsRepo = settingsRepo
        scope.launch {
            settingsRepo.settings.collect { new ->
                val old = settings
                settings = new
                settingsLoaded = true
                _state.update { it.copy(engine = new.engine, rate = new.rate) }
                if (old.engineKey != new.engineKey || old.engine != new.engine) {
                    rebuildEngine()
                } else if (old.rate != new.rate || old.pitch != new.pitch) {
                    engine?.applyLive(new.rate, new.pitch)
                }
            }
        }
    }

    private fun maxCharsFor(engineType: EngineType) = when (engineType) {
        EngineType.SYSTEM -> SystemTtsEngine.MAX_CHARS
        EngineType.OPENAI -> OpenAiTtsEngine.MAX_CHARS
    }

    private suspend fun ensureSettings() {
        if (!settingsLoaded) {
            settings = settingsRepo.settings.first()
            settingsLoaded = true
        }
    }

    private fun createEngine(): TtsEngine = when (settings.engine) {
        EngineType.SYSTEM -> SystemTtsEngine(app, settings.systemVoice)
        EngineType.OPENAI -> OpenAiTtsEngine(
            app,
            settings.openAiBaseUrl.ifBlank { TtsSettings.DEFAULT_BASE_URL },
            settings.openAiKey,
            settings.openAiModel.ifBlank { TtsSettings.DEFAULT_MODEL },
            settings.openAiVoice.ifBlank { TtsSettings.DEFAULT_VOICE },
            settings.openAiInstructions
        )
    }

    private fun ensureEngine(): TtsEngine {
        val key = settings.engineKey
        engine?.let { if (engineKey == key) return it }
        engine?.release()
        val e = createEngine()
        engine = e
        engineKey = key
        return e
    }

    /** Engine settings changed: swap the engine and re-segment the text, keeping the reading position. */
    private fun rebuildEngine() {
        val wasActive = _state.value.isActive
        playJob?.cancel()
        playJob = null
        engine?.release()
        engine = null
        engineKey = null
        if (text.isNotEmpty()) {
            val s = _state.value
            val offset = s.current?.start ?: 0
            val segments = Segmenter.split(text, maxCharsFor(settings.engine))
            _state.update {
                it.copy(
                    segments = segments,
                    index = Segmenter.indexForOffset(segments, offset),
                    status = if (wasActive) Status.PAUSED else it.status
                )
            }
        }
        if (wasActive) play()
    }

    /** Loads a book into the player (without starting playback). No-op if it is already loaded. */
    suspend fun load(bookId: String) {
        if (_state.value.book?.id == bookId && text.isNotEmpty()) return
        stop()
        ensureSettings()
        val book = books.get(bookId) ?: return
        val loaded = books.loadText(bookId)
        text = loaded
        locale = detectLocale(loaded)
        val segments = withContext(Dispatchers.Default) { Segmenter.split(loaded, maxCharsFor(settings.engine)) }
        val finished = book.charOffset >= book.length && book.length > 0
        _state.value = State(
            book = book,
            segments = segments,
            index = if (finished) 0 else Segmenter.indexForOffset(segments, book.charOffset),
            status = Status.IDLE,
            engine = settings.engine,
            rate = settings.rate,
            finished = false
        )
        books.touch(bookId)
    }

    fun play() {
        val s = _state.value
        if (s.segments.isEmpty()) return
        playJob?.cancel()
        if (s.finished || s.index >= s.segments.size) {
            _state.update { it.copy(index = 0, finished = false) }
        }
        playJob = scope.launch {
            try {
                ensureSettings()
                _state.update { it.copy(status = Status.LOADING, error = null) }
                val eng = ensureEngine()
                startService()
                requestFocus()
                while (isActive) {
                    val st = _state.value
                    val seg = st.segments.getOrNull(st.index) ?: break
                    st.segments.getOrNull(st.index + 1)?.let { next ->
                        launch { runCatching { eng.prepare(next) } }
                    }
                    _state.update { it.copy(status = Status.LOADING) }
                    eng.speak(seg, settings.rate, settings.pitch, locale) {
                        _state.update { it.copy(status = Status.PLAYING) }
                    }
                    val nextIndex = st.index + 1
                    if (nextIndex >= st.segments.size) {
                        _state.update { it.copy(status = Status.IDLE, finished = true) }
                        saveProgress(st.book, text.length)
                        abandonFocus()
                        break
                    }
                    _state.update { it.copy(index = nextIndex) }
                    saveProgress(st.book, st.segments[nextIndex].start)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(status = Status.ERROR, error = e.message ?: e.toString()) }
                abandonFocus()
            }
        }
    }

    fun pause() {
        playJob?.cancel()
        playJob = null
        abandonFocus()
        _state.update { if (it.isActive) it.copy(status = Status.PAUSED) else it }
    }

    fun togglePlayPause() {
        if (_state.value.isActive) pause() else play()
    }

    fun stop() {
        playJob?.cancel()
        playJob = null
        abandonFocus()
        cancelSleep()
        _state.update { it.copy(status = Status.IDLE) }
        stopService()
    }

    fun seekTo(index: Int) {
        val s = _state.value
        if (s.segments.isEmpty()) return
        val target = index.coerceIn(0, s.segments.size - 1)
        val wasActive = s.isActive
        playJob?.cancel()
        playJob = null
        _state.update { it.copy(index = target, finished = false, status = if (wasActive) Status.LOADING else Status.PAUSED) }
        scope.launch { saveProgress(s.book, s.segments[target].start) }
        if (wasActive) play()
    }

    fun next() = seekTo(_state.value.index + 1)
    fun previous() = seekTo(_state.value.index - 1)

    fun setSleepTimer(minutes: Int?) {
        cancelSleep()
        if (minutes == null || minutes <= 0) return
        val endsAt = System.currentTimeMillis() + minutes * 60_000L
        _state.update { it.copy(sleepEndsAt = endsAt) }
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            pause()
            _state.update { it.copy(sleepEndsAt = null) }
        }
    }

    private fun cancelSleep() {
        sleepJob?.cancel()
        sleepJob = null
        _state.update { it.copy(sleepEndsAt = null) }
    }

    /** Speaks a short sample with the current engine settings (used by the settings screen). */
    fun speakSample(sample: String) {
        playJob?.cancel()
        playJob = scope.launch {
            try {
                ensureSettings()
                _state.update { it.copy(status = Status.LOADING, error = null) }
                val eng = ensureEngine()
                eng.speak(Segment(0, sample, 0, sample.length, true), settings.rate, settings.pitch, detectLocale(sample)) {
                    _state.update { it.copy(status = Status.PLAYING) }
                }
                _state.update { it.copy(status = if (it.book != null) Status.PAUSED else Status.IDLE) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(status = Status.ERROR, error = e.message ?: e.toString()) }
            }
        }
    }

    /** Lists the system voices. Uses the live engine when it is a system one, otherwise a temporary instance. */
    suspend fun systemVoices(): List<android.speech.tts.Voice> {
        val live = engine as? SystemTtsEngine
        if (live != null) return live.voices()
        val tmp = SystemTtsEngine(app, "")
        return try {
            tmp.voices()
        } finally {
            tmp.release()
        }
    }

    private suspend fun saveProgress(book: Book?, offset: Int) {
        if (book == null) return
        runCatching { books.saveProgress(book.id, offset) }
    }

    private fun detectLocale(sample: String): Locale? {
        val probe = if (sample.length > 4000) sample.substring(0, 4000) else sample
        var cyr = 0
        var lat = 0
        for (ch in probe) {
            when (ch) {
                in 'Ѐ'..'ӿ' -> cyr++
                in 'a'..'z', in 'A'..'Z' -> lat++
            }
        }
        return when {
            cyr == 0 && lat == 0 -> null
            cyr >= lat -> Locale("ru")
            else -> Locale.ENGLISH
        }
    }

    private fun startService() {
        val intent = Intent(app, PlayerService::class.java).setAction(PlayerService.ACTION_START)
        runCatching { ContextCompat.startForegroundService(app, intent) }
    }

    private fun stopService() {
        runCatching { app.stopService(Intent(app, PlayerService::class.java)) }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
        }
    }

    private fun requestFocus() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (focusRequest == null) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
        }
        focusRequest?.let { runCatching { am.requestAudioFocus(it) } }
    }

    private fun abandonFocus() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
    }
}
