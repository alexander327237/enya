package com.enya.txtvoice.tts

import android.content.Context
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Microsoft Edge neural voices (Svetlana, Dmitry, ...): free, no key, needs internet. */
class EdgeTtsEngine(
    context: Context,
    /** Short voice name like `ru-RU-SvetlanaNeural`, or empty to pick one by the text's language. */
    private val voice: String
) : AudioFileTtsEngine(context) {

    override val maxSegmentChars: Int get() = MAX_CHARS

    /** Free, so download a few segments ahead to ride out short connectivity drops. */
    override val prefetchAhead: Int get() = 3

    override val cacheSalt: String = "edge|$voice"

    private val edge = EdgeTtsClient(client)

    override fun cacheKey(text: String, locale: Locale?): String =
        super.cacheKey(voiceFor(locale) + "\u0000" + text, locale)

    private fun voiceFor(locale: Locale?): String = voice.ifBlank { autoVoice(locale) }

    override fun synthesize(text: String, locale: Locale?, target: File) {
        val v = voiceFor(locale)
        var lastError: IOException? = null
        repeat(2) { attempt ->
            try {
                target.outputStream().use { out -> edge.synthesize(text, v, out) }
                return
            } catch (e: IOException) {
                lastError = e
                if (attempt == 0) Thread.sleep(700)
            }
        }
        throw lastError ?: IOException("Edge TTS failed")
    }

    companion object {
        /** Short segments start playing sooner; the service accepts up to 4 KB of SSML per request. */
        const val MAX_CHARS = 700

        const val DEFAULT_RU = "ru-RU-SvetlanaNeural"
        const val DEFAULT_UK = "uk-UA-PolinaNeural"
        const val DEFAULT_EN = "en-US-EmmaMultilingualNeural"

        /** Voices offered in settings: short voice name to display label. */
        val VOICES: List<Pair<String, String>> = listOf(
            "ru-RU-SvetlanaNeural" to "Светлана · ru ♀",
            "ru-RU-DmitryNeural" to "Дмитрий · ru ♂",
            "uk-UA-PolinaNeural" to "Поліна · uk ♀",
            "uk-UA-OstapNeural" to "Остап · uk ♂",
            "kk-KZ-AigulNeural" to "Айгуль · kk ♀",
            "kk-KZ-DauletNeural" to "Даулет · kk ♂",
            "en-US-EmmaMultilingualNeural" to "Emma · multilingual ♀",
            "en-US-AvaMultilingualNeural" to "Ava · multilingual ♀",
            "en-US-AndrewMultilingualNeural" to "Andrew · multilingual ♂",
            "en-US-BrianMultilingualNeural" to "Brian · multilingual ♂",
            "de-DE-SeraphinaMultilingualNeural" to "Seraphina · multilingual ♀",
            "de-DE-FlorianMultilingualNeural" to "Florian · multilingual ♂",
            "en-US-AriaNeural" to "Aria · en ♀",
            "en-US-JennyNeural" to "Jenny · en ♀",
            "en-US-GuyNeural" to "Guy · en ♂"
        )

        fun autoVoice(locale: Locale?): String = when (locale?.language) {
            "ru" -> DEFAULT_RU
            "uk" -> DEFAULT_UK
            else -> DEFAULT_EN
        }

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .pingInterval(0, TimeUnit.SECONDS)
                .build()
        }
    }
}
