package com.palmclaw.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.sync.Mutex

internal data class ChatScrollAnchor(
    val messageId: Long,
    val offsetFromTop: Int
)

internal data class ChatHistoryRestoreRequest(
    val anchorMessageId: Long,
    val anchorScrollOffset: Int,
    val previousFirstMessageId: Long?
)

internal data class ChatScrollRequest(
    val id: Long,
    val target: ChatScrollTarget
)

internal sealed class ChatScrollTarget {
    data class Latest(val animated: Boolean) : ChatScrollTarget()
    data class Anchor(val anchor: ChatScrollAnchor) : ChatScrollTarget()
}

internal sealed class ChatScrollDecision {
    data object None : ChatScrollDecision()
    data class Latest(val animated: Boolean) : ChatScrollDecision()
    data class Anchor(val anchor: ChatScrollAnchor) : ChatScrollDecision()
}

internal object ChatScrollPolicy {
    fun initialSessionScroll(
        cachedAnchor: ChatScrollAnchor?,
        hasMessages: Boolean
    ): ChatScrollDecision {
        if (cachedAnchor != null) return ChatScrollDecision.Anchor(cachedAnchor)
        if (!hasMessages) return ChatScrollDecision.None
        return ChatScrollDecision.Latest(animated = false)
    }

    fun shouldRequestLatestForTailChange(
        hasInitialJumpToBottom: Boolean,
        followLatest: Boolean,
        isNearTail: Boolean,
        isUserScrollInProgress: Boolean,
        tailIndex: Int
    ): Boolean {
        return tailIndex >= 0 &&
            hasInitialJumpToBottom &&
            followLatest &&
            !isNearTail &&
            !isUserScrollInProgress
    }

    fun followLatestAfterUserScroll(isNearTail: Boolean): Boolean = isNearTail

    fun shouldRequestOlderHistory(
        hasInitialJumpToBottom: Boolean,
        userScrolledSinceSessionChange: Boolean,
        programmaticScrolling: Boolean,
        canLoadOlderHistory: Boolean,
        isLoadingOlderHistory: Boolean,
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int
    ): Boolean {
        return hasInitialJumpToBottom &&
            userScrolledSinceSessionChange &&
            !programmaticScrolling &&
            canLoadOlderHistory &&
            !isLoadingOlderHistory &&
            firstVisibleItemIndex == 0 &&
            firstVisibleItemScrollOffset <= HISTORY_LOAD_EDGE_THRESHOLD_PX
    }
}

internal const val HISTORY_LOAD_EDGE_THRESHOLD_PX = 96

@Composable
internal fun rememberChatScrollState(): ChatScrollState = remember { ChatScrollState() }

internal class ChatScrollState {
    var hasInitialJumpToBottom by mutableStateOf(false)
    var followLatest by mutableStateOf(true)
    var olderHistoryLoadingStartedAtMs by mutableStateOf(0L)
    var olderHistoryRestoreCompletedAtMs by mutableStateOf(0L)
    var olderHistoryLoadingObserved by mutableStateOf(false)
    var pendingHistoryRestore by mutableStateOf<ChatHistoryRestoreRequest?>(null)
    var pendingInitialTailScroll by mutableStateOf(false)
    var pendingScrollRequest by mutableStateOf<ChatScrollRequest?>(null)
    var userScrolledSinceSessionChange by mutableStateOf(false)
    var programmaticScrolling by mutableStateOf(false)
    val autoScrollMutex = Mutex()

    private var nextScrollRequestId = 0L

    fun requestScroll(target: ChatScrollTarget) {
        nextScrollRequestId += 1
        pendingScrollRequest = ChatScrollRequest(nextScrollRequestId, target)
    }

    fun requestScroll(decision: ChatScrollDecision) {
        when (decision) {
            ChatScrollDecision.None -> Unit
            is ChatScrollDecision.Anchor -> requestScroll(ChatScrollTarget.Anchor(decision.anchor))
            is ChatScrollDecision.Latest -> requestScroll(ChatScrollTarget.Latest(decision.animated))
        }
    }

    fun onSessionChanged() {
        hasInitialJumpToBottom = false
        followLatest = true
        userScrolledSinceSessionChange = false
        pendingHistoryRestore = null
        pendingInitialTailScroll = true
        pendingScrollRequest = null
        olderHistoryLoadingStartedAtMs = 0L
        olderHistoryRestoreCompletedAtMs = 0L
        olderHistoryLoadingObserved = false
    }

    fun startHistoryRestore(
        anchorMessageId: Long,
        anchorScrollOffset: Int,
        previousFirstMessageId: Long?
    ) {
        olderHistoryLoadingStartedAtMs = System.currentTimeMillis()
        olderHistoryLoadingObserved = false
        followLatest = false
        pendingScrollRequest = null
        pendingHistoryRestore = ChatHistoryRestoreRequest(
            anchorMessageId = anchorMessageId,
            anchorScrollOffset = anchorScrollOffset,
            previousFirstMessageId = previousFirstMessageId
        )
    }

    fun finishHistoryRestore() {
        pendingHistoryRestore = null
        olderHistoryLoadingStartedAtMs = 0L
        olderHistoryLoadingObserved = false
        olderHistoryRestoreCompletedAtMs = System.currentTimeMillis()
    }
}
