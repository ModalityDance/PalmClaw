package com.palmclaw.agent

import android.util.Log
import com.palmclaw.config.AppLimits
import com.palmclaw.memory.MemoryStore
import com.palmclaw.providers.ChatMessage
import com.palmclaw.providers.ToolSpec
import com.palmclaw.storage.MessageRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class MemoryConsolidator(
    private val repository: MessageRepository,
    private val memoryStore: MemoryStore,
    private val providerFactory: () -> com.palmclaw.providers.LlmProvider,
    private val toolCallParser: ToolCallParser
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun hasPendingConsolidation(sessionId: String, memoryWindow: Int): Boolean {
        val all = repository.getMessages(sessionId)
        val keepCount = normalizeMemoryWindow(memoryWindow) / 2
        if (all.size <= keepCount) return false
        val safeLast = memoryStore.getLastConsolidatedIndex(sessionId).coerceIn(0, all.size)
        val endExclusive = all.size - keepCount
        return endExclusive > safeLast
    }

    suspend fun consolidateIfNeeded(sessionId: String, memoryWindow: Int): ConsolidationResult {
        val all = repository.getMessages(sessionId)
        val keepCount = normalizeMemoryWindow(memoryWindow) / 2
        if (all.size <= keepCount) return ConsolidationResult.Noop

        val safeLast = memoryStore.getLastConsolidatedIndex(sessionId).coerceIn(0, all.size)
        val endExclusive = all.size - keepCount
        if (endExclusive <= safeLast) return ConsolidationResult.Noop

        val oldMessages = all.subList(safeLast, endExclusive)
        if (oldMessages.isEmpty()) return ConsolidationResult.Noop

        val lines = oldMessages.mapNotNull { m ->
            val content = m.content.trim()
            if (content.isBlank()) return@mapNotNull null
            val roleLabel = if (m.role == "internal_user") "USER" else m.role.uppercase()
            "[${m.createdAt}] $roleLabel: $content"
        }
        if (lines.isEmpty()) {
            memoryStore.setLastConsolidatedIndex(sessionId, endExclusive)
            return ConsolidationResult.Noop
        }

        val currentMemory = memoryStore.readLongTerm()
        val prompt = """
            Process this conversation and call the save_memory tool with your consolidation.

            ## Current Long-term Memory
            ${if (currentMemory.isBlank()) "(empty)" else currentMemory}

            ## Conversation to Process
            ${lines.joinToString("\n")}
        """.trimIndent()

        return try {
            val response = providerFactory().chat(
                messages = listOf(
                    ChatMessage(
                        role = "system",
                        content = "You are a memory consolidation agent. Call the save_memory tool."
                    ),
                    ChatMessage(role = "user", content = prompt)
                ),
                toolsSpec = listOf(saveMemoryToolSpec())
            )

            val calls = toolCallParser.parse(response)
            val saveCall = calls.firstOrNull { it.name == "save_memory" }
                ?: run {
                    Log.w(TAG, "Memory consolidation skipped: no save_memory tool call")
                    return ConsolidationResult.Failed("no save_memory tool call")
                }

            val args = json.parseToJsonElement(saveCall.argumentsJson) as? JsonObject
                ?: run {
                    Log.w(TAG, "Memory consolidation skipped: invalid tool args")
                    return ConsolidationResult.Failed("invalid tool args")
                }

            val historyEntry = args["history_entry"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val memoryUpdate = args["memory_update"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val updated = historyEntry.isNotBlank() || (memoryUpdate.isNotBlank() && memoryUpdate != currentMemory)

            if (historyEntry.isNotBlank()) {
                memoryStore.appendHistory(sessionId, historyEntry)
            }
            if (memoryUpdate.isNotBlank() && memoryUpdate != currentMemory) {
                memoryStore.writeLongTerm(memoryUpdate)
            }
            memoryStore.setLastConsolidatedIndex(sessionId, endExclusive)
            Log.d(TAG, "Memory consolidation done, session=$sessionId, upTo=$endExclusive")
            if (updated) ConsolidationResult.Updated else ConsolidationResult.Noop
        } catch (t: Throwable) {
            Log.e(TAG, "Memory consolidation failed", t)
            ConsolidationResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun saveMemoryToolSpec(): ToolSpec {
        return ToolSpec(
            name = "save_memory",
            description = "Save memory consolidation to persistent store.",
            parameters = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("history_entry") {
                        put("type", "string")
                        put("description", "2-5 sentence log entry with useful detail.")
                    }
                    putJsonObject("memory_update") {
                        put("type", "string")
                        put("description", "Full updated long-term memory markdown.")
                    }
                }
                put("required", Json.parseToJsonElement("[\"history_entry\",\"memory_update\"]"))
            }
        )
    }

    private fun normalizeMemoryWindow(memoryWindow: Int): Int {
        return memoryWindow.coerceIn(
            AppLimits.MIN_MEMORY_CONSOLIDATION_WINDOW,
            AppLimits.MAX_MEMORY_CONSOLIDATION_WINDOW
        )
    }

    companion object {
        private const val TAG = "MemoryConsolidator"
    }

    sealed class ConsolidationResult {
        data object Noop : ConsolidationResult()
        data object Updated : ConsolidationResult()
        data class Failed(val reason: String) : ConsolidationResult()
    }
}

