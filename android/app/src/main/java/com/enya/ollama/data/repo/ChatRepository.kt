package com.enya.ollama.data.repo

import com.enya.ollama.data.SettingsRepository
import com.enya.ollama.data.db.AppDatabase
import com.enya.ollama.data.db.ChatEntity
import com.enya.ollama.data.db.MessageEntity
import com.enya.ollama.data.db.ProjectEntity
import com.enya.ollama.data.db.Role
import com.enya.ollama.data.net.OllamaApi
import com.enya.ollama.data.net.OllamaMessage
import com.enya.ollama.data.net.StreamEvent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class ChatRepository(
    private val db: AppDatabase,
    private val api: OllamaApi,
    private val settings: SettingsRepository
) {
    fun observeProjects(): Flow<List<ProjectEntity>> = db.projectDao().observeAll()
    fun observeChats(): Flow<List<ChatEntity>> = db.chatDao().observeAll()
    fun observeChat(chatId: Long): Flow<ChatEntity?> = db.chatDao().observeById(chatId)
    fun observeMessages(chatId: Long): Flow<List<MessageEntity>> = db.messageDao().observeForChat(chatId)

    suspend fun createProject(name: String, systemPrompt: String?): Long =
        db.projectDao().insert(ProjectEntity(name = name, systemPrompt = systemPrompt?.takeIf { it.isNotBlank() }))

    suspend fun deleteProject(project: ProjectEntity) = db.projectDao().delete(project)

    suspend fun createChat(projectId: Long?, title: String, model: String): Long =
        db.chatDao().insert(ChatEntity(projectId = projectId, title = title, model = model))

    suspend fun deleteChat(chat: ChatEntity) = db.chatDao().delete(chat)

    suspend fun renameChat(chatId: Long, title: String) = db.chatDao().updateTitle(chatId, title)

    suspend fun switchModel(chatId: Long, model: String) {
        db.chatDao().updateModel(chatId, model)
        settings.setLastModel(model)
    }

    suspend fun fetchModels(): Result<List<String>> = api.listModels(settings.serverUrl.first())

    /**
     * Persists the user's message, then streams the assistant's reply into a single
     * placeholder row, updating it incrementally as tokens arrive.
     */
    suspend fun sendMessage(chatId: Long, userText: String) {
        val chat = db.chatDao().getById(chatId) ?: return
        db.messageDao().insert(MessageEntity(chatId = chatId, role = Role.USER, content = userText))
        db.chatDao().touch(chatId)

        val project = chat.projectId?.let { db.projectDao().getById(it) }
        val history = db.messageDao().getForChat(chatId).map { OllamaMessage(it.role, it.content) }
        val outgoing = buildList {
            project?.systemPrompt?.takeIf { it.isNotBlank() }?.let { add(OllamaMessage(Role.SYSTEM, it)) }
            addAll(history)
        }

        val assistantId = db.messageDao().insert(
            MessageEntity(chatId = chatId, role = Role.ASSISTANT, content = "", isStreaming = true)
        )

        val baseUrl = settings.serverUrl.first()
        val accumulated = StringBuilder()
        var hadError = false

        try {
            api.streamChat(baseUrl, chat.model, outgoing).collect { event ->
                when (event) {
                    is StreamEvent.Delta -> {
                        accumulated.append(event.text)
                        db.messageDao().updateContent(assistantId, accumulated.toString(), true)
                    }
                    is StreamEvent.Error -> {
                        hadError = true
                        if (accumulated.isEmpty()) accumulated.append("⚠️ ${event.message}")
                        db.messageDao().updateContent(assistantId, accumulated.toString(), false)
                    }
                    StreamEvent.Done -> Unit
                }
            }
        } finally {
            withContext(NonCancellable) {
                db.messageDao().finishStreaming(assistantId, hadError)
                db.chatDao().touch(chatId)
            }
        }
    }
}
