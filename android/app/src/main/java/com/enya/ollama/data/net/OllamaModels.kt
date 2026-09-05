package com.enya.ollama.data.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OllamaMessage(
    val role: String,
    val content: String,
    val images: List<String>? = null
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val stream: Boolean = true
)

@Serializable
data class ChatStreamChunk(
    val model: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val message: OllamaMessage? = null,
    val done: Boolean = false,
    @SerialName("done_reason") val doneReason: String? = null,
    val error: String? = null
)

@Serializable
data class TagsResponse(
    val models: List<ModelInfo> = emptyList()
)

@Serializable
data class ModelInfo(
    val name: String,
    val model: String? = null,
    val size: Long? = null,
    @SerialName("modified_at") val modifiedAt: String? = null
)

sealed interface StreamEvent {
    data class Delta(val text: String) : StreamEvent
    data object Done : StreamEvent
    data class Error(val message: String) : StreamEvent
}
