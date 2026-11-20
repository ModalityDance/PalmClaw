package com.palmclaw.tools

import android.util.Log
import com.palmclaw.providers.ToolCall
import com.palmclaw.providers.ToolSpec
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

@JvmInline
value class ToolRegistryOwner(val value: String) {
    init {
        require(value.isNotBlank()) { "Tool registry owner must not be blank." }
    }
}

enum class OwnedToolReplaceRejection {
    BLANK_TOOL_NAME,
    DUPLICATE_TOOL_NAME,
    NAME_CONFLICT
}

sealed interface OwnedToolReplaceResult {
    data class Applied(
        val publishedToolNames: List<String>,
        val removedToolNames: List<String>
    ) : OwnedToolReplaceResult

    data class Rejected(
        val reason: OwnedToolReplaceRejection,
        val toolNames: List<String>
    ) : OwnedToolReplaceResult
}

data class OwnedToolRemoveResult(
    val removedToolNames: List<String>
)

class ToolRegistry(
    initialTools: Map<String, Tool>,
    private val timeoutMsProvider: () -> Long = { 60_000L },
    private val debugLog: (String) -> Unit = { message -> Log.d(TAG, message) }
) {
    private val mutationLock = Any()

    @Volatile
    private var state = RegistryState(
        tools = initialTools.toMap(),
        ownersByToolName = emptyMap()
    )
    private val argumentsValidator = ToolArgumentsValidator()

    fun toToolSpecList(): List<ToolSpec> {
        return state.tools.values.map {
            ToolSpec(name = it.name, description = it.description, parameters = it.jsonSchema)
        }
    }

    /** Registers an unowned tool. Replacing an owned name detaches it from that owner. */
    fun register(tool: Tool) {
        updateState { current ->
            RegistryState(
                tools = current.tools + (tool.name to tool),
                ownersByToolName = current.ownersByToolName - tool.name
            )
        }
    }

    fun unregister(name: String) {
        updateState { current ->
            if (name !in current.tools) {
                current
            } else {
                RegistryState(
                    tools = current.tools - name,
                    ownersByToolName = current.ownersByToolName - name
                )
            }
        }
    }

    fun registerAll(list: List<Tool>) {
        if (list.isEmpty()) return
        updateState { current ->
            val updatedTools = current.tools.toMutableMap()
            val updatedOwners = current.ownersByToolName.toMutableMap()
            list.forEach { tool ->
                updatedTools[tool.name] = tool
                updatedOwners.remove(tool.name)
            }
            RegistryState(
                tools = updatedTools.toMap(),
                ownersByToolName = updatedOwners.toMap()
            )
        }
    }

    fun unregisterByPrefix(prefix: String): Int {
        return synchronized(mutationLock) {
            val current = state
            val names = current.tools.keys.filter { it.startsWith(prefix) }
            if (names.isEmpty()) return@synchronized 0
            state = RegistryState(
                tools = current.tools - names.toSet(),
                ownersByToolName = current.ownersByToolName - names.toSet()
            )
            names.size
        }
    }

    /**
     * Replaces every tool published by [owner] as one linearizable update.
     * Invalid or conflicting replacements leave the registry unchanged.
     */
    fun replaceOwned(
        owner: ToolRegistryOwner,
        replacements: List<Tool>
    ): OwnedToolReplaceResult {
        val blankNames = replacements.map { tool -> tool.name }.filter { name -> name.isBlank() }
        if (blankNames.isNotEmpty()) {
            return OwnedToolReplaceResult.Rejected(
                reason = OwnedToolReplaceRejection.BLANK_TOOL_NAME,
                toolNames = blankNames.distinct().sorted()
            )
        }

        val duplicateNames = replacements
            .groupingBy { tool -> tool.name }
            .eachCount()
            .filterValues { count -> count > 1 }
            .keys
            .sorted()
        if (duplicateNames.isNotEmpty()) {
            return OwnedToolReplaceResult.Rejected(
                reason = OwnedToolReplaceRejection.DUPLICATE_TOOL_NAME,
                toolNames = duplicateNames
            )
        }

        val replacementsByName = replacements.associateBy { tool -> tool.name }
        return synchronized(mutationLock) {
            val current = state
            val conflictingNames = replacementsByName.keys
                .filter { name ->
                    name in current.tools && current.ownersByToolName[name] != owner
                }
                .sorted()
            if (conflictingNames.isNotEmpty()) {
                return@synchronized OwnedToolReplaceResult.Rejected(
                    reason = OwnedToolReplaceRejection.NAME_CONFLICT,
                    toolNames = conflictingNames
                )
            }

            val previousNames = current.ownersByToolName
                .filterValues { existingOwner -> existingOwner == owner }
                .keys
            val updatedTools = current.tools.toMutableMap().apply {
                previousNames.forEach { name -> remove(name) }
                putAll(replacementsByName)
            }
            val updatedOwners = current.ownersByToolName.toMutableMap().apply {
                previousNames.forEach { name -> remove(name) }
                replacementsByName.keys.forEach { name -> put(name, owner) }
            }
            state = RegistryState(
                tools = updatedTools.toMap(),
                ownersByToolName = updatedOwners.toMap()
            )
            OwnedToolReplaceResult.Applied(
                publishedToolNames = replacementsByName.keys.sorted(),
                removedToolNames = (previousNames - replacementsByName.keys).sorted()
            )
        }
    }

    /** Removes only the tools currently published by [owner] as one linearizable update. */
    fun removeOwned(owner: ToolRegistryOwner): OwnedToolRemoveResult {
        return synchronized(mutationLock) {
            val current = state
            val names = current.ownersByToolName
                .filterValues { existingOwner -> existingOwner == owner }
                .keys
            if (names.isEmpty()) return@synchronized OwnedToolRemoveResult(emptyList())
            state = RegistryState(
                tools = current.tools - names,
                ownersByToolName = current.ownersByToolName - names
            )
            OwnedToolRemoveResult(names.sorted())
        }
    }

    fun get(name: String): Tool? = state.tools[name]

    fun has(name: String): Boolean = state.tools.containsKey(name)

    fun toolNames(): List<String> = state.tools.keys.sorted()

    val size: Int
        get() = state.tools.size

    operator fun contains(name: String): Boolean = has(name)

    suspend fun execute(call: ToolCall): ToolResult {
        val defaultTimeoutMs = timeoutMsProvider().coerceAtLeast(1_000L)
        var effectiveTimeoutMs = defaultTimeoutMs
        var action = "unknown"
        val tool = state.tools[call.name]
        if (tool == null) {
            return ToolResult(
                toolCallId = call.id,
                content = buildString {
                    append("Tool not found: ${call.name}.")
                    val available = toolNames()
                    if (available.isNotEmpty()) {
                        append(" Available: ")
                        append(available.joinToString(", "))
                    }
                },
                isError = true,
                metadata = buildJsonObject { put("error", "not_found") }
            )
        }

        return try {
            debugLog("Executing tool ${tool.name}, callId=${call.id}")
            val parsedArgs = argumentsValidator.parseArgumentsObject(call.argumentsJson)
            if (parsedArgs == null) {
                return ToolResult(
                    toolCallId = call.id,
                    content = "Invalid arguments for ${call.name}: JSON object expected. The tool was not run.",
                    isError = true,
                    metadata = buildJsonObject { put("error", "invalid_arguments") }
                )
            }

            val validationErrors = argumentsValidator.validate(tool.jsonSchema, parsedArgs)
            if (validationErrors.isNotEmpty()) {
                return ToolResult(
                    toolCallId = call.id,
                    content = "Invalid parameters for ${call.name}: ${validationErrors.joinToString("; ")}. The tool was not run.",
                    isError = true,
                    metadata = buildJsonObject {
                        put("error", "invalid_parameters")
                        put("error_count", validationErrors.size)
                    }
                )
            }

            effectiveTimeoutMs = (tool as? TimedTool)?.timeoutMs?.takeIf { it > 0 } ?: defaultTimeoutMs
            action = (parsedArgs["action"] as? JsonPrimitive)?.contentOrNull ?: tool.name
            // Use normalized JSON object string to avoid provider quirks where arguments is a JSON string.
            val raw = withTimeout(effectiveTimeoutMs) { tool.run(parsedArgs.toString()) }
            raw.copy(toolCallId = call.id)
        } catch (_: TimeoutCancellationException) {
            interruptedResult(call, action, "timeout", "Execution timed out after ${effectiveTimeoutMs}ms. Any side effects are unverified.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            interruptedResult(call, action, t.javaClass.simpleName, "Execution ended without a reliable result: ${t.message}")
        }
    }

    private fun interruptedResult(call: ToolCall, action: String, code: String, message: String): ToolResult =
        MutationFeedback(
            ExecutionState.UNKNOWN, VerificationState.UNAVAILABLE, buildJsonObject {},
            "Inspect current state before repeating a state-changing operation. This result does not establish that nothing happened."
        ).result(call.name, action, code, message).copy(toolCallId = call.id)

    private inline fun updateState(transform: (RegistryState) -> RegistryState) {
        synchronized(mutationLock) {
            state = transform(state)
        }
    }

    private data class RegistryState(
        val tools: Map<String, Tool>,
        val ownersByToolName: Map<String, ToolRegistryOwner>
    )

    companion object {
        private const val TAG = "ToolRegistry"
    }
}
