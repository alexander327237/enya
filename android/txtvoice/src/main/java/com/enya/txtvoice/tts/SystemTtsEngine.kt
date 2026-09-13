package com.enya.txtvoice.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.enya.txtvoice.R
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Android's built-in text-to-speech (Google TTS, Samsung TTS, RHVoice, ...). Offline and free. */
class SystemTtsEngine(
    private val context: Context,
    private val voiceName: String
) : TtsEngine {

    override val maxSegmentChars: Int get() = MAX_CHARS

    private val ready = CompletableDeferred<Unit>()
    private val pending = ConcurrentHashMap<String, CancellableContinuation<Unit>>()
    private val starts = ConcurrentHashMap<String, () -> Unit>()
    private var appliedLocale: Locale? = null
    private var voiceApplied = false

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) ready.complete(Unit)
        else ready.completeExceptionally(IllegalStateException(context.getString(R.string.error_tts_init)))
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {
                starts.remove(utteranceId)?.invoke()
            }

            override fun onDone(utteranceId: String) {
                starts.remove(utteranceId)
                pending.remove(utteranceId)?.resume(Unit)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                onError(utteranceId, -1)
            }

            override fun onError(utteranceId: String, errorCode: Int) {
                starts.remove(utteranceId)
                pending.remove(utteranceId)?.resumeWithException(IllegalStateException("TTS error $errorCode"))
            }

            override fun onStop(utteranceId: String, interrupted: Boolean) {
                starts.remove(utteranceId)
                pending.remove(utteranceId)?.resume(Unit)
            }
        })
    }

    suspend fun voices(): List<Voice> {
        ready.await()
        return runCatching { tts.voices?.toList() }.getOrNull()
            ?.filterNot { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true }
            ?.sortedWith(compareBy({ it.locale.displayLanguage }, { it.name }))
            ?: emptyList()
    }

    private fun applyVoiceAndLanguage(locale: Locale?) {
        if (voiceName.isNotBlank()) {
            if (!voiceApplied) {
                val v = runCatching { tts.voices?.firstOrNull { it.name == voiceName } }.getOrNull()
                if (v != null) tts.voice = v
                voiceApplied = true
            }
            return
        }
        if (locale != null && locale != appliedLocale) {
            val avail = tts.isLanguageAvailable(locale)
            if (avail >= TextToSpeech.LANG_AVAILABLE) tts.language = locale
            appliedLocale = locale
        }
    }

    override suspend fun speak(segment: Segment, rate: Float, pitch: Float, locale: Locale?, onStart: () -> Unit) {
        ready.await()
        applyVoiceAndLanguage(locale)
        tts.setSpeechRate(rate)
        tts.setPitch(pitch)
        val limit = TextToSpeech.getMaxSpeechInputLength()
        val text = if (segment.text.length > limit) segment.text.substring(0, limit) else segment.text
        suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            pending[id] = cont
            starts[id] = onStart
            cont.invokeOnCancellation {
                pending.remove(id)
                starts.remove(id)
                tts.stop()
            }
            val r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
            if (r != TextToSpeech.SUCCESS) {
                pending.remove(id)
                starts.remove(id)
                cont.resumeWithException(IllegalStateException(context.getString(R.string.error_tts_init)))
            }
        }
    }

    override fun release() {
        pending.keys.toList().forEach { id -> pending.remove(id)?.cancel() }
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }

    companion object {
        /** Short segments give fine-grained highlighting; the system engine has no per-request overhead. */
        const val MAX_CHARS = 400
    }
}
