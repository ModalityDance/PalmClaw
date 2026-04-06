package com.palmclaw.ui

/**
 * Presentation-only models used by the chat transcript and media preview UI.
 */
data class UiMessage(
    val id: Long,
    val role: String,
    val content: String,
    val createdAt: Long,
    val isCollapsible: Boolean = false,
    val expandedContent: String? = null,
    val attachments: List<UiMediaAttachment> = emptyList()
)

data class UiMediaAttachment(
    val reference: String,
    val kind: UiMediaKind,
    val label: String
)

enum class UiMediaKind {
    Image,
    Video,
    Audio
}
