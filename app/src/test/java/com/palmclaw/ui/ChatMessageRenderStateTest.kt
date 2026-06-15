package com.palmclaw.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageRenderStateTest {
    @Test
    fun `session change clears transient assistant and expansion state`() {
        val state = ChatMessageRenderState()
        state.onMessagesChanged(
            messages = listOf(UiMessage(id = 1L, role = "assistant", content = "hello", createdAt = 1L)),
            onLatestUserAppended = {}
        )
        state.toggleToolExpanded(2L)

        state.onSessionChanged("session-2")

        assertNull(state.displayedAssistantText(1L))
        assertFalse(state.isToolExpanded(2L))
        assertFalse(state.showProcessingBubble(emptyList(), isGenerating = false))
    }

    @Test
    fun `appended user message triggers latest callback`() {
        val state = ChatMessageRenderState()
        var latestRequests = 0
        state.onMessagesChanged(
            messages = listOf(UiMessage(id = 1L, role = "assistant", content = "hello", createdAt = 1L)),
            onLatestUserAppended = { latestRequests += 1 }
        )

        state.onMessagesChanged(
            messages = listOf(
                UiMessage(id = 1L, role = "assistant", content = "hello", createdAt = 1L),
                UiMessage(id = 2L, role = "user", content = "next", createdAt = 2L)
            ),
            onLatestUserAppended = { latestRequests += 1 }
        )

        assertEquals(1, latestRequests)
    }

    @Test
    fun `processing bubble remains visible until assistant output appears`() {
        val state = ChatMessageRenderState()
        val user = UiMessage(id = 1L, role = "user", content = "hello", createdAt = 1L)
        state.onMessagesChanged(listOf(user), onLatestUserAppended = {})
        state.onGeneratingChanged(isGenerating = true, messages = listOf(user))

        assertTrue(state.showProcessingBubble(listOf(user), isGenerating = true))

        state.onGeneratingChanged(isGenerating = false, messages = listOf(user))

        assertTrue(state.showProcessingBubble(listOf(user), isGenerating = false))

        val assistant = UiMessage(id = 2L, role = "assistant", content = "done", createdAt = 2L)
        state.onMessagesChanged(listOf(user, assistant), onLatestUserAppended = {})
        state.onGeneratingChanged(isGenerating = false, messages = listOf(user, assistant))

        assertFalse(state.showProcessingBubble(listOf(user, assistant), isGenerating = false))
    }

    @Test
    fun `processing bubble is visible immediately after optimistic send before generation effect runs`() {
        val state = ChatMessageRenderState()
        val user = UiMessage(id = 1L, role = "user", content = "hello", createdAt = 1L)
        state.onMessagesChanged(listOf(user), onLatestUserAppended = {})

        assertTrue(state.showProcessingBubble(listOf(user), isGenerating = true))
    }

    @Test
    fun `processing bubble remains visible while tool messages append before final assistant`() {
        val state = ChatMessageRenderState()
        val user = UiMessage(id = 1L, role = "user", content = "hello", createdAt = 1L)
        val tool = UiMessage(id = 2L, role = "tool", content = "search [ok]", createdAt = 2L)
        state.onMessagesChanged(listOf(user), onLatestUserAppended = {})
        state.onGeneratingChanged(isGenerating = true, messages = listOf(user))

        state.onMessagesChanged(listOf(user, tool), onLatestUserAppended = {})
        state.onGeneratingChanged(isGenerating = true, messages = listOf(user, tool))

        assertTrue(state.showProcessingBubble(listOf(user, tool), isGenerating = true))

        state.onGeneratingChanged(isGenerating = false, messages = listOf(user, tool))

        assertTrue(state.showProcessingBubble(listOf(user, tool), isGenerating = false))
    }

    @Test
    fun `tool expansion state is keyed by message id`() {
        val state = ChatMessageRenderState()

        state.toggleToolExpanded(10L)
        state.toggleToolExpanded(11L)
        state.toggleToolExpanded(10L)

        assertFalse(state.isToolExpanded(10L))
        assertTrue(state.isToolExpanded(11L))
    }
}
