package com.palmclaw.tools

import com.palmclaw.agent.SubagentManager
import com.palmclaw.config.AppSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SpawnTool(
    private val manager: SubagentManager
) : Tool {
    private val contextMutex = Mutex()
    private val turnStates = mutableMapOf<Job, TurnState>()

    @Volatile
    private var originChannel: String = "local"

    @Volatile
    private var originChatId: String = "app"

    @Volatile
    private var sessionKey: String = AppSession.SHARED_SESSION_ID

    @Volatile
    private var originAdapterKey: String? = null

    override val name: String = "sessions_spawn"

    override val description: String =
        "Spawn a background subagent for time-consuming tasks. It will report back when done."

    override val jsonSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("required", Json.parseToJsonElement("[\"task\"]"))
        put(
            "properties",
            Json.parseToJsonElement(
                """
                {
                  "task":{"type":"string","description":"The task for the subagent"},
                  "label":{"type":"string","description":"Optional short label"},
                  "channel":{"type":"string","description":"Optional override target channel"},
                  "chat_id":{"type":"string","description":"Optional override target chat id"}
                }
                """.trimIndent()
            )
        )
    }

    fun setContext(channel: String, chatId: String, sessionKey: String, adapterKey: String? = null) {
        this.originChannel = channel
        this.originChatId = chatId
        this.sessionKey = sessionKey
        this.originAdapterKey = adapterKey?.trim()?.ifBlank { null }
    }

    suspend fun startTurn() {
        val job = requireCurrentJob()
        contextMutex.withLock {
            turnStates[job] = TurnState(
                channel = originChannel,
                chatId = originChatId,
                sessionKey = sessionKey,
                adapterKey = originAdapterKey
            )
        }
    }

    suspend fun finishTurn() {
        val job = requireCurrentJob()
        contextMutex.withLock {
            turnStates.remove(job)
        }
    }

    override suspend fun run(argumentsJson: String): ToolResult {
        val args = runCatching { Json.decodeFromString<Args>(argumentsJson) }
            .getOrElse {
                return ToolResult(
                    toolCallId = "",
                    content = "sessions_spawn failed: invalid arguments JSON (${it.message})",
                    isError = true
                )
            }
        val task = args.task.trim()
        if (task.isBlank()) {
            return ToolResult(
                toolCallId = "",
                content = "sessions_spawn failed: task is required",
                isError = true
            )
        }
        val currentJob = currentCoroutineContext()[Job]
        val turnState = contextMutex.withLock {
            if (currentJob != null) turnStates[currentJob] else null
        }
        val fallbackChannel = turnState?.channel ?: originChannel
        val fallbackChatId = turnState?.chatId ?: originChatId
        val fallbackSessionKey = turnState?.sessionKey ?: sessionKey
        val fallbackAdapterKey = turnState?.adapterKey ?: originAdapterKey
        val channel = args.channel?.trim().orEmpty().ifBlank { fallbackChannel }
        val chatId = args.chatId?.trim().orEmpty().ifBlank { fallbackChatId }
        val adapterKey = fallbackAdapterKey
            ?.takeIf { channel == fallbackChannel && chatId == fallbackChatId }
        val summary = manager.spawn(
            task = task,
            label = args.label?.trim()?.ifBlank { null },
            originChannel = channel,
            originChatId = chatId,
            sessionKey = fallbackSessionKey,
            originAdapterKey = adapterKey
        )
        val isError = summary.startsWith("Error:", ignoreCase = true)
        return ToolResult(
            toolCallId = "",
            content = summary,
            isError = isError
        )
    }

    @Serializable
    private data class Args(
        val task: String,
        val label: String? = null,
        val channel: String? = null,
        @SerialName("chat_id")
        val chatId: String? = null
    )

    private suspend fun requireCurrentJob(): Job {
        return currentCoroutineContext()[Job]
            ?: throw IllegalStateException("sessions_spawn requires an active coroutine job")
    }

    private data class TurnState(
        val channel: String,
        val chatId: String,
        val sessionKey: String,
        val adapterKey: String?
    )
}

