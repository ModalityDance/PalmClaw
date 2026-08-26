package com.palmclaw.channels

import java.util.Locale

internal data class ChannelGatewayDiagnosticsSnapshot(
    val runtimeSnapshotsByChannel: Map<String, List<ChannelRuntimeSnapshot>>,
    val discordSnapshots: List<DiscordGatewaySnapshot>,
    val slackSnapshots: List<SlackGatewaySnapshot>,
    val feishuSnapshots: List<FeishuGatewaySnapshot>,
    val emailSnapshots: List<EmailGatewaySnapshot>,
    val weComSnapshots: List<WeComGatewaySnapshot>
) {
    fun runtimeSnapshots(channel: String): List<ChannelRuntimeSnapshot> =
        runtimeSnapshotsByChannel[channel.trim().lowercase(Locale.US)].orEmpty()
}

internal fun interface ChannelGatewayDiagnosticsSource {
    fun snapshot(): ChannelGatewayDiagnosticsSnapshot
}

internal object ProcessChannelGatewayDiagnosticsSource : ChannelGatewayDiagnosticsSource {
    override fun snapshot(): ChannelGatewayDiagnosticsSnapshot = ChannelGatewayDiagnosticsSnapshot(
        runtimeSnapshotsByChannel = SUPPORTED_CHANNELS.associateWith { channel ->
            ChannelRuntimeDiagnostics.getSnapshots(channel).values.toList()
        },
        discordSnapshots = DiscordGatewayDiagnostics.getSnapshots().values.toList(),
        slackSnapshots = SlackGatewayDiagnostics.getSnapshots().values.toList(),
        feishuSnapshots = FeishuGatewayDiagnostics.getSnapshots().values.toList(),
        emailSnapshots = EmailGatewayDiagnostics.getSnapshots().values.toList(),
        weComSnapshots = WeComGatewayDiagnostics.getSnapshots().values.toList()
    )

    private val SUPPORTED_CHANNELS = listOf("telegram", "discord", "slack", "feishu", "email", "wecom")
}
