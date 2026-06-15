package com.palmclaw.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
internal fun rememberChatMessageRenderState(): ChatMessageRenderState = remember { ChatMessageRenderState() }

internal class ChatMessageRenderState {
    private val displayedAssistantTextById = mutableStateMapOf<Long, String>()
    private val expandedToolMessages = mutableStateMapOf<Long, Boolean>()
    private var initializedMessages by mutableStateOf(false)
    private var trackedMessageIds by mutableStateOf<List<Long>>(emptyList())
    private var generationAnchorMessageId by mutableStateOf<Long?>(null)
    private var keepProcessingBubbleVisible by mutableStateOf(false)

    fun onSessionChanged(sessionId: String) {
        initializedMessages = false
        trackedMessageIds = emptyList()
        displayedAssistantTextById.clear()
        expandedToolMessages.clear()
        generationAnchorMessageId = null
        keepProcessingBubbleVisible = false
    }

    fun onMessagesChanged(
        messages: List<UiMessage>,
        onLatestUserAppended: () -> Unit
    ) {
        if (!initializedMessages) {
            if (messages.isEmpty()) return
            resetDisplayedAssistantText(messages)
            trackedMessageIds = messages.map { it.id }
            initializedMessages = true
            return
        }

        val previousIds = trackedMessageIds
        val currentIds = messages.map { it.id }
        val sharesStablePrefix = previousIds.size <= currentIds.size &&
            previousIds.indices.all { index -> previousIds[index] == currentIds[index] }

        if (!sharesStablePrefix) {
            resetDisplayedAssistantText(messages)
            trackedMessageIds = currentIds
            return
        }

        if (currentIds.size > previousIds.size) {
            val appended = messages.subList(previousIds.size, messages.size)
            appended.forEach { message ->
                if (message.role == "assistant") {
                    displayedAssistantTextById[message.id] = message.content
                }
            }
            if (appended.lastOrNull()?.role == "user") {
                onLatestUserAppended()
            }
        }

        messages.lastOrNull { it.role == "assistant" }?.let { latestAssistant ->
            if (displayedAssistantTextById[latestAssistant.id] != latestAssistant.content) {
                displayedAssistantTextById[latestAssistant.id] = latestAssistant.content
            }
        }
        trackedMessageIds = currentIds
    }

    fun onGeneratingChanged(isGenerating: Boolean, messages: List<UiMessage>) {
        if (isGenerating) {
            val lastMessage = messages.lastOrNull()
            val latestUserLikeMessageId = messages
                .lastOrNull { message -> message.role != "assistant" && message.role != "tool" }
                ?.id
            when {
                generationAnchorMessageId == null -> {
                    generationAnchorMessageId = latestUserLikeMessageId ?: lastMessage?.id
                }
                lastMessage != null && lastMessage.role != "assistant" && lastMessage.role != "tool" -> {
                    generationAnchorMessageId = lastMessage.id
                }
            }
            keepProcessingBubbleVisible = true
            return
        }

        if (hasAssistantOutputAfterAnchor(messages)) {
            keepProcessingBubbleVisible = false
        }
    }

    fun showProcessingBubble(messages: List<UiMessage>, isGenerating: Boolean): Boolean {
        val anchor = generationAnchorMessageId ?: when {
            isGenerating -> inferGenerationAnchor(messages)
            else -> null
        }
        return (isGenerating || keepProcessingBubbleVisible) && !hasAssistantOutputAfterAnchor(messages, anchor)
    }

    fun displayedAssistantText(messageId: Long): String? = displayedAssistantTextById[messageId]

    fun isToolExpanded(messageId: Long): Boolean = expandedToolMessages[messageId] == true

    fun toggleToolExpanded(messageId: Long) {
        expandedToolMessages[messageId] = !isToolExpanded(messageId)
    }

    private fun resetDisplayedAssistantText(messages: List<UiMessage>) {
        displayedAssistantTextById.clear()
        messages.forEach { message ->
            if (message.role == "assistant") {
                displayedAssistantTextById[message.id] = message.content
            }
        }
    }

    private fun hasAssistantOutputAfterAnchor(messages: List<UiMessage>): Boolean {
        return hasAssistantOutputAfterAnchor(messages, generationAnchorMessageId)
    }

    private fun hasAssistantOutputAfterAnchor(messages: List<UiMessage>, anchorMessageId: Long?): Boolean {
        val anchor = anchorMessageId ?: return false
        return messages.any { message ->
            message.id > anchor &&
                message.role == "assistant" &&
                (displayedAssistantTextById[message.id] ?: message.content).isNotBlank()
        }
    }

    private fun inferGenerationAnchor(messages: List<UiMessage>): Long? {
        val lastMessage = messages.lastOrNull()
        return messages
            .lastOrNull { message -> message.role != "assistant" && message.role != "tool" }
            ?.id
            ?: lastMessage?.id
    }
}
