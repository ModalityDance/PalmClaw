package com.palmclaw.agent

import android.util.Log
import com.palmclaw.bus.OutboundMessage
import com.palmclaw.config.AppSession
import com.palmclaw.storage.MessageRepository
import com.palmclaw.storage.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class SubagentManager(
    private val agentLoop: AgentLoop,
    private val messageRepository: MessageRepository,
    private val sessionRepository: SessionRepository,
    private val publishOutbound: suspend (OutboundMessage) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runningJobs = ConcurrentHashMap<String, Job>()

    suspend fun spawn(
        task: String,
        label: String? = null,
        originChannel: String = "local",
        originChatId: String = "app",
        sessionKey: String = AppSession.SHARED_SESSION_ID,
        originAdapterKey: String? = null
    ): String {
        val normalizedTask = task.trim()
        if (normalizedTask.isBlank()) {
            return "Error: task is required"
        }
        if (runningJobs.size >= MAX_CONCURRENT_TASKS) {
            return "Error: too many running subagents (${runningJobs.size}/$MAX_CONCURRENT_TASKS)"
        }

        val id = UUID.randomUUID().toString().replace("-", "").take(8)
        val displayLabel = label?.trim()?.ifBlank { null }
            ?: normalizedTask.take(MAX_LABEL_CHARS).ifBlank { "subtask" }
        val summary = "Subagent [$displayLabel] started (id: $id). I'll report back when it completes."

        val job = scope.launch {
            runSubagent(
                id = id,
                label = displayLabel,
                task = normalizedTask,
                originChannel = originChannel,
                originChatId = originChatId,
                sessionKey = sessionKey,
                originAdapterKey = originAdapterKey
            )
        }
        runningJobs[id] = job
        job.invokeOnCompletion { runningJobs.remove(id) }
        return summary
    }

    fun close() {
        scope.cancel()
        runningJobs.clear()
    }

    private suspend fun runSubagent(
        id: String,
        label: String,
        task: String,
        originChannel: String,
        originChatId: String,
        sessionKey: String,
        originAdapterKey: String?
    ) {
        val subSessionId = "internal:subagent:$id"
        val title = "Subagent $label"
        val announcePrefix = "[Subagent '$label' id=$id]"
        try {
            sessionRepository.ensureSessionExists(subSessionId, title)
            agentLoop.run(
                sessionId = subSessionId,
                newUserText = task,
                blockedTools = SUBAGENT_BLOCKED_TOOLS
            )
            val result = messageRepository.getLatestAssistantMessage(subSessionId)?.content
                ?.trim()
                ?.ifBlank { "(no output)" }
                ?: "(no output)"
            val payload = buildString {
                appendLine("$announcePrefix completed")
                appendLine()
                appendLine("Task:")
                appendLine(task)
                appendLine()
                appendLine("Result:")
                append(limitText(result, MAX_RESULT_CHARS))
            }
            sessionRepository.ensureSessionExists(sessionKey, AppSession.SHARED_SESSION_TITLE)
            messageRepository.appendAssistantMessage(
                sessionId = sessionKey,
                content = payload
            )
            notifyOrigin(originChannel, originChatId, originAdapterKey, payload)
            Log.i(TAG, "Subagent completed id=$id")
        } catch (_: CancellationException) {
            val payload = "$announcePrefix cancelled"
            messageRepository.appendAssistantMessage(
                sessionId = sessionKey,
                content = payload
            )
            notifyOrigin(originChannel, originChatId, originAdapterKey, payload)
            Log.i(TAG, "Subagent cancelled id=$id")
        } catch (t: Throwable) {
            val payload = "$announcePrefix failed: ${t.message ?: t.javaClass.simpleName}"
            messageRepository.appendAssistantMessage(
                sessionId = sessionKey,
                content = payload
            )
            notifyOrigin(originChannel, originChatId, originAdapterKey, payload)
            Log.e(TAG, "Subagent failed id=$id", t)
        } finally {
            runCatching {
                sessionRepository.deleteSession(subSessionId)
            }.onFailure { t ->
                Log.w(TAG, "Cleanup subagent session failed id=$id: ${t.message}")
            }
        }
    }

    private suspend fun notifyOrigin(channel: String, chatId: String, adapterKey: String?, content: String) {
        if (channel.equals("local", ignoreCase = true)) return
        if (channel.isBlank() || chatId.isBlank()) return
        runCatching {
            publishOutbound(
                OutboundMessage(
                    channel = channel,
                    chatId = chatId,
                    content = limitText(content, MAX_NOTIFY_CHARS),
                    metadata = buildMap {
                        adapterKey?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { put("adapter_key", it) }
                    }
                )
            )
        }.onFailure { t ->
            Log.w(TAG, "Subagent notify failed channel=$channel chatId=$chatId: ${t.message}")
        }
    }

    private fun limitText(input: String, maxChars: Int): String {
        if (input.length <= maxChars) return input
        return input.take(maxChars) + "\n...[truncated]"
    }

    companion object {
        private const val TAG = "SubagentManager"
        private const val MAX_CONCURRENT_TASKS = 2
        private const val MAX_LABEL_CHARS = 40
        private const val MAX_RESULT_CHARS = 8_000
        private const val MAX_NOTIFY_CHARS = 3_500
        private val SUBAGENT_BLOCKED_TOOLS = setOf(
            "sessions_spawn",
            "sessions_send",
            "message",
            "runtime_set",
            "session_set",
            "heartbeat_set",
            "heartbeat_trigger"
        )
    }
}
