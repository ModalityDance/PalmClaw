package com.palmclaw.ui

import androidx.compose.runtime.Composable
import java.util.Locale

internal fun irreversibleConfirmMessage(
    prompt: String,
    useChinese: Boolean
): String {
    return if (useChinese) {
        "$prompt\n此操作不可撤销。"
    } else {
        "$prompt\nThis cannot be undone."
    }
}

@Composable
internal fun channelDisplayLabel(channel: String): String {
    return when (channel.trim().lowercase(Locale.getDefault())) {
        "telegram" -> uiLabel("Telegram")
        "discord" -> uiLabel("Discord")
        "slack" -> uiLabel("Slack")
        "feishu" -> uiLabel("Feishu")
        "email" -> uiLabel("Email")
        "wecom" -> uiLabel("WeCom")
        else -> uiLabel(
            channel.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            }
        )
    }
}

@Composable
internal fun sessionConnectionTargetLabel(
    channel: String,
    target: String,
    pendingDetection: Boolean = false
): String {
    if (channel.isBlank()) return tr("This session stays local.", "")
    val normalizedTarget = target.trim()
    return when {
        normalizedTarget.isNotBlank() -> normalizedTarget
        pendingDetection -> uiLabel("Pending detection")
        else -> uiLabel("Not configured")
    }
}

@Composable
internal fun uiLabel(text: String): String =
    localizedText(text, useChinese = LocalUiLanguage.current == UiLanguage.Chinese)
