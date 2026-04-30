package com.palmclaw.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.palmclaw.storage.entities.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    fun observeBySession(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    suspend fun getBySession(sessionId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND role = 'assistant' ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun getLatestAssistantBySession(sessionId: String): MessageEntity?

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("UPDATE messages SET content = :content WHERE id = :id")
    suspend fun updateMessageContent(id: Long, content: String)

    @Query("UPDATE messages SET content = content || :delta WHERE id = :id")
    suspend fun appendMessageContent(id: Long, delta: String)

    @Query("UPDATE messages SET toolCallJson = :toolCallJson WHERE id = :id")
    suspend fun updateToolCallJson(id: Long, toolCallJson: String?)

    @Query("UPDATE messages SET attachmentsJson = :attachmentsJson WHERE id = :id")
    suspend fun updateAttachmentsJson(id: Long, attachmentsJson: String?)

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): MessageEntity?

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun clearSession(sessionId: String)

    @Query("UPDATE messages SET sessionId = :targetSessionId WHERE sessionId != :targetSessionId")
    suspend fun moveAllMessagesToSession(targetSessionId: String): Int
}
