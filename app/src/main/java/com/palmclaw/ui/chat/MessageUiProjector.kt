package com.palmclaw.ui

import com.palmclaw.bus.MessageAttachment
import com.palmclaw.bus.MessageAttachmentJsonCodec
import com.palmclaw.bus.MessageAttachmentKind
import com.palmclaw.bus.deriveMessageAttachmentLabel
import com.palmclaw.bus.inferMessageAttachmentKind
import com.palmclaw.bus.inferMessageAttachmentMimeType
import com.palmclaw.bus.normalizeMessageAttachmentReference
import com.palmclaw.providers.ToolCall
import com.palmclaw.storage.entities.MessageEntity
import com.palmclaw.tools.ToolResultMetadataKeys
import java.util.LinkedHashSet
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Projects persisted transcript records into UI-facing chat messages.
 *
 * Tool-call and tool-result messages are combined here so the chat screen can render a stable,
 * presentation-oriented transcript without duplicating parsing logic in [ChatViewModel].
 */
internal class MessageUiProjector(
    private val uiJson: Json,
    private val toolArgsPreviewMaxCharsProvider: () -> Int,
    private val maxAttachmentsPerMessage: Int = DEFAULT_MAX_ATTACHMENTS_PER_MESSAGE
) {
    fun shouldDisplayInChat(message: MessageEntity): Boolean {
        val text = message.content.trim()
        val hasAttachments = resolveAttachments(message).isNotEmpty()
        return when (message.role) {
            "user" -> text.isNotBlank() || hasAttachments
            "assistant" -> {
                if (text.startsWith("[debug tool call]", ignoreCase = true)) {
                    return false
                }
                if (text == "[tool call]" && message.toolCallJson.isNullOrBlank()) {
                    return false
                }
                text.isNotBlank() || !message.toolCallJson.isNullOrBlank() || hasAttachments
            }

            "tool" -> {
                if (isHiddenToolResult(message.toolResultJson)) {
                    return false
                }
                text.isNotBlank() || !message.toolResultJson.isNullOrBlank() || hasAttachments
            }
            else -> false
        }
    }

    fun project(messages: List<MessageEntity>): List<UiMessage> {
        val mapped = mutableListOf<UiMessage>()
        val consumedHiddenToolMessageIds = mutableSetOf<Long>()
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            if (message.role == "assistant" && !message.toolCallJson.isNullOrBlank()) {
                val toolCalls = parseToolCalls(message.toolCallJson.orEmpty())
                val assistantNote = message.content.trim()
                    .takeIf { it.isNotBlank() && !it.equals("[tool call]", ignoreCase = true) }
                var scan = index + 1
                val contiguousToolMessages = mutableListOf<MessageEntity>()
                while (scan < messages.size && messages[scan].role == "tool") {
                    contiguousToolMessages += messages[scan]
                    scan += 1
                }

                if (toolCalls.isNotEmpty()) {
                    val pending = contiguousToolMessages.map { entity ->
                        ToolResultEnvelope(
                            entity = entity,
                            parsed = parseToolResult(entity.toolResultJson)
                        )
                    }.toMutableList()

                    toolCalls.forEachIndexed { callIndex, call ->
                        val exactMatchIndex = pending.indexOfFirst {
                            it.parsed?.toolCallId?.trim().orEmpty() == call.id.trim()
                        }
                        val matched = when {
                            exactMatchIndex >= 0 -> pending.removeAt(exactMatchIndex)
                            else -> findDeferredHiddenToolResult(
                                messages = messages,
                                startIndex = scan,
                                callId = call.id,
                                consumedHiddenToolMessageIds = consumedHiddenToolMessageIds
                            ) ?: when {
                                pending.isNotEmpty() -> pending.removeAt(0)
                                else -> null
                            }
                        }
                        mapped += buildCombinedToolUiMessage(
                            baseMessage = message,
                            callIndex = callIndex,
                            call = call,
                            matchedResult = matched,
                            assistantNote = assistantNote
                        )
                    }

                    pending.forEachIndexed { orphanIndex, orphan ->
                        if (!isHiddenToolResult(orphan.entity.toolResultJson)) {
                            mapped += orphan.entity.toUiModel(
                                forcedId = syntheticToolMessageId(message.id, 900 + orphanIndex)
                            )
                        }
                    }
                } else {
                    mapped += message.toUiModel()
                    contiguousToolMessages.forEachIndexed { orphanIndex, orphan ->
                        if (!isHiddenToolResult(orphan.toolResultJson)) {
                            mapped += orphan.toUiModel(
                                forcedId = syntheticToolMessageId(message.id, 950 + orphanIndex)
                            )
                        }
                    }
                }
                index = scan
                continue
            }

            if (message.role == "tool") {
                if (message.id in consumedHiddenToolMessageIds || isHiddenToolResult(message.toolResultJson)) {
                    index += 1
                    continue
                }
                mapped += message.toUiModel()
                index += 1
                continue
            }

            mapped += message.toUiModel()
            index += 1
        }
        return mapped
    }

    private fun MessageEntity.toUiModel(forcedId: Long? = null): UiMessage {
        if (role == "assistant" && !toolCallJson.isNullOrBlank()) {
            val details = formatToolCallContent(
                toolCallJson = toolCallJson.orEmpty(),
                assistantContent = content
            )
            return UiMessage(
                id = forcedId ?: id,
                role = "tool",
                content = formatToolCallSummary(
                    toolCallJson = toolCallJson.orEmpty()
                ),
                createdAt = createdAt,
                isCollapsible = true,
                expandedContent = details,
                attachments = emptyList()
            )
        }
        if (role == "tool") {
            val attachments = resolveAttachments(this)
            val details = formatToolResultContent(
                toolResultJson = toolResultJson,
                fallbackContent = content
            )
            return UiMessage(
                id = forcedId ?: id,
                role = "tool",
                content = formatToolResultSummary(
                    toolResultJson = toolResultJson,
                    fallbackContent = content
                ),
                createdAt = createdAt,
                isCollapsible = true,
                expandedContent = details,
                attachments = attachments
            )
        }
        val attachments = resolveAttachments(this)
        return UiMessage(
            id = forcedId ?: id,
            role = role,
            content = if (content.isBlank() && attachments.isNotEmpty()) "" else content.ifBlank { "[empty]" },
            createdAt = createdAt,
            attachments = attachments
        )
    }

    private fun buildCombinedToolUiMessage(
        baseMessage: MessageEntity,
        callIndex: Int,
        call: ToolCall,
        matchedResult: ToolResultEnvelope?,
        assistantNote: String?
    ): UiMessage {
        val resultEntity = matchedResult?.entity
        val parsedResult = matchedResult?.parsed
        val status = when {
            resultEntity == null -> "pending"
            parsedResult?.isError == true -> "error"
            else -> "ok"
        }
        val details = formatSingleToolTraceContent(
            call = call,
            matchedEntity = resultEntity,
            assistantNote = assistantNote
        )
        return UiMessage(
            id = syntheticToolMessageId(baseMessage.id, callIndex),
            role = "tool",
            content = "${call.name} [$status]",
            createdAt = baseMessage.createdAt,
            isCollapsible = true,
            expandedContent = details,
            attachments = if (resultEntity != null) {
                resolveAttachments(resultEntity)
            } else {
                emptyList()
            }
        )
    }

    private fun formatSingleToolTraceContent(
        call: ToolCall,
        matchedEntity: MessageEntity?,
        assistantNote: String?
    ): String {
        val previewMaxChars = toolArgsPreviewMaxCharsProvider()
        val argsPretty = prettyJsonOrRaw(call.argumentsJson)
        return buildString {
            appendLine("Tool Call")
            appendLine("name=${call.name}")
            appendLine("call_id=${call.id}")
            appendLine("arguments:")
            appendLine("```json")
            appendLine(argsPretty.take(previewMaxChars))
            if (argsPretty.length > previewMaxChars) {
                appendLine("...(truncated)")
            }
            appendLine("```")
            appendLine()
            if (matchedEntity != null) {
                append(formatToolResultContent(matchedEntity.toolResultJson, matchedEntity.content))
            } else {
                appendLine("Tool Result")
                appendLine("status=pending")
                appendLine()
                append("(waiting for tool result)")
            }
            if (!assistantNote.isNullOrBlank()) {
                appendLine()
                appendLine()
                appendLine("assistant_note:")
                append(assistantNote)
            }
        }.trimEnd()
    }

    private fun syntheticToolMessageId(baseId: Long, offset: Int): Long {
        return baseId * 1000L + offset.toLong() + 1L
    }

    private fun findDeferredHiddenToolResult(
        messages: List<MessageEntity>,
        startIndex: Int,
        callId: String,
        consumedHiddenToolMessageIds: MutableSet<Long>
    ): ToolResultEnvelope? {
        for (index in startIndex until messages.size) {
            val candidate = messages[index]
            if (candidate.role == "assistant" && !candidate.toolCallJson.isNullOrBlank()) {
                break
            }
            if (candidate.role != "tool" || candidate.id in consumedHiddenToolMessageIds) {
                continue
            }
            val parsed = parseToolResult(candidate.toolResultJson)
            if (!isHiddenToolResult(candidate.toolResultJson)) {
                continue
            }
            if (parsed?.toolCallId?.trim().orEmpty() != callId.trim()) {
                continue
            }
            consumedHiddenToolMessageIds += candidate.id
            return ToolResultEnvelope(
                entity = candidate,
                parsed = parsed
            )
        }
        return null
    }

    private fun formatToolCallSummary(toolCallJson: String): String {
        val calls = parseToolCalls(toolCallJson)
        if (calls.isEmpty()) {
            return "call"
        }
        val names = calls.map { it.name.trim() }.filter { it.isNotBlank() }
        if (names.isEmpty()) return "calls (${calls.size})"
        return if (names.size == 1) {
            names.first()
        } else {
            val preview = names.take(3).joinToString(", ")
            val remain = names.size - 3
            if (remain > 0) {
                "calls (${names.size}): $preview, +$remain more"
            } else {
                "calls (${names.size}): $preview"
            }
        }
    }

    private fun formatToolResultSummary(toolResultJson: String?, fallbackContent: String): String {
        val parsed = parseToolResult(toolResultJson)
        val status = if (parsed?.isError == true) "error" else "ok"
        val toolName = (parsed?.metadata?.get("mcp_tool") as? JsonPrimitive)
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
        val rawLead = parsed?.content?.lineSequence()?.firstOrNull()?.trim()
            .orEmpty()
            .ifBlank { fallbackContent.lineSequence().firstOrNull()?.trim().orEmpty() }
            .ifBlank { "(no output)" }
        val lead = rawLead.take(90)
        return buildString {
            append(toolName ?: "result")
            append(" [")
            append(status)
            append("] ")
            append(lead)
            if (rawLead.length > 90) append("...")
        }
    }

    private fun formatToolCallContent(toolCallJson: String, assistantContent: String): String {
        val previewMaxChars = toolArgsPreviewMaxCharsProvider()
        val calls = parseToolCalls(toolCallJson)
        if (calls.isEmpty()) {
            return buildString {
                appendLine("Tool Call")
                appendLine()
                val fallback = assistantContent.trim()
                if (fallback.isNotBlank() && !fallback.equals("[tool call]", ignoreCase = true)) {
                    append(fallback)
                } else {
                    append(toolCallJson)
                }
            }.trimEnd()
        }

        return buildString {
            appendLine(if (calls.size == 1) "Tool Call" else "Tool Calls (${calls.size})")
            calls.forEachIndexed { index, call ->
                appendLine()
                appendLine("${index + 1}. name=${call.name}")
                appendLine("call_id=${call.id}")
                appendLine("arguments:")
                appendLine("```json")
                appendLine(prettyJsonOrRaw(call.argumentsJson).take(previewMaxChars))
                if (call.argumentsJson.length > previewMaxChars) {
                    appendLine("...(truncated)")
                }
                appendLine("```")
            }
            val note = assistantContent.trim()
            if (note.isNotBlank() && !note.equals("[tool call]", ignoreCase = true)) {
                appendLine()
                appendLine("assistant_note:")
                append(note)
            }
        }.trimEnd()
    }

    private fun formatToolResultContent(toolResultJson: String?, fallbackContent: String): String {
        val parsed = parseToolResult(toolResultJson)
        val body = parsed?.content?.trim().orEmpty()
            .ifBlank { fallbackContent.trim() }
            .ifBlank { "(empty)" }
        return buildString {
            appendLine("Tool Result")
            parsed?.toolCallId?.takeIf { it.isNotBlank() }?.let { appendLine("call_id=$it") }
            parsed?.let {
                appendLine("status=${if (it.isError) "error" else "ok"}")
                val errorCode = (it.metadata?.get("error") as? JsonPrimitive)?.contentOrNull
                if (!errorCode.isNullOrBlank()) {
                    appendLine("error=$errorCode")
                }
                val timeoutMs = (it.metadata?.get("timeout_ms") as? JsonPrimitive)?.contentOrNull
                if (!timeoutMs.isNullOrBlank()) {
                    appendLine("timeout_ms=$timeoutMs")
                }
            }
            appendLine()
            append(body)
        }.trimEnd()
    }

    private fun resolveAttachments(message: MessageEntity): List<UiAttachment> {
        val persisted = MessageAttachmentJsonCodec.decode(message.attachmentsJson)
            .map(::toUiAttachment)
        if (persisted.isNotEmpty()) {
            return persisted.take(maxAttachmentsPerMessage)
        }
        if (message.role == "tool") {
            return extractToolResultAttachments(
                toolResultJson = message.toolResultJson,
                fallbackContent = message.content
            ).take(maxAttachmentsPerMessage)
        }
        return emptyList()
    }

    private fun extractToolResultAttachments(
        toolResultJson: String?,
        fallbackContent: String
    ): List<UiAttachment> {
        val parsed = parseToolResult(toolResultJson)
        if (parsed?.isError == true) return emptyList()

        val action = metadataString(parsed?.metadata, "action")?.lowercase(Locale.US).orEmpty()
        val mode = metadataString(parsed?.metadata, "mode")?.lowercase(Locale.US).orEmpty()
        if (action == "audio_record" && mode == "start") {
            return emptyList()
        }
        val kindHint = metadataString(parsed?.metadata, "kind")?.lowercase(Locale.US).orEmpty()
        val candidates = LinkedHashSet<String>()
        val keys = listOf("output_uri", "uri", "url", "path")
        keys.forEach { key ->
            metadataString(parsed?.metadata, key)?.let { candidates += it }
        }

        val contentPool = buildString {
            append(parsed?.content.orEmpty())
            if (isNotBlank()) append('\n')
            append(fallbackContent)
        }
        extractAttachmentRefsFromText(contentPool).forEach { candidates += it }

        return candidates
            .mapNotNull { ref ->
                val normalized = normalizeMessageAttachmentReference(ref) ?: return@mapNotNull null
                val kind = guessAttachmentKind(normalized, action, kindHint)
                val attachment = MessageAttachment(
                    kind = kind,
                    reference = normalized,
                    label = deriveMessageAttachmentLabel(
                        reference = normalized,
                        kind = kind
                    ),
                    mimeType = inferMessageAttachmentMimeType(
                        reference = normalized,
                        kind = kind
                    )
                )
                toUiAttachment(attachment)
            }
    }

    private fun metadataString(metadata: JsonObject?, key: String): String? {
        return (metadata?.get(key) as? JsonPrimitive)
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun isHiddenToolResult(toolResultJson: String?): Boolean {
        return metadataString(
            metadata = parseToolResult(toolResultJson)?.metadata,
            key = ToolResultMetadataKeys.HIDE_UI_RESULT
        )?.equals("true", ignoreCase = true) == true
    }

    private fun extractAttachmentRefsFromText(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val refs = LinkedHashSet<String>()
        val uriPattern = Regex("""(?i)\b(?:content|file|https?)://[^\s)]+""")
        uriPattern.findAll(text).forEach { refs += it.value }
        val kvPattern = Regex("""(?i)\b(?:output_uri|uri|path)=([^\s]+)""")
        kvPattern.findAll(text).forEach { match ->
            match.groupValues.getOrNull(1)?.let { refs += it }
        }
        return refs.toList()
    }

    private fun guessAttachmentKind(
        reference: String,
        action: String,
        kindHint: String
    ): MessageAttachmentKind {
        return when {
            action == "capture_photo" -> MessageAttachmentKind.Image
            action == "record_video" -> MessageAttachmentKind.Video
            action == "audio_record" || action == "audio_playback" -> MessageAttachmentKind.Audio
            action == "list_recent" && kindHint == "images" -> MessageAttachmentKind.Image
            action == "list_recent" && kindHint == "videos" -> MessageAttachmentKind.Video
            action == "list_recent" && kindHint == "audio" -> MessageAttachmentKind.Audio
            else -> inferMessageAttachmentKind(reference)
        }
    }

    private fun toUiAttachment(attachment: MessageAttachment): UiAttachment {
        return UiAttachment(
            reference = attachment.reference,
            kind = when (attachment.kind) {
                MessageAttachmentKind.Image -> UiAttachmentKind.Image
                MessageAttachmentKind.Video -> UiAttachmentKind.Video
                MessageAttachmentKind.Audio -> UiAttachmentKind.Audio
                MessageAttachmentKind.File -> UiAttachmentKind.File
            },
            label = attachment.label,
            mimeType = attachment.mimeType,
            sizeBytes = attachment.sizeBytes,
            source = attachment.source,
            transferState = attachment.transferState,
            failureMessage = attachment.failureMessage,
            isRemoteBacked = attachment.isRemoteBacked,
            localWorkspacePath = attachment.localWorkspacePath
        )
    }

    private fun parseToolCalls(raw: String): List<ToolCall> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            uiJson.decodeFromString<List<ToolCall>>(raw)
        }.getOrDefault(emptyList())
    }

    private fun parseToolResult(raw: String?): UiStoredToolResult? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            uiJson.decodeFromString<UiStoredToolResult>(raw)
        }.getOrNull()
    }

    private fun prettyJsonOrRaw(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return "{}"
        return runCatching {
            val parsed = uiJson.parseToJsonElement(trimmed)
            uiJson.encodeToString(parsed)
        }.getOrDefault(trimmed)
    }

    @Serializable
    private data class UiStoredToolResult(
        val toolCallId: String,
        val content: String,
        val isError: Boolean,
        val metadata: JsonObject? = null
    )

    private data class ToolResultEnvelope(
        val entity: MessageEntity,
        val parsed: UiStoredToolResult?
    )

    companion object {
        private const val DEFAULT_MAX_ATTACHMENTS_PER_MESSAGE = 4
    }
}
