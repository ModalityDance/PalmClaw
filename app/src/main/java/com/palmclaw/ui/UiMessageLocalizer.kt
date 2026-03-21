package com.palmclaw.ui

fun localizedUiMessage(raw: String, useChinese: Boolean): String {
    if (!useChinese) return raw
    val text = raw.trim()
    if (text.isBlank()) return raw
    return localizeUiMessageToChinese(text)
}

fun shouldLocalizeUiMessage(raw: String): Boolean {
    val text = raw.trim()
    if (text.isBlank()) return false
    if (exactUiMessageTranslations.containsKey(text)) return true
    if (PROVIDER_HTTP_REGEX.matches(text)) return true
    if (PROVIDER_STREAM_HTTP_REGEX.matches(text)) return true
    if (PROVIDER_STREAM_FAILED_REGEX.matches(text)) return true
    if (PROVIDER_MISSING_CHOICES_REGEX.matches(text)) return true
    if (PROVIDER_STREAM_CLOSED_REGEX.matches(text)) return true
    if (UNEXPECTED_CONTENT_TYPE_REGEX.matches(text)) return true
    if (GENERIC_HTTP_REGEX.matches(text)) return true
    if (UNSUPPORTED_CHANNEL_REGEX.matches(text)) return true
    if (CRON_LIMIT_REGEX.matches(text)) return true
    if (EVERY_MS_MIN_REGEX.matches(text)) return true
    if (UNKNOWN_TIMEZONE_REGEX.matches(text)) return true
    if (UNKNOWN_SCHEDULE_KIND_REGEX.matches(text)) return true
    if (ALWAYS_ON_RESTART_SCHEDULED_REGEX.matches(text)) return true
    return PREFIXED_MESSAGE_REGEX.matchEntire(text)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(prefixedUiMessageTranslations::containsKey) == true
}

private fun localizeUiMessageToChinese(text: String): String {
    exactUiMessageTranslations[text]?.let { return it }
    localizeProviderHttpMessage(text)?.let { return it }
    localizePrefixedUiMessage(text)?.let { return it }
    localizeSpecialUiMessage(text)?.let { return it }
    return localizeCommonFragments(text)
}

private fun localizePrefixedUiMessage(text: String): String? {
    val match = PREFIXED_MESSAGE_REGEX.matchEntire(text) ?: return null
    val prefix = match.groupValues[1]
    val detail = match.groupValues[2]
    val translatedPrefix = prefixedUiMessageTranslations[prefix] ?: return null
    return "$translatedPrefix：${localizeUiDetail(detail)}"
}

private fun localizeSpecialUiMessage(text: String): String? {
    UNSUPPORTED_CHANNEL_REGEX.matchEntire(text)?.let { match ->
        return "不支持的渠道：${match.groupValues[1]}"
    }
    GENERIC_HTTP_REGEX.matchEntire(text)?.let { match ->
        val code = match.groupValues[1].toIntOrNull()
        val detail = match.groupValues[2]
        return buildHttpMessage(
            scope = "请求失败",
            code = code,
            detail = detail
        )
    }
    CRON_LIMIT_REGEX.matchEntire(text)?.let { match ->
        return "Cron 任务数量已达到上限（${match.groupValues[1]}）。"
    }
    EVERY_MS_MIN_REGEX.matchEntire(text)?.let { match ->
        return "everyMs 必须大于等于 ${match.groupValues[1]}。"
    }
    UNKNOWN_TIMEZONE_REGEX.matchEntire(text)?.let { match ->
        return "未知时区：'${match.groupValues[1]}'。"
    }
    UNKNOWN_SCHEDULE_KIND_REGEX.matchEntire(text)?.let { match ->
        return "未知的调度类型：'${match.groupValues[1]}'。"
    }
    ALWAYS_ON_RESTART_SCHEDULED_REGEX.matchEntire(text)?.let { match ->
        return "常驻模式已安排重启（${match.groupValues[1]}）：${localizeUiDetail(match.groupValues[2])}"
    }
    return null
}

