package com.enya.ollama.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: Long): ProjectEntity?

    @Insert
    suspend fun insert(project: ProjectEntity): Long

    @Update
    suspend fun update(project: ProjectEntity)

    @Delete
    suspend fun delete(project: ProjectEntity)
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :chatId")
    fun observeById(chatId: Long): Flow<ChatEntity?>

    @Query("SELECT * FROM chats WHERE id = :chatId")
    suspend fun getById(chatId: Long): ChatEntity?

    @Insert
    suspend fun insert(chat: ChatEntity): Long

    @Delete
    suspend fun delete(chat: ChatEntity)

    @Query("UPDATE chats SET model = :model, updatedAt = :updatedAt WHERE id = :chatId")
    suspend fun updateModel(chatId: Long, model: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE chats SET title = :title WHERE id = :chatId")
    suspend fun updateTitle(chatId: Long, title: String)

    @Query("UPDATE chats SET updatedAt = :updatedAt WHERE id = :chatId")
    suspend fun touch(chatId: Long, updatedAt: Long = System.currentTimeMillis())
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id ASC")
    fun observeForChat(chatId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id ASC")
    suspend fun getForChat(chatId: Long): List<MessageEntity>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("UPDATE messages SET content = :content, isStreaming = :isStreaming WHERE id = :id")
    suspend fun updateContent(id: Long, content: String, isStreaming: Boolean)

    @Query("UPDATE messages SET isStreaming = 0, isError = :isError WHERE id = :id")
    suspend fun finishStreaming(id: Long, isError: Boolean)

    @Delete
    suspend fun delete(message: MessageEntity)
}
