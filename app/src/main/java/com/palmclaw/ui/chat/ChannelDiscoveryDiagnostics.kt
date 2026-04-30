package com.palmclaw.ui

import com.palmclaw.channels.FeishuGatewaySnapshot
import com.palmclaw.channels.WeComGatewaySnapshot
import java.util.LinkedHashSet

internal object ChannelDiscoveryDiagnostics {
    fun collectFeishuDiscoveryResult(
        requestedAdapterKeys: List<String>,
        currentBindingAdapterKeys: List<String>,
        snapshotsByAdapterKey: Map<String, FeishuGatewaySnapshot>
    ): FeishuDiscoveryResult {
        val snapshotKeys = LinkedHashSet<String>().apply {
            addAll(requestedAdapterKeys)
            addAll(currentBindingAdapterKeys)
        }
        val matchedSnapshots = snapshotKeys.associateWith { adapterKey ->
            snapshotsByAdapterKey[adapterKey] ?: FeishuGatewaySnapshot()
        }
        val snapshots = if (matchedSnapshots.values.any(::hasFeishuSnapshotActivity)) {
            matchedSnapshots
        } else {
            val activeFallbackSnapshots = snapshotsByAdapterKey
                .filterKeys { it !in snapshotKeys }
                .filterValues(::hasFeishuSnapshotActivity)
            if (activeFallbackSnapshots.size == 1) {
                linkedMapOf<String, FeishuGatewaySnapshot>().apply {
                    putAll(matchedSnapshots)
                    putAll(activeFallbackSnapshots)
                }
            } else {
                matchedSnapshots
            }
        }
        val candidates = snapshots.values
            .asSequence()
            .flatMap { it.recentChats.asSequence() }
            .distinctBy { it.chatId }
            .map {
                UiFeishuChatCandidate(
                    chatId = it.chatId,
                    title = it.title,
                    kind = it.kind,
                    note = it.note
                )
            }
            .toList()
        return FeishuDiscoveryResult(
            snapshots = snapshots,
            candidates = candidates
        )
    }

    fun hasFeishuSnapshotActivity(snapshot: FeishuGatewaySnapshot?): Boolean {
        if (snapshot == null) return false
        return snapshot.running ||
            snapshot.connected ||
            snapshot.ready ||
            snapshot.inboundSeen > 0L ||
            snapshot.inboundForwarded > 0L ||
            snapshot.outboundSent > 0L ||
            snapshot.lastError.isNotBlank() ||
            snapshot.recentChats.isNotEmpty()
    }

    fun buildFeishuDiscoveryInfo(
        requestedAdapterKeys: List<String>,
        currentBindingAdapterKeys: List<String>,
        snapshots: Map<String, FeishuGatewaySnapshot>
    ): String {
        if (requestedAdapterKeys.isEmpty()) {
            return "Save App ID and App Secret first, then detect again."
        }
        val requested = requestedAdapterKeys.asSequence()
            .mapNotNull { snapshots[it] }
            .firstOrNull(::hasFeishuSnapshotActivity)
            ?: requestedAdapterKeys.firstNotNullOfOrNull { snapshots[it] }
        val current = currentBindingAdapterKeys.asSequence()
            .filterNot { it in requestedAdapterKeys }
            .mapNotNull { snapshots[it] }
            .firstOrNull(::hasFeishuSnapshotActivity)
            ?: currentBindingAdapterKeys.asSequence()
                .filterNot { it in requestedAdapterKeys }
                .firstNotNullOfOrNull { snapshots[it] }
        val fallback = snapshots.asSequence()
            .filterNot { (key, _) -> key in requestedAdapterKeys || key in currentBindingAdapterKeys }
            .map { it.value }
            .firstOrNull(::hasFeishuSnapshotActivity)
            ?: snapshots.asSequence()
                .filterNot { (key, _) -> key in requestedAdapterKeys || key in currentBindingAdapterKeys }
                .map { it.value }
                .firstOrNull()
        if (
            currentBindingAdapterKeys.any { it !in requestedAdapterKeys } &&
            !hasFeishuSnapshotActivity(requested) &&
            hasFeishuSnapshotActivity(current)
        ) {
            return "These fields do not match the running Feishu connection. Save first, then detect again."
        }
        val snapshot = listOfNotNull(requested, current, fallback)
            .firstOrNull(::hasFeishuSnapshotActivity)
            ?: requested
            ?: current
            ?: fallback
        if (snapshot == null) {
            return "Save once to start Feishu long connection, then send one message and detect again."
        }
        if (snapshot.lastError.isNotBlank()) {
            return "Feishu long connection is not ready yet."
        }
        if (!snapshot.running) {
            return "Feishu adapter is not running yet. Save once and keep PalmClaw open."
        }
        if (!snapshot.ready) {
            return "Feishu long connection is starting. Finish confirmation, then detect again."
        }
        if (snapshot.inboundSeen <= 0L) {
            return "Feishu Long Connection is ready, but PalmClaw has not received any inbound Feishu message yet. Open the app in Feishu and send one @mention message first. Group tests also need im:message.group_at_msg:readonly."
        }
        return "Feishu messages have reached PalmClaw, but no bindable chat has been cached yet. Send one more @mention message, then try Detect Chats again."
    }

    fun hasWeComSnapshotActivity(snapshot: WeComGatewaySnapshot?): Boolean {
        if (snapshot == null) return false
        return snapshot.running ||
            snapshot.connected ||
            snapshot.ready ||
            snapshot.inboundSeen > 0L ||
            snapshot.inboundForwarded > 0L ||
            snapshot.outboundSent > 0L ||
            snapshot.lastError.isNotBlank() ||
            snapshot.recentChats.isNotEmpty()
    }

    fun buildWeComDiscoveryInfo(snapshot: WeComGatewaySnapshot?): String {
        if (snapshot == null) {
            return "Save Bot ID and Secret first, then detect again."
        }
        if (snapshot.lastError.isNotBlank()) {
            return "WeCom connection is not ready yet."
        }
        if (!snapshot.running) {
            return "WeCom adapter is not running yet. Save once and keep PalmClaw open."
        }
        if (!snapshot.ready) {
            return "WeCom connection is starting. Finish setup, then detect again."
        }
        if (snapshot.inboundSeen <= 0L) {
            return "WeCom connection is ready, but PalmClaw has not received any inbound message yet. Send one message from WeCom first."
        }
        return "WeCom messages have reached PalmClaw, but no bindable chat has been cached yet. Send one more message, then try Detect Chats again."
    }
}

internal data class FeishuDiscoveryResult(
    val snapshots: Map<String, FeishuGatewaySnapshot>,
    val candidates: List<UiFeishuChatCandidate>
)