private fun localizeProviderHttpMessage(text: String): String? {
    PROVIDER_STREAM_HTTP_REGEX.matchEntire(text)?.let { match ->
        val provider = match.groupValues[1]
        val code = match.groupValues[2].toIntOrNull()
        val detail = match.groupValues.getOrNull(3).orEmpty()
        return buildHttpMessage(
            scope = "$provider 流式请求失败",
            code = code,
            detail = detail
        )
    }
    PROVIDER_HTTP_REGEX.matchEntire(text)?.let { match ->
        val provider = match.groupValues[1]
        val code = match.groupValues[2].toIntOrNull()
        val detail = match.groupValues.getOrNull(3).orEmpty()
        return buildHttpMessage(
            scope = "$provider API 请求失败",
            code = code,
            detail = detail
        )
    }
    PROVIDER_STREAM_FAILED_REGEX.matchEntire(text)?.let { match ->
        val provider = match.groupValues[1]
        val detail = match.groupValues.getOrNull(2).orEmpty()
        return if (detail.isBlank()) {
            "$provider 流式请求失败。"
        } else {
            "$provider 流式请求失败：${localizeUiDetail(detail)}"
        }
    }
    PROVIDER_MISSING_CHOICES_REGEX.matchEntire(text)?.let { match ->
        return "${match.groupValues[1]} 响应缺少 choices 字段。"
    }
    PROVIDER_STREAM_CLOSED_REGEX.matchEntire(text)?.let { match ->
        return "${match.groupValues[1]} 流式响应在 [DONE] 前提前结束。"
    }
    UNEXPECTED_CONTENT_TYPE_REGEX.matchEntire(text)?.let { match ->
        val contentType = match.groupValues[1]
        val body = match.groupValues.getOrNull(2).orEmpty()
        return if (body.isBlank()) {
            "响应内容类型异常：$contentType"
        } else {
            "响应内容类型异常：$contentType；详情：${localizeUiDetail(body)}"
        }
    }
    return null
}

private fun buildHttpMessage(scope: String, code: Int?, detail: String): String {
    val statusHint = code?.let(::httpStatusHintChinese)
    val headline = when {
        code != null && !statusHint.isNullOrBlank() -> "$scope（HTTP $code，$statusHint）"
        code != null -> "$scope（HTTP $code）"
        else -> scope
    }
    val normalizedDetail = detail.trim()
    return if (normalizedDetail.isBlank()) {
        "$headline。"
    } else {
        "$headline：${localizeUiDetail(normalizedDetail)}"
    }
}

private fun localizeUiDetail(detail: String): String {
    val text = detail.trim()
    if (text.isBlank()) return detail
    exactUiMessageTranslations[text]?.let { return it }
    localizeProviderHttpMessage(text)?.let { return it }
    localizeSpecialUiMessage(text)?.let { return it }
    return localizeCommonFragments(text)
}

private fun localizeCommonFragments(text: String): String {
    exactUiMessageTranslations[text]?.let { return it }
    unableToResolveHost(text)?.let { return it }
    failedToConnect(text)?.let { return it }
    return commonFragmentTranslations.entries.fold(text) { acc, (source, target) ->
        acc.replace(source, target, ignoreCase = false)
    }
}

private fun unableToResolveHost(text: String): String? {
    val match = UNABLE_TO_RESOLVE_HOST_REGEX.matchEntire(text) ?: return null
    return "无法解析主机 \"${match.groupValues[1]}\"，请检查网络连接、域名或 Base URL。"
}

private fun failedToConnect(text: String): String? {
    val match = FAILED_TO_CONNECT_REGEX.matchEntire(text) ?: return null
    return "连接到 ${match.groupValues[1]} 失败。"
}

private fun httpStatusHintChinese(code: Int): String? {
    return when (code) {
        400 -> "请求参数无效"
        401 -> "认证失败，请检查 API Key"
        403 -> "访问被拒绝，请检查权限或账号状态"
        404 -> "接口不存在，请检查 Base URL"
        408 -> "请求超时"
        409 -> "请求冲突"
        413 -> "请求内容过大"
        415 -> "请求格式不受支持"
        422 -> "请求内容无法处理"
        429 -> "请求过于频繁或额度不足"
        in 500..599 -> "服务端异常"
        else -> null
    }
}

