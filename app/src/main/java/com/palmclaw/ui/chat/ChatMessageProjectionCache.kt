package com.palmclaw.ui

import com.palmclaw.storage.entities.MessageEntity

internal data class ChatMessageProjectionResult(
    val entities: List<MessageEntity>,
    val messages: List<UiMessage>,
    val canLoadOlder: Boolean
)

internal class ChatMessageProjectionCache(
    private val initialPageSize: Int,
    private val olderPageSize: Int,
    private val projectMessages: suspend (String, List<MessageEntity>) -> List<UiMessage>
) {
    private val sessions = mutableMapOf<String, SessionProjectionState>()

    suspend fun replaceRecent(sessionId: String, recentEntities: List<MessageEntity>): ChatMessageProjectionResult {
        val state = sessions.getOrPut(sessionId) { SessionProjectionState() }
        val mergedEntities = mergeMessages(state.entities, recentEntities)
        val canLoadOlder = when {
            mergedEntities.size > recentEntities.size -> state.canLoadOlder
            else -> recentEntities.size >= initialPageSize
        }
        return updateState(sessionId, state, mergedEntities, canLoadOlder)
    }

    suspend fun prependOlder(sessionId: String, olderEntities: List<MessageEntity>): ChatMessageProjectionResult {
        val state = sessions.getOrPut(sessionId) { SessionProjectionState() }
        val mergedEntities = mergeMessages(olderEntities, state.entities)
        return updateState(
            sessionId = sessionId,
            state = state,
            entities = mergedEntities,
            canLoadOlder = olderEntities.size >= olderPageSize
        )
    }

    fun appendOptimistic(sessionId: String, optimisticMessage: UiMessage): ChatMessageProjectionResult {
        val state = sessions.getOrPut(sessionId) { SessionProjectionState() }
        state.optimisticMessages = state.optimisticMessages + optimisticMessage
        state.projectedMessages = mergeWithOptimisticMessages(state.projectedDatabaseMessages, state)
        return state.result()
    }

    fun removeOptimistic(sessionId: String, optimisticId: Long): ChatMessageProjectionResult {
        val state = sessions.getOrPut(sessionId) { SessionProjectionState() }
        state.optimisticMessages = state.optimisticMessages.filterNot { it.id == optimisticId }
        state.projectedMessages = mergeWithOptimisticMessages(state.projectedDatabaseMessages, state)
        return state.result()
    }

    fun markCannotLoadOlder(sessionId: String): ChatMessageProjectionResult {
        val state = sessions.getOrPut(sessionId) { SessionProjectionState() }
        state.canLoadOlder = false
        return state.result()
    }

    fun snapshot(sessionId: String): ChatMessageProjectionResult? = sessions[sessionId]?.result()

    fun clear(sessionId: String) {
        sessions.remove(sessionId)
    }

    fun clearAll() {
        sessions.clear()
    }

    private suspend fun updateState(
        sessionId: String,
        state: SessionProjectionState,
        entities: List<MessageEntity>,
        canLoadOlder: Boolean
    ): ChatMessageProjectionResult {
        val signatures = entities.associate { it.id to it.projectionSignature() }
        if (state.entitySignatures != signatures) {
            state.entities = entities
            state.entitySignatures = signatures
            state.projectedDatabaseMessages = projectMessages(sessionId, entities)
        }
        state.canLoadOlder = canLoadOlder
        state.projectedMessages = mergeWithOptimisticMessages(state.projectedDatabaseMessages, state)
        return state.result()
    }

    private fun mergeWithOptimisticMessages(
        databaseMessages: List<UiMessage>,
        state: SessionProjectionState
    ): List<UiMessage> {
        if (state.optimisticMessages.isEmpty()) return databaseMessages
        val unmatchedOptimistic = state.optimisticMessages.filterNot { optimistic ->
            databaseMessages.any { databaseMessage ->
                databaseMessage.confirmsOptimisticMessage(optimistic)
            }
        }
        state.optimisticMessages = unmatchedOptimistic
        if (unmatchedOptimistic.isEmpty()) return databaseMessages
        return (databaseMessages + unmatchedOptimistic).sortedWith(uiMessageOrder)
    }

    private fun mergeMessages(
        existing: List<MessageEntity>,
        incoming: List<MessageEntity>
    ): List<MessageEntity> {
        if (existing.isEmpty()) return incoming.sortedWith(messageOrder)
        if (incoming.isEmpty()) return existing.sortedWith(messageOrder)
        val byId = LinkedHashMap<Long, MessageEntity>(existing.size + incoming.size)
        existing.forEach { byId[it.id] = it }
        incoming.forEach { byId[it.id] = it }
        return byId.values.sortedWith(messageOrder)
    }

    private fun UiMessage.confirmsOptimisticMessage(optimistic: UiMessage): Boolean {
        if (id == optimistic.id) return true
        if (role != "user" || optimistic.role != "user") return false
        if (content != optimistic.content) return false
        if (attachments.fingerprint() != optimistic.attachments.fingerprint()) return false
        return createdAt >= optimistic.createdAt - OPTIMISTIC_CONFIRMATION_WINDOW_BEFORE_MS &&
            createdAt <= optimistic.createdAt + OPTIMISTIC_CONFIRMATION_WINDOW_AFTER_MS
    }

    private fun List<UiAttachment>.fingerprint(): List<String> {
        return map { attachment ->
            listOf(
                attachment.reference,
                attachment.label,
                attachment.mimeType.orEmpty(),
                attachment.sizeBytes?.toString().orEmpty()
            ).joinToString(separator = "\u001F")
        }
    }

    private fun SessionProjectionState.result(): ChatMessageProjectionResult {
        return ChatMessageProjectionResult(
            entities = entities,
            messages = projectedMessages,
            canLoadOlder = canLoadOlder
        )
    }

    private data class SessionProjectionState(
        var entities: List<MessageEntity> = emptyList(),
        var entitySignatures: Map<Long, MessageProjectionSignature> = emptyMap(),
        var projectedDatabaseMessages: List<UiMessage> = emptyList(),
        var projectedMessages: List<UiMessage> = emptyList(),
        var optimisticMessages: List<UiMessage> = emptyList(),
        var canLoadOlder: Boolean = false
    )

    private data class MessageProjectionSignature(
        val role: String,
        val content: String,
        val createdAt: Long,
        val toolCallJson: String?,
        val toolResultJson: String?,
        val attachmentsJson: String?
    )

    private fun MessageEntity.projectionSignature(): MessageProjectionSignature {
        return MessageProjectionSignature(
            role = role,
            content = content,
            createdAt = createdAt,
            toolCallJson = toolCallJson,
            toolResultJson = toolResultJson,
            attachmentsJson = attachmentsJson
        )
    }

    private companion object {
        private const val OPTIMISTIC_CONFIRMATION_WINDOW_BEFORE_MS = 30_000L
        private const val OPTIMISTIC_CONFIRMATION_WINDOW_AFTER_MS = 10 * 60_000L
        private val messageOrder = compareBy<MessageEntity> { it.createdAt }.thenBy { it.id }
        private val uiMessageOrder = compareBy<UiMessage> { it.createdAt }.thenBy { it.id }
    }
}
