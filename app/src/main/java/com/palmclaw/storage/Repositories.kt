package com.palmclaw.storage

import android.util.Log
import com.palmclaw.storage.dao.MessageDao
import com.palmclaw.storage.dao.SessionDao
import com.palmclaw.storage.entities.MessageEntity
import com.palmclaw.storage.entities.SessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class MessageRepository(
    private val dao: MessageDao
) {
    fun observeMessages(sessionId: String): Flow<List<MessageEntity>> = dao.observeBySession(sessionId)

    suspend fun getMessages(sessionId: String): List<MessageEntity> = withContext(Dispatchers.IO) {
        dao.getBySession(sessionId)
    }

    suspend fun getLatestAssistantMessage(sessionId: String): MessageEntity? = withContext(Dispatchers.IO) {
        dao.getLatestAssistantBySession(sessionId)
    }

    suspend fun appendUserMessage(sessionId: String, content: String): Long {
        return append(sessionId = sessionId, role = "user", content = content)
    }

    suspend fun appendMessage(sessionId: String, role: String, content: String): Long {
        return append(sessionId = sessionId, role = role, content = content)
    }

    suspend fun appendAssistantMessage(
        sessionId: String,
        content: String,
        toolCallJson: String? = null
    ) {
        append(sessionId = sessionId, role = "assistant", content = content, toolCallJson = toolCallJson)
    }

    suspend fun appendToolMessage(
        sessionId: String,
        content: String,
        toolResultJson: String? = null
    ) {
        append(sessionId = sessionId, role = "tool", content = content, toolResultJson = toolResultJson)
    }

    suspend fun createAssistantPlaceholder(sessionId: String): Long = withContext(Dispatchers.IO) {
        dao.insert(
            MessageEntity(
                sessionId = sessionId,
                role = "assistant",
                content = "",
                createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun appendAssistantDelta(messageId: Long, delta: String) = withContext(Dispatchers.IO) {
        dao.appendMessageContent(messageId, delta)
        Log.d(TAG, "appendAssistantDelta messageId=$messageId deltaSize=${delta.length}")
    }

    suspend fun finalizeAssistant(
        messageId: Long,
        finalContent: String,
        toolCallJson: String? = null
    ) = withContext(Dispatchers.IO) {
        dao.updateMessageContent(messageId, finalContent)
        dao.updateToolCallJson(messageId, toolCallJson)
    }

    suspend fun deleteMessage(messageId: Long) = withContext(Dispatchers.IO) {
        dao.deleteById(messageId)
    }

    private suspend fun append(
        sessionId: String,
        role: String,
        content: String,
        toolCallJson: String? = null,
        toolResultJson: String? = null
    ): Long = withContext(Dispatchers.IO) {
        return@withContext dao.insert(
            MessageEntity(
                sessionId = sessionId,
                role = role,
                content = content,
                createdAt = System.currentTimeMillis(),
                toolCallJson = toolCallJson,
                toolResultJson = toolResultJson
            )
        )
    }

    companion object {
        private const val TAG = "MessageRepository"
    }
}

class SessionRepository(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao
) {
    fun observeSessions(): Flow<List<SessionEntity>> = sessionDao.observeAll()

    suspend fun listSessions(): List<SessionEntity> = withContext(Dispatchers.IO) {
        sessionDao.getAll()
    }

    suspend fun touch(sessionId: String) = withContext(Dispatchers.IO) {
        sessionDao.touch(sessionId, System.currentTimeMillis())
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        messageDao.clearSession(sessionId)
        sessionDao.delete(sessionId)
    }

    suspend fun ensureSessionExists(sessionId: String, title: String? = null) = withContext(Dispatchers.IO) {
        val existing = sessionDao.getById(sessionId)
        if (existing != null) return@withContext
        val now = System.currentTimeMillis()
        sessionDao.insert(
            SessionEntity(
                id = sessionId,
                title = title ?: "Gateway $sessionId",
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun createSession(sessionId: String, title: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        sessionDao.insert(
            SessionEntity(
                id = sessionId,
                title = title,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun renameSession(sessionId: String, title: String) = withContext(Dispatchers.IO) {
        sessionDao.rename(sessionId, title, System.currentTimeMillis())
    }

    suspend fun collapseToSharedSession(sharedSessionId: String, sharedTitle: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = sessionDao.getById(sharedSessionId)
        if (existing == null) {
            sessionDao.insert(
                SessionEntity(
                    id = sharedSessionId,
                    title = sharedTitle,
                    createdAt = now,
                    updatedAt = now
                )
            )
        } else {
            if (existing.title != sharedTitle) {
                sessionDao.rename(sharedSessionId, sharedTitle, now)
            } else {
                sessionDao.touch(sharedSessionId, now)
            }
        }
        messageDao.moveAllMessagesToSession(sharedSessionId)
        sessionDao.deleteAllExcept(sharedSessionId)
    }
}
