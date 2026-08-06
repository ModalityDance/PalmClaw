package com.palmclaw.ui

import com.palmclaw.channels.ChannelGatewayDiagnosticsSource

internal class GatewayStatusOverviewAssembler(
    private val diagnosticsSource: ChannelGatewayDiagnosticsSource
) {
    fun build(): SettingsStateAssembler.GatewayStatuses {
        val snapshot = diagnosticsSource.snapshot()
        return SettingsStateAssembler.GatewayStatuses(
            discord = GatewayStatusFormatter.buildDiscordStatus(
                snapshot.runtimeSnapshots("discord"),
                snapshot.discordSnapshots
            ),
            slack = GatewayStatusFormatter.buildSlackStatus(
                snapshot.runtimeSnapshots("slack"),
                snapshot.slackSnapshots
            ),
            feishu = GatewayStatusFormatter.buildFeishuStatus(
                snapshot.runtimeSnapshots("feishu"),
                snapshot.feishuSnapshots
            ),
            email = GatewayStatusFormatter.buildEmailStatus(
                snapshot.runtimeSnapshots("email"),
                snapshot.emailSnapshots
            ),
            wecom = GatewayStatusFormatter.buildWeComStatus(
                snapshot.runtimeSnapshots("wecom"),
                snapshot.weComSnapshots
            )
        )
    }
}
