package com.palmclaw.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationControlToolTest {

    @Test
    fun `schema exposes the complete bounded notification lifecycle`() {
        val tool = createTool()
        val actions = tool.jsonSchema["properties"]!!
            .jsonObject["action"]!!
            .jsonObject["enum"] as JsonArray

        assertEquals(
            listOf("status", "list_active", "post", "update", "cancel", "open_settings"),
            actions.map { it.jsonPrimitive.content }
        )
        val properties = tool.jsonSchema["properties"]!!.jsonObject
        assertFalse(properties.containsKey("channel_id"))
        assertFalse(properties.containsKey("ongoing"))
        assertFalse(properties.containsKey("url"))
    }

    @Test
    fun `status and settings actions return structured results`() = runBlocking {
        val tool = createTool()

        val status = tool.run("""{"action":"status"}""")
        val settings = tool.run("""{"action":"open_settings"}""")

        assertFalse(status.content, status.isError)
        assertTrue(status.body()["permission_granted"]!!.jsonPrimitive.boolean)
        assertTrue(status.body()["notifications_enabled"]!!.jsonPrimitive.boolean)
        assertFalse(settings.content, settings.isError)
        assertTrue(settings.body()["opened"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `agent can post and retrieve a namespaced notification`() = runBlocking {
        val tool = createTool()

        val posted = tool.run(
            """{
              "action":"post",
              "notification_key":"paper.ready",
              "title":"Paper ready",
              "text":"The export has completed.",
              "timeout_sec":60
            }""".trimIndent()
        )
        val listed = tool.run("""{"action":"list_active"}""")

        assertFalse(posted.content, posted.isError)
        assertEquals("paper.ready", posted.body()["notification"]!!.jsonObject.key())
        assertEquals(60, posted.body()["notification"]!!.jsonObject["timeout_sec"]!!.jsonPrimitive.content.toInt())
        assertFalse(listed.content, listed.isError)
        assertEquals(1, listed.body()["count"]!!.jsonPrimitive.content.toInt())
        assertEquals(
            "The export has completed.",
            listed.notifications().single()["text"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `post generates and returns a stable key when none is supplied`() = runBlocking {
        val tool = createTool()

        val posted = tool.run(
            """{"action":"post","title":"Ready","text":"The task is complete."}"""
        )
        val listed = tool.run("""{"action":"list_active"}""")

        assertFalse(posted.content, posted.isError)
        assertEquals("generated-key", posted.body()["notification"]!!.jsonObject.key())
        assertEquals("generated-key", listed.notifications().single().key())
    }

    @Test
    fun `duplicate post is rejected and update changes the existing notification`() = runBlocking {
        val tool = createTool()
        val original = """{
          "action":"post",
          "notification_key":"task.result",
          "title":"Task",
          "text":"Version one"
        }""".trimIndent()
        assertFalse(tool.run(original).isError)

        val duplicate = tool.run(original)
        val updated = tool.run(
            """{
              "action":"update",
              "notification_key":"task.result",
              "title":"Task",
              "text":"Version two"
            }""".trimIndent()
        )
        val listed = tool.run("""{"action":"list_active"}""")

        assertTrue(duplicate.isError)
        assertEquals("notification_exists", duplicate.body()["code"]!!.jsonPrimitive.content)
        assertFalse(updated.content, updated.isError)
        assertEquals(1, listed.notifications().size)
        assertEquals("Version two", listed.notifications().single()["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `update never recreates a notification that is no longer active`() = runBlocking {
        val tool = createTool()

        val result = tool.run(
            """{
              "action":"update",
              "notification_key":"missing",
              "title":"Missing",
              "text":"Must not be recreated"
            }""".trimIndent()
        )
        val listed = tool.run("""{"action":"list_active"}""")

        assertTrue(result.isError)
        assertEquals("notification_not_found", result.body()["code"]!!.jsonPrimitive.content)
        assertTrue(listed.notifications().isEmpty())
    }

    @Test
    fun `cancel removes only the exact agent notification`() = runBlocking {
        val tool = createTool()
        tool.run("""{"action":"post","notification_key":"one","title":"One","text":"First"}""")
        tool.run("""{"action":"post","notification_key":"two","title":"Two","text":"Second"}""")

        val cancelled = tool.run("""{"action":"cancel","notification_key":"one"}""")
        val listed = tool.run("""{"action":"list_active"}""")

        assertFalse(cancelled.content, cancelled.isError)
        assertTrue(cancelled.body()["cancelled"]!!.jsonPrimitive.boolean)
        assertEquals(listOf("two"), listed.notifications().map { it.key() })
    }

    @Test
    fun `cancel rejects a key that is not active`() = runBlocking {
        val tool = createTool()

        val result = tool.run("""{"action":"cancel","notification_key":"missing"}""")

        assertTrue(result.isError)
        assertEquals("notification_not_found", result.body()["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `permission failure leaves notification state unchanged`() = runBlocking {
        val gateway = InMemoryNotificationGateway()
        val interaction = FakeNotificationUserInteraction(
            permissionResult = NotificationGatewayResult.Failure(
                code = "permission_required",
                message = "Permission denied."
            )
        )
        val tool = NotificationControlTool(gateway, interaction)

        val result = tool.run(
            """{"action":"post","notification_key":"blocked","title":"Blocked","text":"No post"}"""
        )

        assertTrue(result.isError)
        assertEquals("permission_required", result.body()["code"]!!.jsonPrimitive.content)
        assertTrue(gateway.activeValues().isEmpty())
    }

    @Test
    fun `arguments are validated before requesting permission`() = runBlocking {
        val gateway = InMemoryNotificationGateway()
        val interaction = FakeNotificationUserInteraction(
            permissionResult = NotificationGatewayResult.Failure(
                code = "permission_required",
                message = "Permission denied."
            )
        )
        val tool = NotificationControlTool(gateway, interaction)

        val result = tool.run(
            """{"action":"post","notification_key":"invalid","title":"","text":"No post"}"""
        )

        assertTrue(result.isError)
        assertEquals("invalid_arguments", result.body()["code"]!!.jsonPrimitive.content)
        assertTrue(gateway.activeValues().isEmpty())
    }

    private fun createTool(): NotificationControlTool =
        NotificationControlTool(
            gateway = InMemoryNotificationGateway(),
            userInteraction = FakeNotificationUserInteraction(),
            keyGenerator = { "generated-key" }
        )

    private fun ToolResult.body(): JsonObject = Json.parseToJsonElement(content).jsonObject

    private fun ToolResult.notifications(): List<JsonObject> =
        (body()["notifications"] as JsonArray).map { it.jsonObject }

    private fun JsonObject.key(): String =
        this["notification_key"]!!.jsonPrimitive.content
}

private class FakeNotificationUserInteraction(
    private val permissionResult: NotificationGatewayResult<Unit> =
        NotificationGatewayResult.Success(Unit),
    private val settingsOpened: Boolean? = true
) : NotificationUserInteraction {
    override suspend fun ensurePostPermission(
        action: String
    ): NotificationGatewayResult<Unit> = permissionResult

    override suspend fun openSettings(): Boolean? = settingsOpened
}

private class InMemoryNotificationGateway : NotificationGateway {
    private val active = linkedMapOf<String, ActiveAgentNotification>()
    private var nowMs = 1_000L

    override fun status(): NotificationGatewayResult<AgentNotificationStatus> =
        NotificationGatewayResult.Success(
            AgentNotificationStatus(
                permissionGranted = true,
                notificationsEnabled = true,
                channelExists = true,
                channelEnabled = true,
                activeCount = active.size
            )
        )

    override fun listActive(limit: Int): NotificationGatewayResult<List<ActiveAgentNotification>> =
        NotificationGatewayResult.Success(active.values.take(limit))

    override fun publish(
        mode: NotificationPublishMode,
        spec: AgentNotificationSpec
    ): NotificationGatewayResult<ActiveAgentNotification> {
        val existing = active[spec.key]
        if (mode == NotificationPublishMode.CREATE && existing != null) {
            return NotificationGatewayResult.Failure(
                "notification_exists",
                "Notification already exists."
            )
        }
        if (mode == NotificationPublishMode.UPDATE && existing == null) {
            return NotificationGatewayResult.Failure(
                "notification_not_found",
                "Notification is not active."
            )
        }
        nowMs += 1
        val value = ActiveAgentNotification(
            key = spec.key,
            title = spec.title,
            text = spec.text,
            postedAtMs = nowMs,
            timeoutAfterMs = spec.timeoutAfterMs,
            channelId = "palmclaw_default",
            tapAction = "open_app"
        )
        active[spec.key] = value
        return NotificationGatewayResult.Success(value)
    }

    override fun cancel(key: String): NotificationGatewayResult<Boolean> =
        if (active.remove(key) != null) {
            NotificationGatewayResult.Success(true)
        } else {
            NotificationGatewayResult.Failure(
                "notification_not_found",
                "Notification is not active."
            )
        }

    fun activeValues(): List<ActiveAgentNotification> = active.values.toList()
}
