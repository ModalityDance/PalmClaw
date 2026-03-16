package com.palmclaw.channels

import android.util.Log
import com.palmclaw.agent.AgentLoop
import com.palmclaw.bus.InboundMessage
import com.palmclaw.bus.MessageBus
import com.palmclaw.bus.OutboundMessage
import com.palmclaw.storage.MessageRepository
import com.palmclaw.storage.SessionRepository
import com.palmclaw.tools.MessageTool
import com.palmclaw.tools.SpawnTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GatewayOrchestrator(
    private val bus: MessageBus,
    private val agentLoop: AgentLoop,
    private val messageRepository: MessageRepository,
    private val sessionRepository: SessionRepository,
    private val sessionResolver: (message: InboundMessage) -> String?,
    private val onSessionProcessingChanged: ((sessionId: String, processing: Boolean) -> Unit)? = null,
    private val messageTool: MessageTool? = null,
    private val spawnTool: SpawnTool? = null,
    adapters: List<ChannelAdapter>
) {
    private val adaptersByKey = adapters.associateBy { it.adapterKey }
    private val adaptersByChannel = adapters.groupBy { it.channelName }
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var inboundJob: Job? = null
    private var outboundJob: Job? = null

    val adapterCount: Int
        get() = adaptersByKey.size

    fun start() {
        if (inboundJob != null || outboundJob != null) return
        adaptersByKey.values.forEach { it.start(scope, bus::publishInbound) }
        inboundJob = scope.launch { consumeInboundLoop() }
        outboundJob = scope.launch { consumeOutboundLoop() }
        Log.d(TAG, "Gateway started with adapters=${adaptersByKey.keys.joinToString(",")}")
    }

    fun stop() {
        inboundJob?.cancel()
        outboundJob?.cancel()
        inboundJob = null
        outboundJob = null
        adaptersByKey.values.forEach { it.stop() }
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        Log.d(TAG, "Gateway stopped")
    }

    private suspend fun consumeInboundLoop() {
        while (true) {
            val inbound = bus.consumeInbound()
            runCatching { processInbound(inbound) }
                .onFailure { t ->
                    Log.e(TAG, "Inbound processing failed channel=${inbound.channel}", t)
                    bus.publishOutbound(
                        OutboundMessage(
                            channel = inbound.channel,
                            chatId = inbound.chatId,
                            content = "Error: ${t.message ?: t.javaClass.simpleName}",
                            metadata = inbound.metadata
                        )
                    )
                }
        }
    }

    private suspend fun consumeOutboundLoop() {
        while (true) {
            val outbound = bus.consumeOutbound()
            val adapter = resolveOutboundAdapter(outbound)
            if (adapter == null) {
                Log.w(
                    TAG,
                    "No adapter for outbound channel=${outbound.channel} adapterKey=${outbound.metadata[KEY_ADAPTER_KEY].orEmpty()} chatId=${outbound.chatId}"
                )
                continue
            }
            runCatching { adapter.send(outbound) }
                .onFailure { t ->
                    Log.e(TAG, "Outbound delivery failed channel=${outbound.channel} adapterKey=${adapter.adapterKey}", t)
                }
        }
    }

    private suspend fun processInbound(msg: InboundMessage) {
        if (isDuplicateInbound(msg)) {
            Log.d(
                TAG,
                "Skip duplicate inbound channel=${msg.channel} chatId=${msg.chatId} adapterKey=${msg.metadata[KEY_ADAPTER_KEY].orEmpty()}"
            )
            return
        }
        val targetSessionId = sessionResolver(msg)
            ?.trim()
            ?.ifBlank { null }
        if (targetSessionId == null) {
            Log.w(
                TAG,
                "Inbound message dropped: no bound session for ${msg.channel}:${msg.chatId} adapterKey=${msg.metadata[KEY_ADAPTER_KEY].orEmpty()}"
            )
            return
        }
        onSessionProcessingChanged?.invoke(targetSessionId, true)
        try {
            messageTool?.setContext(
                channel = msg.channel,
                chatId = msg.chatId,
                messageId = msg.metadata["message_id"],
                adapterKey = msg.metadata[KEY_ADAPTER_KEY]
            )
            messageTool?.startTurn()
            spawnTool?.setContext(
                channel = msg.channel,
                chatId = msg.chatId,
                sessionKey = targetSessionId,
                adapterKey = msg.metadata[KEY_ADAPTER_KEY]
            )

            sessionRepository.ensureSessionExists(
                sessionId = targetSessionId,
                title = targetSessionId
            )
            sessionRepository.touch(targetSessionId)
            agentLoop.run(sessionId = targetSessionId, newUserText = msg.content)
            sessionRepository.touch(targetSessionId)

            if (messageTool?.sentInTurn == true) {
                Log.d(TAG, "Skip auto outbound because message tool already sent response")
                return
            }

            val latest = withContext(Dispatchers.IO) {
                messageRepository.getLatestAssistantMessage(targetSessionId)
            }
            val content = latest?.content?.takeIf { it.isNotBlank() }
                ?: "Processed."
            bus.publishOutbound(
                OutboundMessage(
                    channel = msg.channel,
                    chatId = msg.chatId,
                    content = content,
                    metadata = buildMap {
                        msg.metadata[KEY_ADAPTER_KEY]
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { put(KEY_ADAPTER_KEY, it) }
                    }
                )
            )
        } finally {
            onSessionProcessingChanged?.invoke(targetSessionId, false)
        }
    }

    private fun resolveOutboundAdapter(outbound: OutboundMessage): ChannelAdapter? {
        val candidates = adaptersByChannel[outbound.channel].orEmpty()
        if (candidates.isEmpty()) return null
        val requestedKey = outbound.metadata[KEY_ADAPTER_KEY]
            ?.trim()
            ?.ifBlank { null }
        if (requestedKey != null) {
            return adaptersByKey[requestedKey]
                ?.takeIf { it.channelName == outbound.channel }
        }
        if (candidates.size == 1) {
            return candidates.first()
        }
        val matched = candidates.filter { it.canHandleOutbound(outbound) }
        return if (matched.size == 1) matched.first() else null
    }

    companion object {
        private const val TAG = "GatewayOrchestrator"
        const val KEY_ADAPTER_KEY = "adapter_key"

        private const val DEDUP_TTL_MS = 10 * 60 * 1000L
        private const val MAX_DEDUP_KEYS = 8_000
        private val inboundDedupLock = Any()
        private val recentInboundKeys = linkedMapOf<String, Long>()
        private val stableIdMetadataKeys = listOf(
            "message_id",
            "update_id",
            "event_id",
            "client_msg_id",
            "uid",
            "ts"
        )

        private fun isDuplicateInbound(message: InboundMessage): Boolean {
            val channel = message.channel.trim().lowercase()
            val chatId = message.chatId.trim()
            if (channel.isBlank() || chatId.isBlank()) return false
            val keys = stableIdMetadataKeys
                .mapNotNull { key ->
                    message.metadata[key]
                        ?.trim()
                        ?.ifBlank { null }
                        ?.let { "$channel|$chatId|$key|$it" }
                }
            if (keys.isEmpty()) return false
            return synchronized(inboundDedupLock) {
                val now = System.currentTimeMillis()
                val cutoff = now - DEDUP_TTL_MS
                val stale = recentInboundKeys.filterValues { it < cutoff }.keys
                stale.forEach { recentInboundKeys.remove(it) }

                val duplicated = keys.any { it in recentInboundKeys }
                keys.forEach { recentInboundKeys[it] = now }

                while (recentInboundKeys.size > MAX_DEDUP_KEYS) {
                    val firstKey = recentInboundKeys.entries.firstOrNull()?.key ?: break
                    recentInboundKeys.remove(firstKey)
                }
                duplicated
            }
        }
    }
}

