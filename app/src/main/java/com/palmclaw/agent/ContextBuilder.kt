package com.palmclaw.agent

import com.palmclaw.bus.MessageAttachmentJsonCodec
import com.palmclaw.providers.ChatMessage
import com.palmclaw.providers.ToolCall
import com.palmclaw.storage.entities.MessageEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ContextBuilder(
    private val workspaceContextProvider: ((String) -> WorkspaceContext?)? = null
) {
    private val json = Json { ignoreUnknownKeys = true }

    data class WorkspaceContext(
        val workspaceRoot: String,
        val docsDir: String,
        val scratchDir: String,
        val artifactsDir: String,
        val sharedWorkspaceRoot: String
    )

    fun build(
        sessionId: String,
        messages: List<MessageEntity>,
        maxHistoryMessages: Int,
        longTermMemory: String,
        activeSkillsContent: String,
        skillsSummary: String,
        systemPolicyTemplate: String? = null
    ): List<ChatMessage> {
        val filtered = messages
            .filterNot { shouldSkipInContext(it) }
            .takeLast(maxHistoryMessages)

        val history = mutableListOf<ChatMessage>()
        var pendingAssistant: ChatMessage? = null
        val pendingToolMessages = mutableListOf<ChatMessage>()
        val deferredMessages = mutableListOf<ChatMessage>()
        var pendingToolCallIds = mutableSetOf<String>()
        filtered.forEach { entity ->
            when (entity.role) {
                "assistant" -> {
                    val toolCalls = parseToolCalls(entity.toolCallJson)
                    val hasContent = entity.content.isNotBlank()
                    if (!hasContent && toolCalls.isEmpty()) return@forEach
                    val assistantMessage = ChatMessage(
                        role = "assistant",
                        content = entity.content,
                        toolCalls = toolCalls.ifEmpty { null }
                    )
                    if (toolCalls.isNotEmpty()) {
                        flushPendingToolChain(history, pendingAssistant, pendingToolMessages, deferredMessages, dropPending = true)
                        pendingAssistant = assistantMessage
                        pendingToolCallIds = toolCalls.map { it.id }.toMutableSet()
                    } else if (pendingAssistant != null && pendingToolCallIds.isNotEmpty()) {
                        deferredMessages += assistantMessage
                    } else {
                        history += assistantMessage
                        pendingToolCallIds.clear()
                    }
                }

                "tool" -> {
                    val toolCallId = parseToolResult(entity.toolResultJson)?.toolCallId
                    val isOrphan = toolCallId.isNullOrBlank() || !pendingToolCallIds.contains(toolCallId)
                    if (isOrphan) return@forEach
                    pendingToolMessages += ChatMessage(
                        role = "tool",
                        content = entity.content,
                        toolCallId = toolCallId
                    )
                    pendingToolCallIds.remove(toolCallId)
                    if (pendingToolCallIds.isEmpty()) {
                        flushPendingToolChain(history, pendingAssistant, pendingToolMessages, deferredMessages, dropPending = false)
                        pendingAssistant = null
                    }
                }

                "user", "internal_user" -> {
                    val userMessage = ChatMessage(
                        role = "user",
                        content = appendAttachmentSummary(entity.content, entity.attachmentsJson)
                    )
                    if (pendingAssistant != null && pendingToolCallIds.isNotEmpty()) {
                        deferredMessages += userMessage
                    } else {
                        history += userMessage
                    }
                    if (pendingAssistant == null) {
                        pendingToolCallIds.clear()
                    }
                }

                else -> {
                    val otherMessage = ChatMessage(
                        role = entity.role,
                        content = appendAttachmentSummary(entity.content, entity.attachmentsJson)
                    )
                    if (pendingAssistant != null && pendingToolCallIds.isNotEmpty()) {
                        deferredMessages += otherMessage
                    } else {
                        history += otherMessage
                        pendingToolCallIds.clear()
                    }
                }
            }
        }
        flushPendingToolChain(history, pendingAssistant, pendingToolMessages, deferredMessages, dropPending = pendingToolCallIds.isNotEmpty())
        return listOf(
            ChatMessage(
                role = "system",
                content = buildSystemPrompt(
                    sessionId = sessionId,
                    longTermMemory = longTermMemory,
                    activeSkillsContent = activeSkillsContent,
                    skillsSummary = skillsSummary,
                    systemPolicyTemplate = systemPolicyTemplate
                )
            )
        ) + history
    }

    private fun buildSystemPrompt(
        sessionId: String,
        longTermMemory: String,
        activeSkillsContent: String,
        skillsSummary: String,
        systemPolicyTemplate: String?
    ): String {
        val nowDate = Date()
        val tz = TimeZone.getDefault()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(nowDate)
        val isoNow = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(nowDate)
        val tzId = tz.id
        val tzDisplay = tz.getDisplayName(false, TimeZone.SHORT, Locale.US)
        val workspaceContext = workspaceContextProvider?.invoke(sessionId)
        val runtime = buildList {
            add("[Runtime Context]")
            add("This is the real current date and time at the moment this reply is being generated.")
            add("Treat it as authoritative for time-sensitive reasoning in this turn.")
            add("current_time_local=$now")
            add("current_time_iso8601=$isoNow")
            add("timezone_id=$tzId")
            add("timezone_display=$tzDisplay")
            add("session_id=$sessionId")
            addAll(workspaceRuntimeLines(workspaceContext))
        }.joinToString("\n")

        val fallbackPolicy = """
            You are PalmClaw Assistant inside an Android app.
            Follow these rules:
            1. Be concise and helpful.
            2. If tools are needed, output tool calls using provided function tools.
            3. Never invent tool results; wait for tool messages.
            4. If a tool fails, explain briefly and continue with best effort.
            5. Prefer plain text unless structured output is explicitly requested.
            6. Reply in the same language as the user's latest message.
        """.trimIndent()
        val policy = systemPolicyTemplate?.trim().takeUnless { it.isNullOrBlank() } ?: fallbackPolicy

        val memorySection = if (longTermMemory.isBlank()) "" else "\n\n## Long-term Memory\n$longTermMemory"
        val activeSkillsSection = if (activeSkillsContent.isBlank()) "" else "\n\n## Active Skills\n$activeSkillsContent"
        val summarySection = if (skillsSummary.isBlank()) "" else """

            ## Skills
            The following skills extend your capabilities.
            If the current task is related to a listed skill, read that skill's `SKILL.md` first, then execute the task according to the skill guidance.
            $skillsSummary
        """.trimIndent()
        return policy + "\n\n" + runtime + memorySection + activeSkillsSection + "\n\n" + summarySection
    }

    private fun workspaceRuntimeLines(workspaceContext: WorkspaceContext?): List<String> {
        if (workspaceContext == null) return emptyList()
        return listOf(
            "current_workspace_root=${workspaceContext.workspaceRoot}",
            "current_workspace_docs_dir=${workspaceContext.docsDir}",
            "current_workspace_scratch_dir=${workspaceContext.scratchDir}",
            "current_workspace_artifacts_dir=${workspaceContext.artifactsDir}",
            "shared_workspace_root=${workspaceContext.sharedWorkspaceRoot}",
            "Local relative file paths default to current_workspace_root.",
            "Use session:// to explicitly target the current session workspace.",
            "Use shared:// to explicitly target shared app storage."
        )
    }

    private fun appendAttachmentSummary(content: String, attachmentsJson: String?): String {
        val attachments = MessageAttachmentJsonCodec.decode(attachmentsJson)
        if (attachments.isEmpty()) return content
        val summary = attachments.joinToString("\n") { attachment ->
            buildString {
                append("- label=")
                append(attachment.label.ifBlank { attachment.reference.substringAfterLast('/') })
                append(", kind=")
                append(attachment.kind.name.lowercase(Locale.US))
                append(", mime_type=")
                append(attachment.mimeType ?: "*/*")
                append(", size_bytes=")
                append(attachment.sizeBytes?.toString() ?: "unknown")
                append(", local_workspace_path=")
                append(
                    attachment.localWorkspacePath?.takeIf { it.isNotBlank() }
                        ?: attachment.reference
                )
                attachment.metadata["source_channel"]?.takeIf { it.isNotBlank() }?.let {
                    append(", source_channel=")
                    append(it)
                }
            }
        }
        return buildString {
            val trimmed = content.trim()
            if (trimmed.isNotBlank()) {
                append(trimmed)
                append("\n\n")
            }
            append("[Attachments]\n")
            append(summary)
        }
    }

    private fun shouldSkipInContext(entity: MessageEntity): Boolean {
        if (entity.role != "assistant") return false
        val content = entity.content.trim()
        val hasToolCalls = !entity.toolCallJson.isNullOrBlank()
        if (hasToolCalls) return false
        if (content.isBlank() && !hasToolCalls) return true
        if (content.startsWith("[Error]")) return true
        if (content.startsWith("Error:")) return true
        if (content.startsWith("[Info]")) return true
        return false
    }

    private fun parseToolCalls(raw: String?): List<ToolCall> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<ToolCall>>(raw)
        }.getOrDefault(emptyList())
    }

    private fun parseToolResult(raw: String?): StoredToolResult? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            json.decodeFromString<StoredToolResult>(raw)
        }.getOrNull()
    }

    private fun flushPendingToolChain(
        history: MutableList<ChatMessage>,
        pendingAssistant: ChatMessage?,
        pendingToolMessages: MutableList<ChatMessage>,
        deferredMessages: MutableList<ChatMessage>,
        dropPending: Boolean
    ) {
        if (!dropPending && pendingAssistant != null) {
            history += pendingAssistant
            history += pendingToolMessages
        }
        history += deferredMessages
        pendingToolMessages.clear()
        deferredMessages.clear()
    }

    @Serializable
    private data class StoredToolResult(
        val toolCallId: String,
        val content: String,
        val isError: Boolean
    )
}