private val exactUiMessageTranslations = mapOf(
    "Provider token stats cleared." to "提供方 Token 统计已清除。",
    "Setup failed" to "初始化失败",
    "Current session cleared." to "当前会话已清空。",
    "Session name is required." to "会话名称不能为空。",
    "Session created." to "会话已创建。",
    "LOCAL session cannot be renamed." to "LOCAL 会话不能重命名。",
    "Session renamed." to "会话已重命名。",
    "Local session cannot be deleted." to "本地会话不能删除。",
    "Session deleted." to "会话已删除。",
    "Session channel binding saved. Channels gateway enabled." to "会话渠道绑定已保存，渠道网关已启用。",
    "Session channel binding saved. Channels gateway disabled (no active session channel)." to "会话渠道绑定已保存，渠道网关已关闭（当前没有启用中的会话渠道）。",
    "Telegram token saved. Tap Detect Chats, choose the conversation, then save again." to "Telegram Token 已保存。请先点“检测会话”，选择目标会话后再保存一次。",
    "Feishu credentials saved. Long connection starting. Send a message to the bot, then use Detect Chats to finish binding." to "飞书凭据已保存，长连接正在启动。先给机器人发一条消息，再用“检测会话”完成绑定。",
    "Email account saved. Mailbox polling starting. Send one email to this account, then use Detect Senders to finish binding." to "邮箱账号已保存，邮箱轮询正在启动。先向该邮箱发送一封邮件，再用“检测发件人”完成绑定。",
    "WeCom credentials saved. Long connection starting. Send a message to the bot, then use Detect Chats to finish binding." to "企微凭据已保存，长连接正在启动。先给机器人发一条消息，再用“检测会话”完成绑定。",
    "Session channel binding saved." to "会话渠道绑定已保存。",
    "Session channel enabled." to "会话渠道已启用。",
    "Session channel disabled." to "会话渠道已停用。",
    "Please enter Telegram bot token first." to "请先输入 Telegram Bot Token。",
    "No chats discovered yet. Send a message to the bot first." to "还没有检测到会话。请先给机器人发送一条消息。",
    "Telegram chats discovered. Tap one to use." to "已检测到 Telegram 会话，点选即可使用。",
    "No Feishu chats discovered yet. Save credentials first, wait for long connection, then send a message to the bot." to "还没有检测到飞书会话。请先保存凭据，等待长连接建立后再给机器人发送消息。",
    "Feishu chats discovered. Tap one to use." to "已检测到飞书会话，点选即可使用。",
    "No email senders found. Check that the message reached INBOX, is visible over IMAP, and the mailbox credentials are correct." to "未找到邮件发件人。请检查邮件是否进入 INBOX、能否通过 IMAP 读取，以及邮箱凭据是否正确。",
    "Email senders discovered. Tap one to use." to "已检测到邮件发件人，点选即可使用。",
    "Email sender detection failed." to "检测邮件发件人失败。",
    "No WeCom chats discovered yet. Save Bot ID and Secret, send a message to the bot, then detect again." to "还没有检测到企微会话。请先保存 Bot ID 和 Secret，给机器人发送一条消息后再重新检测。",
    "WeCom chats discovered. Tap one to use." to "已检测到企微会话，点选即可使用。",
    "HEARTBEAT.md saved." to "HEARTBEAT.md 已保存。",
    "Cron logs cleared." to "Cron 日志已清空。",
    "Agent logs cleared." to "智能体日志已清空。",
    "Provider saved." to "提供方设置已保存。",
    "Runtime saved." to "运行时设置已保存。",
    "Cron saved." to "Cron 设置已保存。",
    "Heartbeat saved." to "Heartbeat 设置已保存。",
    "Always-on mode settings saved." to "常驻模式设置已保存。",
    "Channels synced." to "渠道设置已同步。",
    "MCP saved." to "MCP 设置已保存。",
    "Provider responded, but returned empty content." to "提供方已响应，但返回内容为空。",
    "Provider test passed." to "提供方测试通过。",
    "[Error] Empty assistant response." to "[错误] 助手返回了空响应。",
    "LLM request timed out (configured timeout reached). You can increase Runtime timeout settings and retry." to "LLM 请求超时（已达到当前配置的超时上限）。你可以调大 Runtime 中的超时设置后重试。",
    "Telegram bot token is required" to "Telegram Bot Token 为必填项。",
    "Telegram Chat ID must be numeric" to "Telegram Chat ID 必须是数字。",
    "Discord Channel ID is required" to "Discord Channel ID 为必填项。",
    "Discord Channel ID must be a numeric ID (15-30 digits)" to "Discord Channel ID 必须是 15 到 30 位数字 ID。",
    "Discord bot token is required" to "Discord Bot Token 为必填项。",
    "Discord response mode must be mention or open" to "Discord 回复模式必须是 mention 或 open。",
    "Slack channel ID is required" to "Slack Channel ID 为必填项。",
    "Slack channel ID must look like C/G/D + letters/numbers" to "Slack Channel ID 格式应类似 C/G/D 开头加字母数字。",
    "Slack bot token is required" to "Slack Bot Token 为必填项。",
    "Slack app token is required" to "Slack App Token 为必填项。",
    "Slack response mode must be mention or open" to "Slack 回复模式必须是 mention 或 open。",
    "Feishu App ID is required" to "飞书 App ID 为必填项。",
    "Feishu App Secret is required" to "飞书 App Secret 为必填项。",
    "Feishu target must look like ou_xxx or oc_xxx" to "飞书目标 ID 格式应类似 ou_xxx 或 oc_xxx。",
    "Email sender address is invalid" to "邮件发件人地址无效。",
    "Email mailbox consent must be enabled" to "必须启用邮箱授权。",
    "IMAP host is required" to "IMAP 主机为必填项。",
    "IMAP port must be between 1 and 65535" to "IMAP 端口必须在 1 到 65535 之间。",
    "IMAP username is required" to "IMAP 用户名为必填项。",
    "IMAP password is required" to "IMAP 密码为必填项。",
    "SMTP host is required" to "SMTP 主机为必填项。",
    "SMTP port must be between 1 and 65535" to "SMTP 端口必须在 1 到 65535 之间。",
    "SMTP username is required" to "SMTP 用户名为必填项。",
    "SMTP password is required" to "SMTP 密码为必填项。",
    "From address is required" to "发件地址为必填项。",
    "WeCom Bot ID is required" to "企微 Bot ID 为必填项。",
    "WeCom Secret is required" to "企微 Secret 为必填项。",
    "Max rounds must be a number" to "最大轮数必须是数字。",
    "Tool result max chars must be a number" to "工具结果最大字符数必须是数字。",
    "Memory consolidation window must be a number" to "记忆整理窗口必须是数字。",
    "LLM call timeout must be a number" to "LLM 调用超时必须是数字。",
    "LLM connect timeout must be a number" to "LLM 连接超时必须是数字。",
    "LLM read timeout must be a number" to "LLM 读取超时必须是数字。",
    "Default tool timeout must be a number" to "默认工具超时必须是数字。",
    "Context messages must be a number" to "上下文消息数必须是数字。",
    "Tool args preview max chars must be a number" to "工具参数预览最大字符数必须是数字。",
    "Cron min interval ms must be a number" to "Cron 最小间隔毫秒数必须是数字。",
    "Cron max jobs must be a number" to "Cron 最大任务数必须是数字。",
    "Heartbeat interval seconds must be a number" to "Heartbeat 间隔秒数必须是数字。",
    "MCP server names must be unique." to "MCP 服务器名称必须唯一。",
    "Enable MCP requires at least one configured server." to "启用 MCP 时，至少需要配置一个服务器。",
    "Base URL is required" to "Base URL 为必填项。",
    "Base URL is invalid" to "Base URL 无效。",
    "Base URL must start with http:// or https://" to "Base URL 必须以 http:// 或 https:// 开头。",
    "API key is empty. Please set API key in ConfigStore." to "API Key 为空，请先在设置中填写。",
    "Failed to parse stream chunk" to "解析流式响应分片失败。",
    "Read timed out" to "读取超时。",
    "timeout" to "请求超时。",
    "Connection refused" to "连接被拒绝。",
    "Canceled" to "已取消。",
    "SocketTimeoutException" to "连接超时。",
    "UnknownHostException" to "无法解析主机。",
    "ConnectException" to "连接失败。",
    "SSLHandshakeException" to "SSL 握手失败。",
    "SSLException" to "SSL 错误。",
    "EOFException" to "连接意外结束。",
    "IOException" to "网络 I/O 错误。",
    "IllegalArgumentException" to "参数无效。",
    "IllegalStateException" to "状态异常。",
    "invalid cron expression" to "Cron 表达式无效。",
    "tz can only be used with cron schedules" to "tz 只能与 cron 调度一起使用。",
    "at schedule requires atMs" to "at 调度必须提供 atMs。",
    "every schedule requires everyMs" to "every 调度必须提供 everyMs。",
    "everyMs must be > 0" to "everyMs 必须大于 0。",
    "cron schedule requires expr" to "cron 调度必须提供 expr。",
    "MCP server URL is required when MCP is enabled" to "启用 MCP 时，MCP 服务器 URL 为必填项。",
    "MCP server URL is invalid" to "MCP 服务器 URL 无效。",
    "MCP server URL must use http or https" to "MCP 服务器 URL 必须使用 http 或 https。",
    "Use HTTPS for non-local MCP endpoints" to "非本地 MCP 端点必须使用 HTTPS。",
    "Use HTTPS for non-local MCP endpoints." to "非本地 MCP 端点必须使用 HTTPS。"
)

