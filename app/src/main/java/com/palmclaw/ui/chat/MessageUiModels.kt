package com.palmclaw.ui

import com.palmclaw.bus.MessageAttachmentSource
import com.palmclaw.bus.MessageAttachmentTransferState

/**
 * Presentation-only models used by the chat transcript and attachment preview UI.
 */
data class UiMessage(
    val id: Long,
    val role: String,
    val content: String,
    val createdAt: Long,
    val isCollapsible: Boolean = false,
    val expandedContent: String? = null,
    val attachments: List<UiAttachment> = emptyList()
)

data class UiAttachment(
    val reference: String,
    val kind: UiAttachmentKind,
    val label: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val source: MessageAttachmentSource = MessageAttachmentSource.Unknown,
    val transferState: MessageAttachmentTransferState = MessageAttachmentTransferState.Ready,
    val failureMessage: String? = null,
    val isRemoteBacked: Boolean = false,
    val localWorkspacePath: String? = null
)

enum class UiAttachmentKind {
    Image,
    Video,
    Audio,
    File
}

data class UiComposerAttachmentDraft(
    val id: String,
    val attachment: UiAttachment
)
