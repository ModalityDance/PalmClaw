package com.palmclaw.storage

import android.util.Log
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.palmclaw.attachments.AttachmentRecordRepository
import com.palmclaw.bus.MessageAttachment
import com.palmclaw.bus.MessageAttachmentJsonCodec
import com.palmclaw.config.AppSession
import com.palmclaw.storage.dao.MessageDao
import com.palmclaw.storage.dao.SessionDao
import com.palmclaw.storage.entities.MessageEntity
import com.palmclaw.storage.entities.SessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class MessageRepository(
    private val dao: MessageDao,
    private val attachmentRecordRepository: AttachmentRecordRepository? = null,
    private val database: RoomDatabase? = null
) {
    fun observeMessages(sessionId: String): Flow<List<MessageEntity>> = dao.observeBySession(sessionId)

    suspend fun getMessages(sessionId: String): List<MessageEntity> = withContext(Dispatchers.IO) {
        dao.getBySession(sessionId)
    }

    suspend fun getLatestAssistantMessage(sessionId: String): MessageEntity? = withContext(Dispatchers.IO) {
        dao.getLatestAssistantBySession(sessionId)
    }

    suspend fun getMessageById(messageId: Long): MessageEntity? = withContext(Dispatchers.IO) {
        dao.getById(messageId)
    }

    suspend fun appendUserMessage(
        sessionId: String,
        content: String,
        attachments: List<MessageAttachment> = emptyList()
    ): Long {
        return append(sessionId = sessionId, role = "user", content = content, attachments = attachments)
    }

    suspend fun appendMessage(
        sessionId: String,
        role: String,
        content: String,
        attachments: List<MessageAttachment> = emptyList()
    ): Long {
        return append(sessionId = sessionId, role = role, content = content, attachments = attachments)
    }

    suspend fun appendAssistantMessage(
        sessionId: String,
        content: String,
        toolCallJson: String? = null,
        attachments: List<MessageAttachment> = emptyList()
    ): Long {
        return append(
            sessionId = sessionId,
            role = "assistant",
            content = content,
            toolCallJson = toolCallJson,
            attachments = attachments
        )
    }

    suspend fun appendToolMessage(
        sessionId: String,
        content: String,
        toolResultJson: String? = null,
        attachments: List<MessageAttachment> = emptyList()
    ): Long {
        return append(
            sessionId = sessionId,
            role = "tool",
            content = content,
            toolResultJson = toolResultJson,
            attachments = attachments
        )
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
        attachmentRecordRepository?.deleteForMessage(messageId)
        dao.deleteById(messageId)
    }

    suspend fun syncMessageAttachments(
        messageId: Long,
        sessionId: String,
        role: String,
        attachments: List<MessageAttachment>,
        direction: String = when (role) {
            "user", "internal_user" -> "inbound"
            else -> "outbound"
        }
    ) = withContext(Dispatchers.IO) {
        attachmentRecordRepository?.replaceForMessage(
            messageId = messageId,
            sessionId = sessionId,
            ownerRole = role,
            attachments = attachments,
            direction = direction
        )
    }

    private suspend fun append(
        sessionId: String,
        role: String,
        content: String,
        toolCallJson: String? = null,
        toolResultJson: String? = null,
        attachments: List<MessageAttachment> = emptyList()
    ): Long = withContext(Dispatchers.IO) {
        val writeMessage = suspend {
            val messageId = dao.insert(
                MessageEntity(
                    sessionId = sessionId,
                    role = role,
                    content = content,
                    createdAt = System.currentTimeMillis(),
                    toolCallJson = toolCallJson,
                    toolResultJson = toolResultJson,
                    attachmentsJson = MessageAttachmentJsonCodec.encode(attachments)
                )
            )
            attachmentRecordRepository?.replaceForMessage(
                messageId = messageId,
                sessionId = sessionId,
                ownerRole = role,
                attachments = attachments,
                direction = when (role) {
                    "user", "internal_user" -> "inbound"
                    else -> "outbound"
                }
            )
            messageId
        }
        return@withContext if (database != null) {
            database.withTransaction { writeMessage() }
        } else {
            writeMessage()
        }
    }

    companion object {
        private const val TAG = "MessageRepository"
    }
}

class SessionRepository(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val attachmentRecordRepository: AttachmentRecordRepository? = null
) {
    fun observeSessions(): Flow<List<SessionEntity>> = sessionDao.observeAll()

    suspend fun listSessions(): List<SessionEntity> = withContext(Dispatchers.IO) {
        sessionDao.getAll()
    }

    suspend fun getSession(sessionId: String): SessionEntity? = withContext(Dispatchers.IO) {
        sessionDao.getById(sessionId)
    }

    suspend fun touch(sessionId: String) = withContext(Dispatchers.IO) {
        sessionDao.touch(sessionId, System.currentTimeMillis())
    }

    suspend fun clearSessionMessages(sessionId: String) = withContext(Dispatchers.IO) {
        messageDao.clearSession(sessionId)
        attachmentRecordRepository?.deleteForSession(sessionId)
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        messageDao.clearSession(sessionId)
        attachmentRecordRepository?.deleteForSession(sessionId)
        sessionDao.delete(sessionId)
    }

    suspend fun ensureSessionExists(sessionId: String, title: String? = null) = withContext(Dispatchers.IO) {
        val existing = sessionDao.getById(sessionId)
        if (existing != null) return@withContext
        require(sessionId == AppSession.LOCAL_SESSION_ID) {
            "Implicit session creation is only allowed for the local session"
        }
        val now = System.currentTimeMillis()
        sessionDao.insert(
            SessionEntity(
                id = sessionId,
                title = title ?: AppSession.LOCAL_SESSION_TITLE,
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
        attachmentRecordRepository?.moveAllToSession(sharedSessionId)
        sessionDao.deleteAllExcept(sharedSessionId)
    }
}




