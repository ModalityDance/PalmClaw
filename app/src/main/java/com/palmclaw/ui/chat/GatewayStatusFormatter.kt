package com.palmclaw.ui

import com.palmclaw.channels.ChannelRuntimeSnapshot
import com.palmclaw.channels.DiscordGatewaySnapshot
import com.palmclaw.channels.EmailGatewaySnapshot
import com.palmclaw.channels.FeishuGatewaySnapshot
import com.palmclaw.channels.SlackGatewaySnapshot
import com.palmclaw.channels.WeComGatewaySnapshot

internal object GatewayStatusFormatter {
    fun buildDiscordStatus(
        runtimeSnapshots: Collection<ChannelRuntimeSnapshot>,
        gatewaySnapshots: Collection<DiscordGatewaySnapshot>
    ): String {
        val snapshot = gatewaySnapshots.singleOrNull()
        return buildStatusLines(runtimeSnapshots).apply {
            if (snapshot?.botUserId?.isNotBlank() == true) {
                add("Bot User ID: ${snapshot.botUserId}")
            }
            add("Inbound seen: ${gatewaySnapshots.sumOf { it.inboundSeen }}")
            add("Inbound forwarded: ${gatewaySnapshots.sumOf { it.inboundForwarded }}")
            add("Outbound sent: ${gatewaySnapshots.sumOf { it.outboundSent }}")
            if (snapshot?.lastInboundChannelId?.isNotBlank() == true) {
                add("Last inbound channel: ${snapshot.lastInboundChannelId}")
            }
            if (snapshot?.lastGatewayPayload?.isNotBlank() == true) {
                add("Last payload: ${snapshot.lastGatewayPayload}")
            }
        }.joinToString("\n")
    }

    fun buildSlackStatus(
        runtimeSnapshots: Collection<ChannelRuntimeSnapshot>,
        gatewaySnapshots: Collection<SlackGatewaySnapshot>
    ): String {
        val snapshot = gatewaySnapshots.singleOrNull()
        return buildStatusLines(runtimeSnapshots).apply {
            if (snapshot?.botUserId?.isNotBlank() == true) {
                add("Bot User ID: ${snapshot.botUserId}")
            }
            add("Inbound seen: ${gatewaySnapshots.sumOf { it.inboundSeen }}")
            add("Inbound forwarded: ${gatewaySnapshots.sumOf { it.inboundForwarded }}")
            add("Outbound sent: ${gatewaySnapshots.sumOf { it.outboundSent }}")
            if (snapshot?.lastInboundChannelId?.isNotBlank() == true) {
                add("Last inbound channel: ${snapshot.lastInboundChannelId}")
            }
            if (snapshot?.lastEnvelopeType?.isNotBlank() == true) {
                add("Last envelope: ${snapshot.lastEnvelopeType}")
            }
        }.joinToString("\n")
    }

    fun buildFeishuStatus(
        runtimeSnapshots: Collection<ChannelRuntimeSnapshot>,
        gatewaySnapshots: Collection<FeishuGatewaySnapshot>
    ): String {
        val snapshot = gatewaySnapshots.singleOrNull()
        return buildStatusLines(runtimeSnapshots).apply {
            add("Inbound seen: ${gatewaySnapshots.sumOf { it.inboundSeen }}")
            add("Inbound forwarded: ${gatewaySnapshots.sumOf { it.inboundForwarded }}")
            add("Outbound sent: ${gatewaySnapshots.sumOf { it.outboundSent }}")
            if (snapshot?.lastInboundChatId?.isNotBlank() == true) {
                add("Last inbound target: ${snapshot.lastInboundChatId}")
            }
            if (snapshot?.lastSenderOpenId?.isNotBlank() == true) {
                add("Last sender open_id: ${snapshot.lastSenderOpenId}")
            }
            if (snapshot?.lastEventType?.isNotBlank() == true) {
                add("Last event: ${snapshot.lastEventType}")
            }
            val detectedChats = gatewaySnapshots.sumOf { it.recentChats.size }
            if (detectedChats > 0) {
                add("Detected chats: $detectedChats")
            }
        }.joinToString("\n")
    }

    fun buildEmailStatus(
        runtimeSnapshots: Collection<ChannelRuntimeSnapshot>,
        gatewaySnapshots: Collection<EmailGatewaySnapshot>
    ): String {
        val snapshot = gatewaySnapshots.singleOrNull()
        return buildStatusLines(runtimeSnapshots).apply {
            add("Inbound seen: ${gatewaySnapshots.sumOf { it.inboundSeen }}")
            add("Inbound forwarded: ${gatewaySnapshots.sumOf { it.inboundForwarded }}")
            add("Outbound sent: ${gatewaySnapshots.sumOf { it.outboundSent }}")
            if (snapshot?.lastSenderEmail?.isNotBlank() == true) {
                add("Last sender: ${snapshot.lastSenderEmail}")
            }
            if (snapshot?.lastSubject?.isNotBlank() == true) {
                add("Last subject: ${snapshot.lastSubject}")
            }
            val detectedSenders = gatewaySnapshots.sumOf { it.recentSenders.size }
            if (detectedSenders > 0) {
                add("Detected senders: $detectedSenders")
            }
        }.joinToString("\n")
    }

    fun buildWeComStatus(
        runtimeSnapshots: Collection<ChannelRuntimeSnapshot>,
        gatewaySnapshots: Collection<WeComGatewaySnapshot>
    ): String {
        val snapshot = gatewaySnapshots.singleOrNull()
        return buildStatusLines(runtimeSnapshots).apply {
            add("Inbound seen: ${gatewaySnapshots.sumOf { it.inboundSeen }}")
            add("Inbound forwarded: ${gatewaySnapshots.sumOf { it.inboundForwarded }}")
            add("Outbound sent: ${gatewaySnapshots.sumOf { it.outboundSent }}")
            if (snapshot?.lastInboundChatId?.isNotBlank() == true) {
                add("Last inbound target: ${snapshot.lastInboundChatId}")
            }
            if (snapshot?.lastSenderUserId?.isNotBlank() == true) {
                add("Last sender user ID: ${snapshot.lastSenderUserId}")
            }
            if (snapshot?.lastEventType?.isNotBlank() == true) {
                add("Last event: ${snapshot.lastEventType}")
            }
            val detectedChats = gatewaySnapshots.sumOf { it.recentChats.size }
            if (detectedChats > 0) {
                add("Detected chats: $detectedChats")
            }
        }.joinToString("\n")
    }

    private fun buildStatusLines(
        runtimeSnapshots: Collection<ChannelRuntimeSnapshot>
    ): MutableList<String> {
        val lines = mutableListOf<String>()
        lines += "Adapters: ${runtimeSnapshots.size}"
        lines += "Running: ${runtimeSnapshots.count { it.running }}"
        lines += "Connected: ${runtimeSnapshots.count { it.connected }}"
        lines += "Ready: ${runtimeSnapshots.count { it.ready }}"
        val lastError = runtimeSnapshots.asSequence()
            .map { it.lastError.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        if (lastError.isNotBlank()) {
            lines += "Runtime error: $lastError"
        }
        return lines
    }
}
