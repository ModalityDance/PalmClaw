package com.palmclaw.tools

import android.util.Log
import com.palmclaw.providers.ToolCall
import com.palmclaw.providers.ToolSpec
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap

class ToolRegistry(
    initialTools: Map<String, Tool>,
    private val timeoutMsProvider: () -> Long = { 60_000L }
) {
    private val tools = ConcurrentHashMap(initialTools)
    private val argumentsValidator = ToolArgumentsValidator()
    private val errorHint = "\n\n[Analyze the error above and try a different approach.]"

    fun toToolSpecList(): List<ToolSpec> {
        return tools.values.map {
            ToolSpec(name = it.name, description = it.description, parameters = it.jsonSchema)
        }
    }

    fun register(tool: Tool) {
        tools[tool.name] = tool
    }

    fun unregister(name: String) {
        tools.remove(name)
    }

    fun registerAll(list: List<Tool>) {
        list.forEach { register(it) }
    }

    fun unregisterByPrefix(prefix: String): Int {
        val keys = tools.keys.filter { it.startsWith(prefix) }
        keys.forEach { tools.remove(it) }
        return keys.size
    }

    fun get(name: String): Tool? = tools[name]

    fun has(name: String): Boolean = tools.containsKey(name)

    fun toolNames(): List<String> = tools.keys().toList().sorted()

    val size: Int
        get() = tools.size

    operator fun contains(name: String): Boolean = has(name)

    suspend fun execute(call: ToolCall): ToolResult {
        val defaultTimeoutMs = timeoutMsProvider().coerceAtLeast(1_000L)
        var effectiveTimeoutMs = defaultTimeoutMs
        val tool = tools[call.name]
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
                    append(errorHint)
                },
                isError = true,
                metadata = buildJsonObject { put("error", "not_found") }
            )
        }

        return try {
            Log.d(TAG, "Executing tool ${tool.name}, callId=${call.id}")
            val parsedArgs = argumentsValidator.parseArgumentsObject(call.argumentsJson)
            if (parsedArgs == null) {
                return ToolResult(
                    toolCallId = call.id,
                    content = "Invalid arguments for ${call.name}: JSON object expected$errorHint",
                    isError = true,
                    metadata = buildJsonObject { put("error", "invalid_arguments") }
                )
            }

            val validationErrors = argumentsValidator.validate(tool.jsonSchema, parsedArgs)
            if (validationErrors.isNotEmpty()) {
                return ToolResult(
                    toolCallId = call.id,
                    content = "Invalid parameters for ${call.name}: ${validationErrors.joinToString("; ")}$errorHint",
                    isError = true,
                    metadata = buildJsonObject {
                        put("error", "invalid_parameters")
                        put("error_count", validationErrors.size)
                    }
                )
            }

            effectiveTimeoutMs = (tool as? TimedTool)?.timeoutMs?.takeIf { it > 0 } ?: defaultTimeoutMs
            // Use normalized JSON object string to avoid provider quirks where arguments is a JSON string.
            val raw = withTimeout(effectiveTimeoutMs) { tool.run(parsedArgs.toString()) }
            if (raw.isError && !raw.content.contains(errorHint)) {
                raw.copy(toolCallId = call.id, content = raw.content + errorHint)
            } else {
                raw.copy(toolCallId = call.id)
            }
        } catch (_: TimeoutCancellationException) {
            ToolResult(
                toolCallId = call.id,
                content = "Tool execution timed out for ${call.name} after ${effectiveTimeoutMs}ms$errorHint",
                isError = true,
                metadata = buildJsonObject {
                    put("error", "timeout")
                    put("timeout_ms", effectiveTimeoutMs)
                }
            )
        } catch (t: Throwable) {
            ToolResult(
                toolCallId = call.id,
                content = "Tool execution failed for ${call.name}: ${t.message}$errorHint",
                isError = true,
                metadata = buildJsonObject {
                    put("error", t.javaClass.simpleName)
                }
            )
        }
    }

    companion object {
        private const val TAG = "ToolRegistry"
    }
}

