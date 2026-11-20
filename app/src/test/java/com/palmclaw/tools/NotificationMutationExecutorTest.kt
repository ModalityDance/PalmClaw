package com.palmclaw.tools

import org.junit.Assert.*
import org.junit.Test

class NotificationMutationExecutorTest {
    @Test
    fun `failed preflight never dispatches a notification mutation`() {
        var writes = 0
        val executor = NotificationMutationExecutor({ error("query failed") }, { writes++ }, { writes++ })
        val result = executor.publish(NotificationPublishMode.CREATE, spec) as NotificationGatewayResult.Failure
        assertEquals("notification_query_unavailable", result.code)
        assertNull(result.feedback)
        assertEquals(0, writes)
    }

    private val spec = AgentNotificationSpec("receipt-test", "New title", "New text", null)
    private fun active(title: String = spec.title) = ActiveAgentNotification(
        spec.key, title, spec.text, 1L, null, "test", "open_app"
    )

    @Test
    fun `posting keeps the key when readback fails after submission`() {
        var posts = 0
        val executor = NotificationMutationExecutor(
            readActive = { if (posts == 0) emptyList() else error("read unavailable") },
            post = { posts++ },
            cancel = {}
        )
        val result = executor.publish(NotificationPublishMode.CREATE, spec) as NotificationGatewayResult.Failure
        assertEquals(1, posts)
        assertEquals(ExecutionState.SUBMITTED, result.feedback!!.executionState)
        assertEquals(spec.key, result.feedback.receipt["notification_key"]!!.toString().trim('"'))
    }

    @Test
    fun `an old active notification does not verify an update`() {
        val executor = NotificationMutationExecutor({ listOf(active("Old title")) }, {}, {})
        val result = executor.publish(NotificationPublishMode.UPDATE, spec) as NotificationGatewayResult.Failure
        assertEquals(VerificationState.MISMATCH, result.feedback!!.verification)
    }

    @Test
    fun `matching readback verifies the post`() {
        var posted = false
        val executor = NotificationMutationExecutor(
            { if (posted) listOf(active()) else emptyList() }, { posted = true }, {}
        )
        assertTrue(executor.publish(NotificationPublishMode.CREATE, spec) is NotificationGatewayResult.Success)
    }

    @Test
    fun `cancel keeps its receipt if verification is unavailable`() {
        var cancelled = false
        val executor = NotificationMutationExecutor(
            { if (cancelled) error("read unavailable") else listOf(active()) }, {}, { cancelled = true }
        )
        val result = executor.cancel(spec.key) as NotificationGatewayResult.Failure
        assertTrue(cancelled)
        assertEquals(ExecutionState.SUBMITTED, result.feedback!!.executionState)
    }

    @Test
    fun `a posting exception cannot claim that nothing happened`() {
        val executor = NotificationMutationExecutor({ emptyList() }, { error("IPC failed") }, {})
        val result = executor.publish(NotificationPublishMode.CREATE, spec) as NotificationGatewayResult.Failure
        assertEquals(ExecutionState.UNKNOWN, result.feedback!!.executionState)
    }
}
