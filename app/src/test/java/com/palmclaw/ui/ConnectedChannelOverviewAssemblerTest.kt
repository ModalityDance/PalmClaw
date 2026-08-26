package com.palmclaw.ui

import com.palmclaw.channels.ChannelBindingRuntimeProjection
import com.palmclaw.config.SessionChannelBinding
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectedChannelOverviewAssemblerTest {

    @Test
    fun `overview excludes local and unsupported sessions then sorts and maps projections`() {
        val sessions = listOf(
            UiSessionSummary(id = "local", title = "Local", isLocal = true),
            UiSessionSummary(id = "z", title = "Zulu", isLocal = false),
            UiSessionSummary(id = "a", title = "alpha", isLocal = false),
            UiSessionSummary(id = "unsupported", title = "Other", isLocal = false),
            UiSessionSummary(id = "unbound", title = "None", isLocal = false)
        )
        val bindings = listOf(
            SessionChannelBinding(sessionId = "local", channel = "telegram"),
            SessionChannelBinding(sessionId = "z", enabled = false, channel = "telegram"),
            SessionChannelBinding(sessionId = "a", channel = "slack"),
            SessionChannelBinding(sessionId = "unsupported", channel = "matrix")
        )

        val result = ConnectedChannelOverviewAssembler.build(
            sessions = sessions,
            bindings = bindings,
            projectionForBinding = { binding ->
                ChannelBindingRuntimeProjection(
                    channel = binding.channel.trim(),
                    target = "target-${binding.sessionId}",
                    adapterKeys = listOf("key-${binding.sessionId}"),
                    status = "status-${binding.sessionId}"
                )
            }
        )

        assertEquals(listOf("a", "z"), result.map { it.sessionId })
        assertEquals("target-a", result[0].chatId)
        assertEquals("status-a", result[0].status)
        assertEquals(false, result[1].enabled)
    }
}
