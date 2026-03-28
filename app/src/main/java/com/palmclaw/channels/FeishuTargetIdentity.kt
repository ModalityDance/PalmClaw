package com.palmclaw.channels

private val FEISHU_TARGET_ID_REGEX = Regex("((?:ou|oc)_[A-Za-z0-9_-]+)")

fun normalizeFeishuTargetId(raw: String): String {
    val trimmed = raw.trim()
    return FEISHU_TARGET_ID_REGEX.find(trimmed)?.groupValues?.getOrNull(1) ?: trimmed
}

fun resolveFeishuBindingTarget(
    chatType: String,
    sourceChatId: String,
    senderOpenId: String
): String {
    val normalizedSourceChatId = normalizeFeishuTargetId(sourceChatId)
    val normalizedSenderOpenId = normalizeFeishuTargetId(senderOpenId)
    return if (chatType.equals("p2p", ignoreCase = true)) {
        normalizedSourceChatId.ifBlank { normalizedSenderOpenId }
    } else {
        normalizedSourceChatId.ifBlank { normalizedSenderOpenId }
    }
}

fun buildFeishuTargetAliases(
    primaryTargetId: String,
    sourceChatId: String = "",
    senderOpenId: String = ""
): List<String> {
    return linkedSetOf(
        normalizeFeishuTargetId(primaryTargetId),
        normalizeFeishuTargetId(sourceChatId),
        normalizeFeishuTargetId(senderOpenId)
    )
        .filter { it.isNotBlank() }
        .toList()
}
