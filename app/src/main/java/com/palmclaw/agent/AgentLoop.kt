package com.palmclaw.agent

import android.util.Log
import com.palmclaw.config.AppLimits
import com.palmclaw.memory.MemoryStore
import com.palmclaw.providers.LlmProvider
import com.palmclaw.providers.LlmUsage
import com.palmclaw.providers.ToolCall
import com.palmclaw.providers.ToolSpec
import com.palmclaw.skills.SkillsLoader
import com.palmclaw.storage.MessageRepository
import com.palmclaw.templates.TemplateStore
import com.palmclaw.tools.ToolRegistry
import com.palmclaw.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.InterruptedIOException

class AgentLoop(
    private val repository: MessageRepository,
    private val contextBuilder: ContextBuilder,
    private val toolCallParser: ToolCallParser,
    private val toolRegistry: ToolRegistry,
    private val llmProviderFactory: () -> LlmProvider,
    private val memoryStore: MemoryStore,
    private val memoryConsolidator: MemoryConsolidator,
    private val skillsLoader: SkillsLoader,
    private val templateStore: TemplateStore?,
    private val processLogger: ((String) -> Unit)? = null,
    private val usageReporter: ((LlmUsage) -> Unit)? = null,
    private val maxRoundsProvider: () -> Int = { AppLimits.DEFAULT_MAX_TOOL_ROUNDS },
    private val toolResultMaxCharsProvider: () -> Int = { AppLimits.DEFAULT_TOOL_RESULT_MAX_CHARS },
    private val memoryWindowProvider: () -> Int = { AppLimits.DEFAULT_MEMORY_CONSOLIDATION_WINDOW },
    private val maxContextMessagesProvider: () -> Int = { AppLimits.DEFAULT_CONTEXT_MESSAGES }
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val consolidationLocks = mutableMapOf<String, Mutex>()
    private val consolidationJobs = mutableMapOf<String, Job>()
    private val consolidationGuard = Any()

    suspend fun run(
        sessionId: String,
        newUserText: String,
        blockedTools: Set<String> = emptySet(),
        onUserMessageAppended: (suspend (Long) -> Unit)? = null,
        inputRole: String = "user",
        appendInputMessage: Boolean = true
    ) = withContext(sessionContext(sessionId)) {
        var cancelled = false
        try {
            val normalizedRole = inputRole.trim().ifBlank { "user" }
            if (appendInputMessage) {
                val appendedUserMessageId = repository.appendMessage(
                    sessionId = sessionId,
                    role = normalizedRole,
                    content = newUserText
                )
                onUserMessageAppended?.invoke(appendedUserMessageId)
                logInfo("input message appended; session=$sessionId, role=$normalizedRole, chars=${newUserText.length}")
            } else {
                logInfo("input message already appended; session=$sessionId, role=$normalizedRole, chars=${newUserText.length}")
            }

            val recentUserContext = repository.getMessages(sessionId)
                .asReversed()
                .filter { it.role == "user" || it.role == "internal_user" }
                .take(4)
                .asReversed()
                .joinToString("\n") { it.content }
                .ifBlank { newUserText }
            val alwaysSkillNames = skillsLoader.getAlwaysSkills()
            val selectedSkillNames = skillsLoader.selectSkillsForInput(recentUserContext)
            val activeSkillNames = (alwaysSkillNames + selectedSkillNames).distinct()
            val activeSkillsContent = skillsLoader.loadSkillsForContext(activeSkillNames)
            val skillsSummary = skillsLoader.buildSkillsSummary()
            val systemPolicyTemplate = buildBootstrapPolicyTemplate()
            val maxRounds = maxRoundsProvider()
                .coerceIn(AppLimits.MIN_MAX_TOOL_ROUNDS, AppLimits.MAX_MAX_TOOL_ROUNDS)
            val toolResultMaxChars = toolResultMaxCharsProvider()
                .coerceIn(AppLimits.MIN_TOOL_RESULT_MAX_CHARS, AppLimits.MAX_TOOL_RESULT_MAX_CHARS)
            if (activeSkillNames.isNotEmpty()) {
                logInfo("active skills selected: ${activeSkillNames.joinToString(",")}")
            }

            for (round in 1..maxRounds) {
                logInfo("round $round start")
                val toolSpec = toolRegistry.toToolSpecList()

                val turn = requestNonStreamTurn(
                    sessionId = sessionId,
                    toolSpec = toolSpec,
                    activeSkillsContent = activeSkillsContent,
                    skillsSummary = skillsSummary,
                    systemPolicyTemplate = systemPolicyTemplate
                ) ?: run {
                    logWarn("non-stream turn failed; stop loop")
                    return@withContext
                }

                val parsedToolCalls = turn.parsedToolCalls
                if (parsedToolCalls.isEmpty()) {
                    logInfo("no tool calls; end loop")
                    return@withContext
                }

                for (call in parsedToolCalls) {
                    logInfo("tool call: ${call.name}, id=${call.id}")
                    val result = if (blockedTools.contains(call.name)) {
                        ToolResult(
                            toolCallId = call.id,
                            content = "Tool '${call.name}' is disabled in this execution context.",
                            isError = true
                        )
                    } else {
                        toolRegistry.execute(call)
                    }
                    val safeContent = truncateToolResult(result.content, toolResultMaxChars)
                    repository.appendToolMessage(
                        sessionId = sessionId,
                        content = safeContent,
                        toolResultJson = json.encodeToString(
                            StoredToolResult(
                                toolCallId = result.toolCallId,
                                content = safeContent,
                                isError = result.isError,
                                metadata = result.metadata
                            )
                        )
                    )
                    logInfo(
                        "tool result appended: ${call.name}, isError=${result.isError}, chars=${safeContent.length}"
                    )
                }
            }
            repository.appendAssistantMessage(sessionId, "Stopped after reaching max rounds ($maxRounds).")
            logWarn("reached max rounds: $maxRounds")
        } catch (ce: CancellationException) {
            cancelled = true
            logInfo("agent loop cancelled by user")
            throw ce
        } catch (t: Throwable) {
            logError("round failed", t)
            repository.appendAssistantMessage(
                sessionId,
                "Error: ${t.message ?: t.javaClass.simpleName}"
            )
            return@withContext
        } finally {
            if (!cancelled) {
                scheduleBackgroundConsolidation(sessionId)
            }
        }
    }

    fun close() {
        backgroundScope.cancel()
        synchronized(consolidationGuard) {
            consolidationJobs.clear()
            consolidationLocks.clear()
        }
    }

    private suspend fun requestNonStreamTurn(
        sessionId: String,
        toolSpec: List<ToolSpec>,
        activeSkillsContent: String,
        skillsSummary: String,
        systemPolicyTemplate: String?
    ): AssistantTurn? {
        val history = repository.getMessages(sessionId)
        val longTermMemory = memoryStore.readLongTerm()
        val llmMessages = contextBuilder.build(
            sessionId = sessionId,
            messages = history,
            maxHistoryMessages = maxContextMessagesProvider().coerceIn(
                AppLimits.MIN_CONTEXT_MESSAGES,
                AppLimits.MAX_CONTEXT_MESSAGES
            ),
            longTermMemory = longTermMemory,
            activeSkillsContent = activeSkillsContent,
            skillsSummary = skillsSummary,
            systemPolicyTemplate = systemPolicyTemplate
        )
        val response = try {
            llmProviderFactory().chat(llmMessages, toolSpec)
        } catch (t: Throwable) {
            if (t is InterruptedIOException || (t.message?.contains("timeout", ignoreCase = true) == true)) {
                throw IllegalStateException(
                    "LLM request timed out (configured timeout reached). " +
                        "You can increase Runtime timeout settings and retry.",
                    t
                )
            }
            throw t
        }
        response.usage?.let { usageReporter?.invoke(it) }
        val parsedToolCalls = toolCallParser.parse(response)
        val toolCallJson = if (parsedToolCalls.isNotEmpty()) {
            json.encodeToString(parsedToolCalls)
        } else {
            null
        }
        logInfo(
            "llm response received; assistantChars=${response.assistant.content.length}, toolCalls=${parsedToolCalls.size}"
        )

        val rawContent = response.assistant.content
        if (rawContent.isBlank() && parsedToolCalls.isEmpty()) {
            repository.appendAssistantMessage(sessionId, "[Error] Empty assistant response.")
            logWarn("empty assistant response without tool call")
            return null
        }

        val persistedContent = when {
            rawContent.isNotBlank() -> rawContent
            parsedToolCalls.isNotEmpty() -> "[tool call]"
            else -> ""
        }

        repository.appendAssistantMessage(
            sessionId = sessionId,
            content = persistedContent,
            toolCallJson = toolCallJson
        )
        logInfo(
            "assistant saved; chars=${persistedContent.length}, toolCalls=${parsedToolCalls.size}"
        )
        return AssistantTurn(parsedToolCalls = parsedToolCalls)
    }

    private fun truncateToolResult(raw: String, maxChars: Int): String {
        if (raw.length <= maxChars) return raw
        return raw.take(maxChars) + "\n...[truncated]"
    }

    private fun buildBootstrapPolicyTemplate(): String? {
        val store = templateStore ?: return null
        val sections = mutableListOf<String>()
        for (name in BOOTSTRAP_TEMPLATE_FILES) {
            val content = when (name) {
                "AGENT.md" -> store.loadTemplate("AGENT.md") ?: store.loadTemplate("system_prompt.md")
                else -> store.loadTemplate(name)
            }?.trim().orEmpty()
            if (content.isBlank()) continue
            sections += "## $name\n\n$content"
        }
        if (sections.isEmpty()) return null
        return sections.joinToString("\n\n---\n\n")
    }

    private fun scheduleBackgroundConsolidation(sessionId: String) {
        val existing = synchronized(consolidationGuard) { consolidationJobs[sessionId] }
        if (existing?.isActive == true) return

        val job = backgroundScope.launch {
            val lock = synchronized(consolidationGuard) {
                consolidationLocks.getOrPut(sessionId) { Mutex() }
            }
            lock.withLock {
                val memoryWindow = normalizedMemoryWindow()
                val allMessages = repository.getMessages(sessionId)
                val safeLast = memoryStore.getLastConsolidatedIndex(sessionId).coerceIn(0, allMessages.size)
                val unconsolidated = allMessages.size - safeLast
                if (unconsolidated < memoryWindow) return@withLock
                val hasPending = memoryConsolidator.hasPendingConsolidation(sessionId, memoryWindow)
                if (!hasPending) return@withLock
                when (val result = memoryConsolidator.consolidateIfNeeded(sessionId, memoryWindow)) {
                    is MemoryConsolidator.ConsolidationResult.Updated -> {
                        logInfo("memory consolidation updated long-term memory")
                    }

                    is MemoryConsolidator.ConsolidationResult.Failed -> {
                        logWarn("memory consolidation failed: ${result.reason}")
                    }

                    is MemoryConsolidator.ConsolidationResult.Noop -> Unit
                }
            }
        }

        synchronized(consolidationGuard) {
            consolidationJobs[sessionId] = job
        }
        job.invokeOnCompletion {
            synchronized(consolidationGuard) {
                if (consolidationJobs[sessionId] === job) {
                    consolidationJobs.remove(sessionId)
                }
            }
        }
    }

    private fun normalizedMemoryWindow(): Int {
        return memoryWindowProvider().coerceIn(
            AppLimits.MIN_MEMORY_CONSOLIDATION_WINDOW,
            AppLimits.MAX_MEMORY_CONSOLIDATION_WINDOW
        )
    }

    @kotlinx.serialization.Serializable
    private data class StoredToolResult(
        val toolCallId: String,
        val content: String,
        val isError: Boolean,
        val metadata: JsonObject? = null
    )

    private data class AssistantTurn(
        val parsedToolCalls: List<ToolCall>
    )

    private fun logInfo(message: String) {
        Log.d(TAG, message)
        processLogger?.invoke(message)
    }

    private fun logWarn(message: String) {
        Log.w(TAG, message)
        processLogger?.invoke("WARN: $message")
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        val suffix = throwable?.message?.takeIf { it.isNotBlank() }?.let { " | $it" }.orEmpty()
        processLogger?.invoke("ERROR: $message$suffix")
    }

    companion object {
        private val sessionIdThreadLocal = ThreadLocal<String?>()

        fun currentSessionId(): String? = sessionIdThreadLocal.get()

        private fun sessionContext(sessionId: String) = sessionIdThreadLocal.asContextElement(sessionId)
        private const val TAG = "AgentLoop"
        private val BOOTSTRAP_TEMPLATE_FILES = listOf(
            "AGENT.md",
            "SOUL.md",
            "USER.md",
            "TOOLS.md"
        )
    }
}