private val prefixedUiMessageTranslations = mapOf(
    "Setup failed" to "初始化失败",
    "Load cron jobs failed" to "加载 Cron 任务失败",
    "Update cron job failed" to "更新 Cron 任务失败",
    "Run cron job failed" to "执行 Cron 任务失败",
    "Remove cron job failed" to "删除 Cron 任务失败",
    "Clear session failed" to "清空会话失败",
    "Create session failed" to "创建会话失败",
    "Rename session failed" to "重命名会话失败",
    "Delete session failed" to "删除会话失败",
    "Save session channel binding failed" to "保存会话渠道绑定失败",
    "Update session channel switch failed" to "更新会话渠道开关失败",
    "Discover chats failed" to "检测会话失败",
    "Save HEARTBEAT.md failed" to "保存 HEARTBEAT.md 失败",
    "Save failed" to "保存失败",
    "Provider test failed" to "提供方测试失败",
    "Runtime error" to "运行时错误",
    "Always-on restart scheduling failed" to "常驻模式重启调度失败",
    "Error" to "错误"
)

private val commonFragmentTranslations = linkedMapOf(
    "Incorrect API key provided" to "API Key 不正确",
    "Invalid API key" to "API Key 无效",
    "No auth credentials found" to "未找到认证凭据",
    "Read timed out" to "读取超时",
    "Connection refused" to "连接被拒绝",
    "Software caused connection abort" to "连接被中止",
    "unexpected end of stream" to "连接意外中断",
    "stream was reset" to "连接流被重置",
    "Broken pipe" to "连接已断开",
    "timeout" to "超时",
    "Unauthorized" to "未授权",
    "Forbidden" to "已被拒绝",
    "Not Found" to "未找到",
    "No route to host" to "无法连接到目标主机",
    "failed to connect" to "连接失败"
)

