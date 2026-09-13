package com.enya.txtvoice.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import com.enya.txtvoice.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Speech through an OpenAI-compatible `POST {baseUrl}/audio/speech` endpoint (OpenAI gpt-4o-mini-tts,
 * Kokoro-FastAPI, LocalAI, ...). Audio for each segment is cached on disk and played with MediaPlayer;
 * the next segment is downloaded while the current one plays.
 */
class OpenAiTtsEngine(
    private val context: Context,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val voice: String,
    private val instructions: String
) : TtsEngine {

    override val maxSegmentChars: Int get() = MAX_CHARS

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = HashMap<String, Deferred<File>>()
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var current: MediaPlayer? = null

    private fun cacheKey(text: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val raw = listOf(baseUrl, model, voice, instructions, text).joinToString("")
        return md.digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** Returns the (possibly already running) download for this segment. Failed downloads are retried. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun fetch(segment: Segment): Deferred<File> = lock.withLock {
        val key = cacheKey(segment.text)
        val existing = inFlight[key]
        if (existing != null && !existing.isCancelled &&
            !(existing.isCompleted && existing.getCompletionExceptionOrNull() != null)
        ) {
            return existing
        }
        val deferred = scope.async { download(key, segment.text) }
        inFlight[key] = deferred
        deferred
    }

    private fun download(key: String, text: String): File {
        val dir = cacheDir(context)
        val file = File(dir, "$key.mp3")
        if (file.exists() && file.length() > 0) return file

        if (apiKey.isBlank() && baseUrl.contains("api.openai.com")) {
            throw IllegalStateException(context.getString(R.string.error_no_api_key))
        }
        val body = buildJsonObject {
            put("model", model)
            put("input", text)
            put("voice", voice)
            put("response_format", "mp3")
            if (instructions.isNotBlank()) put("instructions", instructions)
        }.toString()

        val req = Request.Builder()
            .url("$baseUrl/audio/speech")
            .post(body.toRequestBody("application/json".toMediaType()))
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                val msg = runCatching {
                    json.parseToJsonElement(errBody).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                }.getOrNull() ?: errBody.take(200)
                throw IllegalStateException("HTTP ${resp.code}: $msg")
            }
            val tmp = File(dir, "$key.part")
            resp.body?.byteStream()?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                ?: throw IllegalStateException("Empty response")
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
        return file
    }

    override suspend fun prepare(segment: Segment) {
        fetch(segment)
    }

    override suspend fun speak(segment: Segment, rate: Float, pitch: Float, locale: Locale?, onStart: () -> Unit) {
        val file = fetch(segment).await()
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
                val mp = MediaPlayer()
                current = mp
                fun finish() {
                    if (current === mp) current = null
                    runCatching { mp.reset() }
                    runCatching { mp.release() }
                }
                cont.invokeOnCancellation {
                    runCatching { if (mp.isPlaying) mp.stop() }
                    finish()
                }
                try {
                    mp.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    mp.setDataSource(file.absolutePath)
                    mp.setOnPreparedListener {
                        runCatching { mp.playbackParams = PlaybackParams().setSpeed(rate).setPitch(pitch) }
                        mp.start()
                        onStart()
                    }
                    mp.setOnCompletionListener {
                        finish()
                        if (cont.isActive) cont.resume(Unit)
                    }
                    mp.setOnErrorListener { _, what, extra ->
                        finish()
                        if (cont.isActive) cont.resumeWithException(IllegalStateException("MediaPlayer error $what/$extra"))
                        true
                    }
                    mp.prepareAsync()
                } catch (e: Exception) {
                    finish()
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        }
    }

    override fun applyLive(rate: Float, pitch: Float) {
        val mp = current ?: return
        runCatching {
            if (mp.isPlaying) mp.playbackParams = PlaybackParams().setSpeed(rate).setPitch(pitch)
        }
    }

    override fun release() {
        scope.cancel()
        current?.let { mp ->
            current = null
            runCatching { mp.stop() }
            runCatching { mp.release() }
        }
    }

    companion object {
        /** Bigger chunks mean fewer requests; the OpenAI limit is 4096 characters per request. */
        const val MAX_CHARS = 1500

        fun cacheDir(context: Context): File = File(context.cacheDir, "tts").apply { mkdirs() }

        fun cacheSizeBytes(context: Context): Long =
            cacheDir(context).listFiles()?.sumOf { it.length() } ?: 0L

        fun clearCache(context: Context) {
            cacheDir(context).listFiles()?.forEach { it.delete() }
        }
    }
}
