package com.palmclaw.channels

import android.util.Log
import com.palmclaw.agent.AgentLoop
import com.palmclaw.attachments.AttachmentTransferService
import com.palmclaw.config.AppSession
import com.palmclaw.bus.InboundMessage
import com.palmclaw.bus.MessageAttachmentJsonCodec
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GatewayOrchestrator(
    private val bus: MessageBus,
    private val agentLoop: AgentLoop,
    private val messageRepository: MessageRepository,
    private val sessionRepository: SessionRepository,
    private val attachmentTransferService: AttachmentTransferService,
    private val sessionResolver: (message: InboundMessage) -> String?,
    private val onSessionProcessingChanged: ((sessionId: String, processing: Boolean) -> Unit)? = null,
    private val messageTool: MessageTool? = null,
    private val spawnTool: SpawnTool? = null,
    adapters: List<ChannelAdapter>
) {
    @Volatile
    private var adaptersByKey = adapters.associateBy { it.adapterKey }
    @Volatile
    private var adaptersByChannel = adapters.groupBy { it.channelName }
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var inboundJob: Job? = null
    private var outboundJob: Job? = null
    private val sessionWorkerLock = Any()
    private val sessionInboundChannels = mutableMapOf<String, Channel<QueuedInbound>>()
    private val sessionWorkerJobs = mutableMapOf<String, Job>()
    private val adapterLock = Any()

    val adapterCount: Int
        get() = adaptersByKey.size

    fun start() {
        synchronized(adapterLock) {
            if (inboundJob != null || outboundJob != null) return
            adaptersByKey.values.forEach { it.start(scope, bus::publishInbound) }
            inboundJob = scope.launch { consumeInboundLoop() }
            outboundJob = scope.launch { consumeOutboundLoop() }
            Log.d(TAG, "Gateway started with adapters=${adaptersByKey.keys.joinToString(",")}")
        }
    }

    fun reconfigure(adapters: List<ChannelAdapter>) {
        synchronized(adapterLock) {
            val previousByKey = adaptersByKey
            val nextByKey = linkedMapOf<String, ChannelAdapter>()
            adapters.forEach { candidate ->
                val existing = previousByKey[candidate.adapterKey]
                val resolved = if (existing?.reconfigureFrom(candidate) == true) {
                    existing
                } else {
                    candidate
                }
                nextByKey[resolved.adapterKey] = resolved
            }
            val replacedOrRemoved = previousByKey
                .filter { (key, existing) ->
                    val next = nextByKey[key]
                    next == null || next !== existing
                }
                .values
                .distinct()
            replacedOrRemoved.forEach { it.stop() }
            val nextByChannel = nextByKey.values.groupBy { it.channelName }
            adaptersByKey = nextByKey
            adaptersByChannel = nextByChannel
            if (inboundJob != null || outboundJob != null) {
                nextByKey.values
                    .filter { adapter -> previousByKey[adapter.adapterKey] !== adapter }
                    .forEach { it.start(scope, bus::publishInbound) }
            }
            Log.d(
                TAG,
                "Gateway reconfigured adapters=${adaptersByKey.keys.joinToString(",")} reused=${
                    nextByKey.keys.count { key -> previousByKey[key] === nextByKey[key] }
                }"
            )
        }
    }

    fun stop() {
        synchronized(adapterLock) {
            inboundJob?.cancel()
            outboundJob?.cancel()
            inboundJob = null
            outboundJob = null
            closeSessionWorkers()
            adaptersByKey.values.distinct().forEach { it.stop() }
            scope.cancel()
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            Log.d(TAG, "Gateway stopped")
        }
    }

    private suspend fun consumeInboundLoop() {
        while (true) {
            val inbound = bus.consumeInbound()
            runCatching { enqueueInbound(inbound) }
                .onFailure { t ->
                    Log.e(TAG, "Inbound dispatch failed channel=${inbound.channel}", t)
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

    private suspend fun enqueueInbound(msg: InboundMessage) {
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

        if (targetSessionId == AppSession.LOCAL_SESSION_ID) {
            sessionRepository.ensureSessionExists(AppSession.LOCAL_SESSION_ID, AppSession.LOCAL_SESSION_TITLE)
        } else if (sessionRepository.getSession(targetSessionId) == null) {
            Log.w(
                TAG,
                "Inbound message dropped: target session missing sessionId=$targetSessionId channel=${msg.channel} chatId=${msg.chatId}"
            )
            return
        }
        sessionRepository.touch(targetSessionId)
        val targetTitle = sessionRepository.getSession(targetSessionId)?.title
            ?: if (targetSessionId == AppSession.LOCAL_SESSION_ID) {
                AppSession.LOCAL_SESSION_TITLE
            } else {
                targetSessionId
            }
        val inboundAttachments = attachmentTransferService.importInboundAttachments(
            sessionId = targetSessionId,
            sessionTitle = targetTitle,
            channel = msg.channel,
            messageKey = msg.metadata["message_id"]?.ifBlank { null } ?: msg.timestamp.toString(),
            attachments = msg.normalizedAttachments
        )
        messageRepository.appendMessage(
            sessionId = targetSessionId,
            role = "user",
            content = msg.content,
            attachments = inboundAttachments
        )
        sessionChannel(targetSessionId).send(
            QueuedInbound(
                inbound = msg,
                sessionId = targetSessionId
            )
        )
    }

    private suspend fun processInbound(work: QueuedInbound) {
        val msg = work.inbound
        val targetSessionId = work.sessionId
        val adapter = resolveInboundAdapter(msg)
        val processingHandle = runCatching { adapter?.beginInboundProcessing(msg) }
            .onFailure { t ->
                Log.w(
                    TAG,
                    "Inbound processing feedback start failed channel=${msg.channel} adapterKey=${msg.metadata[KEY_ADAPTER_KEY].orEmpty()}",
                    t
                )
            }
            .getOrNull()
        onSessionProcessingChanged?.invoke(targetSessionId, true)
        try {
            val beforeLatestAssistantId = withContext(Dispatchers.IO) {
                messageRepository.getLatestAssistantMessage(targetSessionId)?.id ?: 0L
            }
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
            spawnTool?.startTurn()
            try {
                sessionRepository.touch(targetSessionId)
                agentLoop.run(
                    sessionId = targetSessionId,
                    newUserText = msg.content,
                    appendInputMessage = false
                )
                sessionRepository.touch(targetSessionId)

                if (messageTool?.wasSentInCurrentTurn() == true) {
                    Log.d(TAG, "Skip auto outbound because message tool already sent response")
                    return
                }

                val latestAssistant = withContext(Dispatchers.IO) {
                    messageRepository.getLatestAssistantMessage(targetSessionId)
                    ?.takeIf { it.id > beforeLatestAssistantId }
                }
                val content = latestAssistant
                    ?.takeIf { it.content.isNotBlank() || !it.attachmentsJson.isNullOrBlank() }
                    ?.content
                    ?: "Processed."
                val attachments = MessageAttachmentJsonCodec.decode(
                    latestAssistant?.attachmentsJson
                )
                bus.publishOutbound(
                    OutboundMessage(
                        channel = msg.channel,
                        chatId = msg.chatId,
                        content = content,
                        attachments = attachments,
                        metadata = buildMap {
                            msg.metadata[KEY_ADAPTER_KEY]
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                                ?.let { put(KEY_ADAPTER_KEY, it) }
                        }
                    )
                )
            } finally {
                messageTool?.finishTurn()
                spawnTool?.finishTurn()
            }
        } finally {
            runCatching { adapter?.endInboundProcessing(msg, processingHandle) }
                .onFailure { t ->
                    Log.w(
                        TAG,
                        "Inbound processing feedback finish failed channel=${msg.channel} adapterKey=${msg.metadata[KEY_ADAPTER_KEY].orEmpty()}",
                        t
                    )
                }
            onSessionProcessingChanged?.invoke(targetSessionId, false)
        }
    }

    private fun sessionChannel(sessionId: String): Channel<QueuedInbound> {
        synchronized(sessionWorkerLock) {
            sessionInboundChannels[sessionId]?.let { return it }
            val channel = Channel<QueuedInbound>(capacity = Channel.UNLIMITED)
            val job = scope.launch {
                while (true) {
                    val work = channel.receiveCatching().getOrNull() ?: break
                    runCatching { processInbound(work) }
                        .onFailure { t ->
                            Log.e(
                                TAG,
                                "Inbound processing failed channel=${work.inbound.channel} session=$sessionId",
                                t
                            )
                            bus.publishOutbound(
                                OutboundMessage(
                                    channel = work.inbound.channel,
                                    chatId = work.inbound.chatId,
                                    content = "Error: ${t.message ?: t.javaClass.simpleName}",
                                    metadata = work.inbound.metadata
                                )
                            )
                        }
                }
            }
            job.invokeOnCompletion {
                synchronized(sessionWorkerLock) {
                    if (sessionInboundChannels[sessionId] === channel) {
                        sessionInboundChannels.remove(sessionId)
                    }
                    if (sessionWorkerJobs[sessionId] === job) {
                        sessionWorkerJobs.remove(sessionId)
                    }
                }
            }
            sessionInboundChannels[sessionId] = channel
            sessionWorkerJobs[sessionId] = job
            return channel
        }
    }

    private fun closeSessionWorkers() {
        val channels: List<Channel<QueuedInbound>>
        val jobs: List<Job>
        synchronized(sessionWorkerLock) {
            channels = sessionInboundChannels.values.toList()
            jobs = sessionWorkerJobs.values.toList()
            sessionInboundChannels.clear()
            sessionWorkerJobs.clear()
        }
        channels.forEach { it.close() }
        jobs.forEach { it.cancel() }
    }

    private fun resolveOutboundAdapter(outbound: OutboundMessage): ChannelAdapter? {
        return synchronized(adapterLock) {
            val candidates = adaptersByChannel[outbound.channel].orEmpty()
            if (candidates.isEmpty()) return@synchronized null
            val requestedKey = outbound.metadata[KEY_ADAPTER_KEY]
                ?.trim()
                ?.ifBlank { null }
            if (requestedKey != null) {
                return@synchronized adaptersByKey[requestedKey]
                    ?.takeIf { it.channelName == outbound.channel }
            }
            if (candidates.size == 1) {
                return@synchronized candidates.first()
            }
            val matched = candidates.filter { it.canHandleOutbound(outbound) }
            if (matched.size == 1) matched.first() else null
        }
    }

    fun resolveOutboundAttachmentCapability(outbound: OutboundMessage): ChannelAttachmentCapability? {
        return resolveOutboundAdapter(outbound)?.attachmentCapability
    }

    private fun resolveInboundAdapter(inbound: InboundMessage): ChannelAdapter? {
        return synchronized(adapterLock) {
            val candidates = adaptersByChannel[inbound.channel].orEmpty()
            if (candidates.isEmpty()) return@synchronized null
            val requestedKey = inbound.metadata[KEY_ADAPTER_KEY]
                ?.trim()
                ?.ifBlank { null }
            if (requestedKey != null) {
                return@synchronized adaptersByKey[requestedKey]
                    ?.takeIf { it.channelName == inbound.channel }
            }
            if (candidates.size == 1) candidates.first() else null
        }
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

    private data class QueuedInbound(
        val inbound: InboundMessage,
        val sessionId: String
    )
}