private val PREFIXED_MESSAGE_REGEX = Regex("^(.+?):\\s*(.+)$")
private val PROVIDER_HTTP_REGEX = Regex("^(.+?) HTTP (\\d+)(?::\\s*(.+))?$")
private val PROVIDER_STREAM_HTTP_REGEX = Regex("^(.+?) stream HTTP (\\d+)(?::\\s*(.+))?$")
private val PROVIDER_STREAM_FAILED_REGEX = Regex("^(.+?) stream failed(?::\\s*(.+))?$")
private val PROVIDER_MISSING_CHOICES_REGEX = Regex("^(.+?) response missing choices$")
private val PROVIDER_STREAM_CLOSED_REGEX = Regex("^(.+?) stream closed before \\[DONE\\]$")
private val UNEXPECTED_CONTENT_TYPE_REGEX = Regex("^Unexpected content-type: (.+?)(?:; body=(.+))?$")
private val GENERIC_HTTP_REGEX = Regex("^HTTP (\\d+):\\s*(.+)$")
private val UNSUPPORTED_CHANNEL_REGEX = Regex("^Unsupported channel: (.+)$")
private val CRON_LIMIT_REGEX = Regex("^Cron job limit reached \\((.+)\\)$")
private val EVERY_MS_MIN_REGEX = Regex("^everyMs must be >= (.+)$")
private val UNKNOWN_TIMEZONE_REGEX = Regex("^unknown timezone '(.+)'$")
private val UNKNOWN_SCHEDULE_KIND_REGEX = Regex("^unknown schedule kind '(.+)'$")
private val ALWAYS_ON_RESTART_SCHEDULED_REGEX = Regex("^Always-on restart scheduled \\((.+)\\):\\s*(.+)$")
private val UNABLE_TO_RESOLVE_HOST_REGEX = Regex("^Unable to resolve host \"([^\"]+)\":.*$")
private val FAILED_TO_CONNECT_REGEX = Regex("^Failed to connect to (.+)$")
