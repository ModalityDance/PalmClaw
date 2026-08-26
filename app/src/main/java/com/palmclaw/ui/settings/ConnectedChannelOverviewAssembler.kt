package com.palmclaw.ui

import com.palmclaw.channels.ChannelBindingRuntimeProjection
import com.palmclaw.config.SessionChannelBinding
import java.util.Locale

internal object ConnectedChannelOverviewAssembler {
    fun build(
        sessions: List<UiSessionSummary>,
        bindings: List<SessionChannelBinding>,
        projectionForBinding: (SessionChannelBinding) -> ChannelBindingRuntimeProjection
    ): List<UiConnectedChannelSummary> {
        val bindingsBySession = bindings.associateBy { it.sessionId.trim() }
        return sessions
            .asSequence()
            .filterNot { it.isLocal }
            .mapNotNull { session ->
                val binding = bindingsBySession[session.id] ?: return@mapNotNull null
                val channel = binding.channel.trim().lowercase(Locale.US)
                if (channel !in SUPPORTED_CHANNELS) return@mapNotNull null
                val projection = projectionForBinding(binding)
                UiConnectedChannelSummary(
                    sessionId = session.id,
                    sessionTitle = session.title,
                    channel = projection.channel,
                    chatId = projection.target,
                    enabled = binding.enabled,
                    status = projection.status
                )
            }
            .sortedWith(
                compareBy<UiConnectedChannelSummary>(
                    { it.channel },
                    { it.sessionTitle.lowercase(Locale.US) }
                )
            )
            .toList()
    }

    private val SUPPORTED_CHANNELS = setOf("telegram", "discord", "slack", "feishu", "email", "wecom")
}
