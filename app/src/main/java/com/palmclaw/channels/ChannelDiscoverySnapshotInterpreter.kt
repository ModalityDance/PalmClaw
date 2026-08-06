package com.palmclaw.channels

internal data class FeishuDiscoverySnapshotResult(
    val snapshots: Map<String, FeishuGatewaySnapshot>,
    val candidates: List<FeishuChatCandidate>
)

internal object ChannelDiscoverySnapshotInterpreter {
    fun collectFeishu(
        requestedAdapterKeys: List<String>,
        currentBindingAdapterKeys: List<String>,
        snapshotsByAdapterKey: Map<String, FeishuGatewaySnapshot>
    ): FeishuDiscoverySnapshotResult {
        val keys = linkedSetOf<String>().apply {
            addAll(requestedAdapterKeys)
            addAll(currentBindingAdapterKeys)
        }
        val snapshots = keys.associateWith { adapterKey ->
            snapshotsByAdapterKey[adapterKey] ?: FeishuGatewaySnapshot()
        }
        val candidates = snapshots.values
            .asSequence()
            .flatMap { it.recentChats.asSequence() }
            .distinctBy { it.chatId }
            .toList()
        return FeishuDiscoverySnapshotResult(snapshots, candidates)
    }

    fun feishuInfo(result: FeishuDiscoverySnapshotResult): String {
        val snapshot = result.snapshots.values.firstOrNull(::hasFeishuActivity)
            ?: result.snapshots.values.firstOrNull()
            ?: return "Enter App ID and App Secret first, then detect again."
        return when {
            snapshot.lastError.isNotBlank() && !snapshot.ready ->
                "Feishu discovery connection is not ready. Check the credentials and try again."
            !snapshot.running ->
                "Feishu discovery did not start. Check the credentials and try again."
            !snapshot.ready ->
                "Feishu discovery connection is still starting. Finish confirmation and try again."
            snapshot.inboundSeen <= 0L ->
                "Feishu discovery is ready, but no inbound message arrived during the detection window. Send one @mention message and detect again."
            else ->
                "Feishu received a message, but no bindable chat was cached. Send one more @mention message and detect again."
        }
    }

    fun weComInfo(snapshot: WeComGatewaySnapshot): String = when {
        snapshot.lastError.isNotBlank() && !snapshot.ready ->
            "WeCom discovery connection is not ready. Check the credentials and try again."
        !snapshot.running ->
            "WeCom discovery did not start. Check the credentials and try again."
        !snapshot.ready ->
            "WeCom discovery connection is still starting. Finish setup and try again."
        snapshot.inboundSeen <= 0L ->
            "WeCom discovery is ready, but no inbound message arrived during the detection window. Send one message and detect again."
        else ->
            "WeCom received a message, but no bindable chat was cached. Send one more message and detect again."
    }

    fun hasFeishuActivity(snapshot: FeishuGatewaySnapshot): Boolean =
        snapshot.running ||
            snapshot.connected ||
            snapshot.ready ||
            snapshot.inboundSeen > 0L ||
            snapshot.lastError.isNotBlank() ||
            snapshot.recentChats.isNotEmpty()
}
