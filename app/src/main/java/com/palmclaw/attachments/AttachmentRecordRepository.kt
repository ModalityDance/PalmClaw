package com.palmclaw.attachments

import com.palmclaw.bus.MessageAttachment
import com.palmclaw.bus.MessageAttachmentJsonCodec
import com.palmclaw.bus.MessageAttachmentKind
import com.palmclaw.bus.MessageAttachmentSource
import com.palmclaw.bus.MessageAttachmentTransferState
import com.palmclaw.bus.inferMessageAttachmentSource
import com.palmclaw.bus.normalizeMessageAttachments
import com.palmclaw.storage.dao.AttachmentRecordDao
import com.palmclaw.storage.dao.MessageDao
import com.palmclaw.storage.entities.AttachmentRecordEntity
import java.util.UUID
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AttachmentRecordRepository(
    private val attachmentRecordDao: AttachmentRecordDao,
    private val messageDao: MessageDao
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    suspend fun replaceForMessage(
        messageId: Long,
        sessionId: String,
        ownerRole: String,
        attachments: List<MessageAttachment>,
        direction: String
    ) {
        val normalized = normalizeMessageAttachments(attachments = attachments)
        attachmentRecordDao.deleteByMessageId(messageId)
        if (normalized.isEmpty()) {
            messageDao.updateAttachmentsJson(messageId, null)
            return
        }
        val now = System.currentTimeMillis()
        val records = normalized.map { attachment ->
            AttachmentRecordEntity(
                attachmentId = buildAttachmentId(messageId, attachment),
                messageId = messageId,
                sessionId = sessionId,
                direction = direction,
                ownerRole = ownerRole,
                kind = attachment.kind.name.lowercase(),
                label = attachment.label,
                mimeType = attachment.mimeType,
                sizeBytes = attachment.sizeBytes,
                workspacePath = attachment.localWorkspacePath,
                sourceUri = attachment.reference,
                remoteLocator = attachment.metadata[KEY_REMOTE_LOCATOR],
                transferState = attachment.transferState.name.lowercase(),
                failureMessage = attachment.failureMessage,
                metadataJson = json.encodeToString(attachment.metadata),
                createdAtMs = now,
                updatedAtMs = now
            )
        }
        attachmentRecordDao.insertAll(records)
        messageDao.updateAttachmentsJson(messageId, MessageAttachmentJsonCodec.encode(normalized))
    }

    suspend fun loadMessageAttachments(messageId: Long): List<MessageAttachment> {
        return attachmentRecordDao.getByMessageId(messageId)
            .map { entity ->
                MessageAttachment(
                    kind = decodeKind(entity.kind),
                    reference = entity.workspacePath?.takeIf { it.isNotBlank() }
                        ?: entity.sourceUri.orEmpty(),
                    label = entity.label,
                    mimeType = entity.mimeType,
                    sizeBytes = entity.sizeBytes,
                    source = decodeSource(entity),
                    transferState = decodeTransferState(entity.transferState),
                    failureMessage = entity.failureMessage,
                    localWorkspacePath = entity.workspacePath,
                    isRemoteBacked = !entity.remoteLocator.isNullOrBlank(),
                    metadata = decodeMetadata(entity)
                )
            }
            .filter { it.reference.isNotBlank() }
    }

    suspend fun deleteForMessage(messageId: Long) {
        attachmentRecordDao.deleteByMessageId(messageId)
    }

    suspend fun deleteForSession(sessionId: String) {
        attachmentRecordDao.deleteBySessionId(sessionId)
    }

    suspend fun moveAllToSession(sessionId: String) {
        attachmentRecordDao.moveAllToSession(sessionId)
    }

    private fun decodeMetadata(entity: AttachmentRecordEntity): Map<String, String> {
        val base = runCatching {
            json.decodeFromString<Map<String, String>>(entity.metadataJson.orEmpty())
        }.getOrDefault(emptyMap())
        return if (entity.remoteLocator.isNullOrBlank()) {
            base
        } else {
            base + (KEY_REMOTE_LOCATOR to entity.remoteLocator)
        }
    }

    private fun buildAttachmentId(messageId: Long, attachment: MessageAttachment): String {
        val seed = listOf(
            messageId.toString(),
            attachment.reference,
            attachment.label,
            attachment.mimeType.orEmpty()
        ).joinToString("|")
        return UUID.nameUUIDFromBytes(seed.toByteArray(Charsets.UTF_8)).toString()
    }

    private fun decodeKind(raw: String): MessageAttachmentKind {
        return when (raw.lowercase()) {
            "image" -> MessageAttachmentKind.Image
            "video" -> MessageAttachmentKind.Video
            "audio" -> MessageAttachmentKind.Audio
            else -> MessageAttachmentKind.File
        }
    }

    private fun decodeTransferState(raw: String): MessageAttachmentTransferState {
        return when (raw.lowercase()) {
            "draft" -> MessageAttachmentTransferState.Draft
            "importing" -> MessageAttachmentTransferState.Importing
            "uploading" -> MessageAttachmentTransferState.Uploading
            "uploaded" -> MessageAttachmentTransferState.Uploaded
            "downloading" -> MessageAttachmentTransferState.Downloading
            "downloaded" -> MessageAttachmentTransferState.Downloaded
            "failed" -> MessageAttachmentTransferState.Failed
            else -> MessageAttachmentTransferState.Ready
        }
    }

    private fun decodeSource(entity: AttachmentRecordEntity): MessageAttachmentSource {
        return when {
            !entity.remoteLocator.isNullOrBlank() -> MessageAttachmentSource.Remote
            !entity.workspacePath.isNullOrBlank() -> MessageAttachmentSource.Local
            !entity.sourceUri.isNullOrBlank() -> inferMessageAttachmentSource(entity.sourceUri)
            else -> MessageAttachmentSource.Unknown
        }
    }

    companion object {
        const val KEY_REMOTE_LOCATOR = "remote_locator"
    }
}
