package com.enya.txtvoice.tts

import android.content.Context
import com.enya.txtvoice.R
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
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Speech through an OpenAI-compatible `POST {baseUrl}/audio/speech` endpoint (OpenAI gpt-4o-mini-tts,
 * Kokoro-FastAPI, LocalAI, ...).
 */
class OpenAiTtsEngine(
    context: Context,
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val voice: String,
    private val instructions: String
) : AudioFileTtsEngine(context) {

    override val maxSegmentChars: Int get() = MAX_CHARS

    override val cacheSalt: String = listOf("openai", baseUrl, model, voice, instructions).joinToString("|")

    private val json = Json { ignoreUnknownKeys = true }

    override fun synthesize(text: String, locale: Locale?, target: File) {
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
            resp.body?.byteStream()?.use { input -> target.outputStream().use { input.copyTo(it) } }
                ?: throw IllegalStateException("Empty response")
        }
    }

    companion object {
        /** Bigger chunks mean fewer requests; the OpenAI limit is 4096 characters per request. */
        const val MAX_CHARS = 1500

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}
