package com.enya.ollama.data.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal client for a locally hosted Ollama server (https://github.com/ollama/ollama).
 * Only the two endpoints this app needs are covered: /api/tags and /api/chat.
 */
class OllamaApi {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // streaming responses can run indefinitely
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun listModels(baseUrl: String, authHeader: String? = null): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val url = "${normalizeBaseUrl(baseUrl)}/api/tags"
            val request = Request.Builder().url(url).applyAuth(authHeader).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}"))
                }
                val bodyString = response.body?.string().orEmpty()
                val parsed = json.decodeFromString<TagsResponse>(bodyString)
                Result.success(parsed.models.map { it.name })
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun streamChat(
        baseUrl: String,
        model: String,
        messages: List<OllamaMessage>,
        authHeader: String? = null
    ): Flow<StreamEvent> = callbackFlow {
        val url = "${normalizeBaseUrl(baseUrl)}/api/chat"
        val payload = json.encodeToString(ChatRequest.serializer(), ChatRequest(model, messages, stream = true))
        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).applyAuth(authHeader).post(body).build()
        val call = client.newCall(request)

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                trySend(StreamEvent.Error(e.message ?: "Network error"))
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val errorBody = resp.body?.string()
                        trySend(StreamEvent.Error("HTTP ${resp.code}${errorBody?.let { ": $it" } ?: ""}"))
                        close()
                        return
                    }
                    val source = resp.body?.source()
                    if (source == null) {
                        close()
                        return
                    }
                    try {
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isBlank()) continue
                            val chunk = runCatching {
                                json.decodeFromString(ChatStreamChunk.serializer(), line)
                            }.getOrNull() ?: continue

                            if (!chunk.error.isNullOrEmpty()) {
                                trySend(StreamEvent.Error(chunk.error))
                                break
                            }
                            val delta = chunk.message?.content
                            if (!delta.isNullOrEmpty()) {
                                trySend(StreamEvent.Delta(delta))
                            }
                            if (chunk.done) {
                                trySend(StreamEvent.Done)
                                break
                            }
                        }
                    } catch (e: IOException) {
                        trySend(StreamEvent.Error(e.message ?: "Stream interrupted"))
                    } finally {
                        close()
                    }
                }
            }
        })

        awaitClose { call.cancel() }
    }

    private fun Request.Builder.applyAuth(authHeader: String?): Request.Builder =
        if (!authHeader.isNullOrBlank()) header("Authorization", authHeader) else this

    private fun normalizeBaseUrl(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) url = "http://127.0.0.1:11434"
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
        if (url.endsWith("/")) url = url.dropLast(1)
        return url
    }
}
