package com.enya.ollama.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val systemPrompt: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "chats",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("projectId")]
)
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long? = null,
    val title: String,
    val model: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("chatId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val role: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val isError: Boolean = false
)

object Role {
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val SYSTEM = "system"
}
