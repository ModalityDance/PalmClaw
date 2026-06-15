package com.palmclaw.storage.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "attachment_records",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["messageId", "updatedAtMs"]),
        Index(value = ["sessionId", "updatedAtMs"])
    ]
)
data class AttachmentRecordEntity(
    @PrimaryKey val attachmentId: String,
    val messageId: Long,
    val sessionId: String,
    val direction: String,
    val ownerRole: String,
    val kind: String,
    val label: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val workspacePath: String? = null,
    val sourceUri: String? = null,
    val remoteLocator: String? = null,
    val transferState: String,
    val failureMessage: String? = null,
    val metadataJson: String? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long
)
