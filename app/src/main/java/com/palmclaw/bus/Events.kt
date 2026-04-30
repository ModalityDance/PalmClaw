package com.palmclaw.bus

data class InboundMessage(
    val channel: String,
    val senderId: String,
    val chatId: String,
    val content: String,
    val media: List<String> = emptyList(),
    val attachments: List<MessageAttachment> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val sessionKeyOverride: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    val sessionKey: String
        get() = sessionKeyOverride ?: "$channel:$chatId"

    val normalizedAttachments: List<MessageAttachment>
        get() = normalizeMessageAttachments(attachments = attachments, legacyMedia = media)
}

data class OutboundMessage(
    val channel: String,
    val chatId: String,
    val content: String,
    val replyTo: String? = null,
    val media: List<String> = emptyList(),
    val attachments: List<MessageAttachment> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
) {
    val normalizedAttachments: List<MessageAttachment>
        get() = normalizeMessageAttachments(attachments = attachments, legacyMedia = media)
}
