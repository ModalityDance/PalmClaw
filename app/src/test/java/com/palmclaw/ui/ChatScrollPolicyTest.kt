package com.palmclaw.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatScrollPolicyTest {
    @Test
    fun `initial session scroll can restore explicit anchor before latest`() {
        val anchor = ChatScrollAnchor(messageId = 42L, offsetFromTop = 12)

        assertEquals(
            ChatScrollDecision.Anchor(anchor),
            ChatScrollPolicy.initialSessionScroll(
                cachedAnchor = anchor,
                hasMessages = true
            )
        )
    }

    @Test
    fun `initial session scroll goes to latest when no cached anchor exists`() {
        assertEquals(
            ChatScrollDecision.Latest(animated = false),
            ChatScrollPolicy.initialSessionScroll(
                cachedAnchor = null,
                hasMessages = true
            )
        )
    }

    @Test
    fun `initial session scroll waits when there are no messages`() {
        assertEquals(
            ChatScrollDecision.None,
            ChatScrollPolicy.initialSessionScroll(
                cachedAnchor = null,
                hasMessages = false
            )
        )
    }

    @Test
    fun `tail updates keep following only when latest mode is active`() {
        assertTrue(
            ChatScrollPolicy.shouldRequestLatestForTailChange(
                hasInitialJumpToBottom = true,
                followLatest = true,
                isNearTail = false,
                isUserScrollInProgress = false,
                tailIndex = 5
            )
        )
        assertFalse(
            ChatScrollPolicy.shouldRequestLatestForTailChange(
                hasInitialJumpToBottom = true,
                followLatest = false,
                isNearTail = false,
                isUserScrollInProgress = false,
                tailIndex = 5
            )
        )
    }

    @Test
    fun `tail updates do not fight active user scroll`() {
        assertFalse(
            ChatScrollPolicy.shouldRequestLatestForTailChange(
                hasInitialJumpToBottom = true,
                followLatest = true,
                isNearTail = false,
                isUserScrollInProgress = true,
                tailIndex = 5
            )
        )
    }

    @Test
    fun `manual scroll away from tail disables follow latest`() {
        assertFalse(ChatScrollPolicy.followLatestAfterUserScroll(isNearTail = false))
        assertTrue(ChatScrollPolicy.followLatestAfterUserScroll(isNearTail = true))
    }

    @Test
    fun `tool updates do not force tail after user leaves tail until follow latest resumes`() {
        val followLatestAfterUserLeavesTail = ChatScrollPolicy.followLatestAfterUserScroll(isNearTail = false)

        assertFalse(
            ChatScrollPolicy.shouldRequestLatestForTailChange(
                hasInitialJumpToBottom = true,
                followLatest = followLatestAfterUserLeavesTail,
                isNearTail = false,
                isUserScrollInProgress = false,
                tailIndex = 8
            )
        )

        val followLatestAfterReturnToTail = ChatScrollPolicy.followLatestAfterUserScroll(isNearTail = true)

        assertTrue(
            ChatScrollPolicy.shouldRequestLatestForTailChange(
                hasInitialJumpToBottom = true,
                followLatest = followLatestAfterReturnToTail,
                isNearTail = false,
                isUserScrollInProgress = false,
                tailIndex = 9
            )
        )
    }

    @Test
    fun `older history loads only after user reaches top`() {
        assertTrue(
            ChatScrollPolicy.shouldRequestOlderHistory(
                hasInitialJumpToBottom = true,
                userScrolledSinceSessionChange = true,
                programmaticScrolling = false,
                canLoadOlderHistory = true,
                isLoadingOlderHistory = false,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = HISTORY_LOAD_EDGE_THRESHOLD_PX
            )
        )
        assertFalse(
            ChatScrollPolicy.shouldRequestOlderHistory(
                hasInitialJumpToBottom = true,
                userScrolledSinceSessionChange = false,
                programmaticScrolling = false,
                canLoadOlderHistory = true,
                isLoadingOlderHistory = false,
                firstVisibleItemIndex = 1,
                firstVisibleItemScrollOffset = 0
            )
        )
        assertFalse(
            ChatScrollPolicy.shouldRequestOlderHistory(
                hasInitialJumpToBottom = true,
                userScrolledSinceSessionChange = true,
                programmaticScrolling = false,
                canLoadOlderHistory = true,
                isLoadingOlderHistory = false,
                firstVisibleItemIndex = 0,
                firstVisibleItemScrollOffset = HISTORY_LOAD_EDGE_THRESHOLD_PX + 1
            )
        )
    }
}
