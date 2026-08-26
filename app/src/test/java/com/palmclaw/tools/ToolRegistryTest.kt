package com.palmclaw.tools

import com.palmclaw.providers.ToolCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ToolRegistryTest {
    @Test
    fun `owner replacement publishes a complete tool set and removes the previous set`() {
        val registry = emptyRegistry()
        val owner = ToolRegistryOwner("mcp-server-a")
        val firstA = tool("mcp_a_first")
        val firstB = tool("mcp_a_second")

        assertEquals(
            OwnedToolReplaceResult.Applied(
                publishedToolNames = listOf("mcp_a_first", "mcp_a_second"),
                removedToolNames = emptyList()
            ),
            registry.replaceOwned(owner, listOf(firstA, firstB))
        )

        val replacementB = tool("mcp_a_second")
        val replacementC = tool("mcp_a_third")
        assertEquals(
            OwnedToolReplaceResult.Applied(
                publishedToolNames = listOf("mcp_a_second", "mcp_a_third"),
                removedToolNames = listOf("mcp_a_first")
            ),
            registry.replaceOwned(owner, listOf(replacementB, replacementC))
        )

        assertFalse(registry.has("mcp_a_first"))
        assertSame(replacementB, registry.get("mcp_a_second"))
        assertSame(replacementC, registry.get("mcp_a_third"))
    }

    @Test
    fun `invalid owner replacement leaves the previous tool set unchanged`() {
        val registry = emptyRegistry()
        val owner = ToolRegistryOwner("mcp-server-a")
        val existing = tool("mcp_a_existing")
        assertTrue(registry.replaceOwned(owner, listOf(existing)) is OwnedToolReplaceResult.Applied)

        assertEquals(
            OwnedToolReplaceResult.Rejected(
                reason = OwnedToolReplaceRejection.BLANK_TOOL_NAME,
                toolNames = listOf(" ")
            ),
            registry.replaceOwned(owner, listOf(tool(" "), tool("mcp_a_new")))
        )
        assertSame(existing, registry.get("mcp_a_existing"))
        assertFalse(registry.has("mcp_a_new"))

        assertEquals(
            OwnedToolReplaceResult.Rejected(
                reason = OwnedToolReplaceRejection.DUPLICATE_TOOL_NAME,
                toolNames = listOf("mcp_a_duplicate")
            ),
            registry.replaceOwned(
                owner,
                listOf(tool("mcp_a_duplicate"), tool("mcp_a_duplicate"))
            )
        )
        assertSame(existing, registry.get("mcp_a_existing"))
        assertFalse(registry.has("mcp_a_duplicate"))
    }

    @Test
    fun `owner replacement rejects names held outside that owner without partial changes`() {
        val builtIn = tool("device_status")
        val registry = ToolRegistry(
            initialTools = mapOf(builtIn.name to builtIn),
            debugLog = {}
        )
        val owner = ToolRegistryOwner("mcp-server-a")
        val existing = tool("mcp_a_existing")
        assertTrue(registry.replaceOwned(owner, listOf(existing)) is OwnedToolReplaceResult.Applied)

        assertEquals(
            OwnedToolReplaceResult.Rejected(
                reason = OwnedToolReplaceRejection.NAME_CONFLICT,
                toolNames = listOf("device_status")
            ),
            registry.replaceOwned(owner, listOf(tool("mcp_a_new"), tool("device_status")))
        )

        assertSame(existing, registry.get("mcp_a_existing"))
        assertFalse(registry.has("mcp_a_new"))
        assertSame(builtIn, registry.get("device_status"))
    }

    @Test
    fun `different owners replace and remove only their own tools`() {
        val registry = emptyRegistry()
        val ownerA = ToolRegistryOwner("mcp-server-a")
        val ownerB = ToolRegistryOwner("mcp-server-b")
        val toolA = tool("mcp_a_tool")
        val toolB = tool("mcp_b_tool")
        assertTrue(registry.replaceOwned(ownerA, listOf(toolA)) is OwnedToolReplaceResult.Applied)
        assertTrue(registry.replaceOwned(ownerB, listOf(toolB)) is OwnedToolReplaceResult.Applied)

        assertEquals(
            OwnedToolReplaceResult.Rejected(
                reason = OwnedToolReplaceRejection.NAME_CONFLICT,
                toolNames = listOf("mcp_b_tool")
            ),
            registry.replaceOwned(ownerA, listOf(tool("mcp_a_new"), tool("mcp_b_tool")))
        )
        assertSame(toolA, registry.get("mcp_a_tool"))
        assertSame(toolB, registry.get("mcp_b_tool"))

        assertEquals(
            OwnedToolRemoveResult(removedToolNames = listOf("mcp_a_tool")),
            registry.removeOwned(ownerA)
        )
        assertFalse(registry.has("mcp_a_tool"))
        assertSame(toolB, registry.get("mcp_b_tool"))
    }

    @Test
    fun `legacy registration takes ownership of its replacement away from an owner`() {
        val registry = emptyRegistry()
        val owner = ToolRegistryOwner("mcp-server-a")
        assertTrue(
            registry.replaceOwned(owner, listOf(tool("shared_name"))) is OwnedToolReplaceResult.Applied
        )
        val legacyReplacement = tool("shared_name")

        registry.register(legacyReplacement)

        assertEquals(OwnedToolRemoveResult(emptyList()), registry.removeOwned(owner))
        assertSame(legacyReplacement, registry.get("shared_name"))
    }

    @Test
    fun `concurrent owners cannot both publish the same tool name`() {
        val registry = emptyRegistry()
        val ownerA = ToolRegistryOwner("mcp-server-a")
        val ownerB = ToolRegistryOwner("mcp-server-b")
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val results = Collections.synchronizedList(
            mutableListOf<Pair<ToolRegistryOwner, OwnedToolReplaceResult>>()
        )

        listOf(ownerA, ownerB).forEach { owner ->
            Thread {
                ready.countDown()
                start.await()
                results += owner to registry.replaceOwned(owner, listOf(tool("shared_remote_tool")))
                finished.countDown()
            }.start()
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))

        val applied = results.filter { it.second is OwnedToolReplaceResult.Applied }
        val rejected = results.filter { it.second is OwnedToolReplaceResult.Rejected }
        assertEquals(1, applied.size)
        assertEquals(1, rejected.size)
        assertEquals(
            OwnedToolReplaceResult.Rejected(
                reason = OwnedToolReplaceRejection.NAME_CONFLICT,
                toolNames = listOf("shared_remote_tool")
            ),
            rejected.single().second
        )

        val winningOwner = applied.single().first
        val losingOwner = rejected.single().first
        assertEquals(OwnedToolRemoveResult(emptyList()), registry.removeOwned(losingOwner))
        assertTrue(registry.has("shared_remote_tool"))
        assertEquals(
            OwnedToolRemoveResult(listOf("shared_remote_tool")),
            registry.removeOwned(winningOwner)
        )
        assertFalse(registry.has("shared_remote_tool"))
    }

    @Test
    fun `structured tool errors remain valid json when recovery guidance is added`() = runBlocking {
        val registry = registry(
            ToolResult(
                toolCallId = "",
                content = """{"status":"error","code":"invalid_arguments"}""",
                isError = true
            )
        )

        val result = registry.execute(call())
        val body = Json.parseToJsonElement(result.content).jsonObject

        assertEquals("call-1", result.toolCallId)
        assertEquals("invalid_arguments", body["code"]!!.jsonPrimitive.content)
        assertEquals(
            "Analyze the error above and try a different approach.",
            body["recovery_hint"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `plain text tool errors retain the legacy recovery suffix`() = runBlocking {
        val result = registry(
            ToolResult(toolCallId = "", content = "plain failure", isError = true)
        ).execute(call())

        assertTrue(result.content.startsWith("plain failure"))
        assertTrue(result.content.contains("[Analyze the error above and try a different approach.]"))
    }

    @Test(expected = CancellationException::class)
    fun `tool execution preserves caller cancellation`(): Unit = runBlocking {
        val cancelledTool = object : Tool {
            override val name: String = "cancelled_tool"
            override val description: String = "Cancels"
            override val jsonSchema: JsonObject = buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
            }

            override suspend fun run(argumentsJson: String): ToolResult {
                throw CancellationException("cancelled")
            }
        }
        val registry = ToolRegistry(mapOf(cancelledTool.name to cancelledTool), debugLog = {})

        registry.execute(call().copy(name = cancelledTool.name))
    }

    private fun registry(result: ToolResult): ToolRegistry {
        val tool = tool("test_tool", result)
        return ToolRegistry(
            initialTools = mapOf(tool.name to tool),
            debugLog = {}
        )
    }

    private fun emptyRegistry(): ToolRegistry = ToolRegistry(
        initialTools = emptyMap(),
        debugLog = {}
    )

    private fun tool(
        name: String,
        result: ToolResult = ToolResult(toolCallId = "", content = "ok", isError = false)
    ): Tool {
        return object : Tool {
            override val name: String = name
            override val description: String = "Test tool $name"
            override val jsonSchema: JsonObject = buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
            }

            override suspend fun run(argumentsJson: String): ToolResult = result
        }
    }

    private fun call() = ToolCall(
        id = "call-1",
        name = "test_tool",
        argumentsJson = "{}"
    )
}
