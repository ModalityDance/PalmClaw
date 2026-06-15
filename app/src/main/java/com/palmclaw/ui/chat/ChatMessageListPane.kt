package com.palmclaw.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock

@Composable
internal fun ChatMessageListPane(
    state: ChatTimelineState,
    identity: IdentityDisplayState,
    useChinese: Boolean,
    bottomOverlayHeightPx: Int,
    previewAudioRef: String?,
    previewAudioDurationMs: Int,
    previewAudioPositionMs: Int,
    onOpenAttachment: (UiAttachment) -> Unit,
    onToggleAudioPreview: (UiAttachment) -> Unit,
    onLoadOlderMessages: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scrollState = rememberChatScrollState()
    val renderState = rememberChatMessageRenderState()

    val visibleMessages = state.messages
    val canLoadOlderHistory = state.canLoadOlderMessages
    val isLoadingOlderHistory = state.messagesLoadingOlder
    val headerItemCount = 0
    val showProcessingBubble = renderState.showProcessingBubble(visibleMessages, state.isGenerating)
    val showMessagesLoading = state.messagesLoading && visibleMessages.isEmpty()
    val extraTailItemCount = if (showProcessingBubble) 1 else 0
    val loadingItemCount = if (showMessagesLoading) 1 else 0
    val totalItems = visibleMessages.size + headerItemCount + loadingItemCount + extraTailItemCount
    val tailIndex = if (totalItems <= 0) -1 else totalItems - 1
    val scrollIndicator by remember(
        totalItems,
        listState.firstVisibleItemIndex,
        listState.firstVisibleItemScrollOffset
    ) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            val visibleCount = visibleItems.size
            val totalCount = layoutInfo.totalItemsCount
            val viewportHeight = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset)
                .coerceAtLeast(0)
            if (totalCount <= 0 || visibleCount <= 0 || totalCount <= visibleCount || viewportHeight <= 0) {
                null
            } else {
                val firstVisible = visibleItems.first()
                val lastVisible = visibleItems.last()
                val visibleSpan = ((lastVisible.offset + lastVisible.size) - firstVisible.offset)
                    .coerceAtLeast(viewportHeight)
                val averageItemExtent = (visibleSpan.toFloat() / visibleCount.toFloat())
                    .coerceAtLeast(1f)
                val estimatedContentHeight = (averageItemExtent * totalCount.toFloat())
                    .coerceAtLeast(viewportHeight.toFloat())
                val consumedBeforeFirstVisible = firstVisible.index * averageItemExtent +
                    (layoutInfo.viewportStartOffset - firstVisible.offset).toFloat()
                val maxScrollableDistance = (estimatedContentHeight - viewportHeight.toFloat())
                    .coerceAtLeast(1f)
                ScrollIndicatorUi(
                    thumbFraction = (viewportHeight.toFloat() / estimatedContentHeight).coerceIn(0.12f, 0.42f),
                    progress = (consumedBeforeFirstVisible / maxScrollableDistance).coerceIn(0f, 1f)
                )
            }
        }
    }
    val density = LocalDensity.current
    val chatInputBarClearance = with(density) {
        val fallback = CHAT_INPUT_BAR_CLEARANCE.roundToPx()
        val outerVerticalPadding = 8.dp.roundToPx()
        val overlayHeight = maxOf(bottomOverlayHeightPx + outerVerticalPadding, fallback)
        overlayHeight.toDp().coerceAtLeast(72.dp) + CHAT_TAIL_VISIBLE_GAP + 8.dp
    }
    val chatInputBarClearancePx = with(density) { chatInputBarClearance.roundToPx() }
    val isNearTail by remember(
        visibleMessages.size,
        headerItemCount,
        showProcessingBubble,
        listState.firstVisibleItemIndex,
        listState.firstVisibleItemScrollOffset,
        chatInputBarClearancePx
    ) {
        derivedStateOf {
            if (totalItems <= 0) return@derivedStateOf true
            val tailItem = listState.layoutInfo.visibleItemsInfo.lastOrNull { it.index == tailIndex }
                ?: return@derivedStateOf false
            val desiredBottom = listState.layoutInfo.viewportEndOffset - chatInputBarClearancePx
            (tailItem.offset + tailItem.size) <= (desiredBottom + 1)
        }
    }
    suspend fun moveToLatest(animated: Boolean) {
        if (tailIndex < 0) return
        scrollState.autoScrollMutex.withLock {
            scrollState.programmaticScrolling = true
            try {
                val longDistance = abs(listState.firstVisibleItemIndex - tailIndex) > 20
                if (animated && !longDistance) {
                    listState.animateScrollToItem(tailIndex)
                } else {
                    listState.scrollToItem(tailIndex)
                }
                repeat(3) {
                    val tailItem = listState.layoutInfo.visibleItemsInfo
                        .lastOrNull { it.index == tailIndex }
                        ?: return@repeat
                    val desiredBottom = listState.layoutInfo.viewportEndOffset - chatInputBarClearancePx
                    val remaining = (tailItem.offset + tailItem.size) - desiredBottom
                    if (remaining <= 1) return@repeat
                    listState.scrollBy(remaining.toFloat())
                }
            } finally {
                scrollState.programmaticScrolling = false
            }
        }
    }

    LaunchedEffect(
        state.isGenerating,
        visibleMessages.lastOrNull()?.id,
        visibleMessages.lastOrNull()?.role,
        visibleMessages.lastOrNull()?.content
    ) {
        renderState.onGeneratingChanged(
            isGenerating = state.isGenerating,
            messages = visibleMessages
        )
    }

    LaunchedEffect(
        listState.isScrollInProgress,
        listState.firstVisibleItemIndex,
        listState.firstVisibleItemScrollOffset,
        scrollState.programmaticScrolling
    ) {
        if (scrollState.programmaticScrolling) {
            if (isNearTail) scrollState.followLatest = true
            return@LaunchedEffect
        }
        if (listState.isScrollInProgress) {
            scrollState.userScrolledSinceSessionChange = true
            scrollState.followLatest = ChatScrollPolicy.followLatestAfterUserScroll(isNearTail)
        }
    }
    val restoringOlderHistory = isLoadingOlderHistory || scrollState.pendingHistoryRestore != null
    LaunchedEffect(bottomOverlayHeightPx, tailIndex, isNearTail, restoringOlderHistory) {
        if (restoringOlderHistory) return@LaunchedEffect
        if (
            ChatScrollPolicy.shouldRequestLatestForTailChange(
                hasInitialJumpToBottom = scrollState.hasInitialJumpToBottom,
                followLatest = scrollState.followLatest,
                isNearTail = isNearTail,
                isUserScrollInProgress = listState.isScrollInProgress,
                tailIndex = tailIndex
            )
        ) {
            scrollState.requestScroll(ChatScrollTarget.Latest(animated = false))
        }
    }
    LaunchedEffect(visibleMessages) {
        renderState.onMessagesChanged(
            messages = visibleMessages,
            onLatestUserAppended = {
                scrollState.followLatest = true
                scrollState.requestScroll(ChatScrollTarget.Latest(animated = false))
            }
        )
    }
    LaunchedEffect(state.currentSessionId) {
        renderState.onSessionChanged(state.currentSessionId)
        scrollState.onSessionChanged()
    }
    LaunchedEffect(
        scrollState.pendingInitialTailScroll,
        visibleMessages.size,
        visibleMessages.lastOrNull()?.id,
        tailIndex
    ) {
        val decision = if (scrollState.pendingInitialTailScroll) {
            ChatScrollPolicy.initialSessionScroll(
                cachedAnchor = null,
                hasMessages = visibleMessages.isNotEmpty()
            )
        } else {
            ChatScrollDecision.None
        }
        if (decision == ChatScrollDecision.None || tailIndex < 0) return@LaunchedEffect
        scrollState.requestScroll(decision)
        scrollState.pendingInitialTailScroll = false
        scrollState.hasInitialJumpToBottom = true
    }
    LaunchedEffect(
        scrollState.pendingHistoryRestore,
        visibleMessages.size,
        visibleMessages.firstOrNull()?.id,
        isLoadingOlderHistory,
        canLoadOlderHistory
    ) {
        val restore = scrollState.pendingHistoryRestore ?: return@LaunchedEffect
        if (isLoadingOlderHistory) {
            scrollState.olderHistoryLoadingObserved = true
            return@LaunchedEffect
        }
        val loadedOrFinished =
            scrollState.olderHistoryLoadingObserved ||
                restore.previousFirstMessageId == null ||
                visibleMessages.firstOrNull()?.id != restore.previousFirstMessageId ||
                !canLoadOlderHistory
        if (!loadedOrFinished) {
            return@LaunchedEffect
        }
        val localIndex = visibleMessages.indexOfFirst { it.id == restore.anchorMessageId }
        if (localIndex >= 0) {
            scrollState.autoScrollMutex.withLock {
                scrollState.programmaticScrolling = true
                try {
                    withFrameNanos { }
                    listState.scrollToItem(
                        index = localIndex.coerceAtLeast(0),
                        scrollOffset = restore.anchorScrollOffset.coerceAtLeast(0)
                    )
                } finally {
                    scrollState.programmaticScrolling = false
                }
            }
        }
        val elapsed = System.currentTimeMillis() - scrollState.olderHistoryLoadingStartedAtMs
        val remain = HISTORY_LOADING_MIN_VISIBLE_MS - elapsed
        if (remain > 0) delay(remain)
        scrollState.finishHistoryRestore()
    }
    LaunchedEffect(
        scrollState.hasInitialJumpToBottom,
        scrollState.userScrolledSinceSessionChange,
        listState.firstVisibleItemIndex,
        listState.firstVisibleItemScrollOffset,
        canLoadOlderHistory,
        isLoadingOlderHistory,
        scrollState.pendingHistoryRestore,
        visibleMessages.size,
        visibleMessages.firstOrNull()?.id
    ) {
        if (scrollState.pendingHistoryRestore != null) return@LaunchedEffect
        if (
            System.currentTimeMillis() - scrollState.olderHistoryRestoreCompletedAtMs <
            HISTORY_RELOAD_COOLDOWN_MS
        ) return@LaunchedEffect
        if (
            !ChatScrollPolicy.shouldRequestOlderHistory(
                hasInitialJumpToBottom = scrollState.hasInitialJumpToBottom,
                userScrolledSinceSessionChange = scrollState.userScrolledSinceSessionChange,
                programmaticScrolling = scrollState.programmaticScrolling,
                canLoadOlderHistory = canLoadOlderHistory,
                isLoadingOlderHistory = isLoadingOlderHistory,
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset
            )
        ) return@LaunchedEffect

        delay(HISTORY_LOAD_TRIGGER_DELAY_MS)
        if (scrollState.pendingHistoryRestore != null) return@LaunchedEffect
        if (
            System.currentTimeMillis() - scrollState.olderHistoryRestoreCompletedAtMs <
            HISTORY_RELOAD_COOLDOWN_MS
        ) return@LaunchedEffect
        if (
            !ChatScrollPolicy.shouldRequestOlderHistory(
                hasInitialJumpToBottom = scrollState.hasInitialJumpToBottom,
                userScrolledSinceSessionChange = scrollState.userScrolledSinceSessionChange,
                programmaticScrolling = scrollState.programmaticScrolling,
                canLoadOlderHistory = canLoadOlderHistory,
                isLoadingOlderHistory = isLoadingOlderHistory,
                firstVisibleItemIndex = listState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset
            )
        ) return@LaunchedEffect

        val firstVisibleInfo = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index >= headerItemCount }
        val anchorMessageId = firstVisibleInfo?.let { info ->
            visibleMessages.getOrNull(info.index - headerItemCount)?.id
        } ?: visibleMessages.firstOrNull()?.id ?: return@LaunchedEffect
        val anchorScrollOffset = when {
            firstVisibleInfo?.index == listState.firstVisibleItemIndex -> listState.firstVisibleItemScrollOffset
            firstVisibleInfo != null -> (listState.layoutInfo.viewportStartOffset - firstVisibleInfo.offset)
                .coerceAtLeast(0)
            else -> 0
        }

        scrollState.startHistoryRestore(
            anchorMessageId = anchorMessageId,
            anchorScrollOffset = anchorScrollOffset,
            previousFirstMessageId = visibleMessages.firstOrNull()?.id
        )
        onLoadOlderMessages()
    }
    LaunchedEffect(
        scrollState.hasInitialJumpToBottom,
        visibleMessages.lastOrNull()?.id,
        showProcessingBubble,
        scrollState.followLatest,
        isNearTail,
        chatInputBarClearancePx,
        restoringOlderHistory
    ) {
        if (restoringOlderHistory) return@LaunchedEffect
        if (
            ChatScrollPolicy.shouldRequestLatestForTailChange(
                hasInitialJumpToBottom = scrollState.hasInitialJumpToBottom,
                followLatest = scrollState.followLatest,
                isNearTail = isNearTail,
                isUserScrollInProgress = listState.isScrollInProgress,
                tailIndex = tailIndex
            )
        ) {
            scrollState.requestScroll(ChatScrollTarget.Latest(animated = !showProcessingBubble))
        }
    }
    LaunchedEffect(
        scrollState.pendingScrollRequest,
        tailIndex,
        visibleMessages.size,
        visibleMessages.firstOrNull()?.id,
        visibleMessages.lastOrNull()?.id,
        headerItemCount,
        chatInputBarClearancePx
    ) {
        val request = scrollState.pendingScrollRequest ?: return@LaunchedEffect
        when (val target = request.target) {
            is ChatScrollTarget.Anchor -> {
                val localIndex = visibleMessages.indexOfFirst { it.id == target.anchor.messageId }
                if (localIndex >= 0) {
                    scrollState.autoScrollMutex.withLock {
                        scrollState.programmaticScrolling = true
                        try {
                            listState.scrollToItem(
                                index = (headerItemCount + localIndex).coerceAtLeast(0),
                                scrollOffset = -target.anchor.offsetFromTop
                            )
                        } finally {
                            scrollState.programmaticScrolling = false
                        }
                    }
                }
            }
            is ChatScrollTarget.Latest -> moveToLatest(animated = target.animated)
        }
        if (scrollState.pendingScrollRequest?.id == request.id) {
            scrollState.pendingScrollRequest = null
        }
    }

    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                start = 3.dp,
                top = CHAT_HISTORY_EDGE_PADDING,
                end = 3.dp,
                bottom = chatInputBarClearance
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showMessagesLoading) {
                item(key = "messages-loading") {
                    MessagesLoadingRow()
                }
            }

            items(
                items = visibleMessages,
                key = { it.id },
                contentType = { message ->
                    when (message.role) {
                        "user" -> "user"
                        "tool" -> "tool"
                        "system" -> "system"
                        else -> "assistant"
                    }
                }
            ) { message ->
                val messageExpanded = message.role == "tool" && renderState.isToolExpanded(message.id)
                ChatMessageBubble(
                    message = message,
                    identity = identity,
                    useChinese = useChinese,
                    displayedAssistantText = renderState.displayedAssistantText(message.id),
                    expanded = messageExpanded,
                    onToggleExpanded = {
                        renderState.toggleToolExpanded(message.id)
                    },
                    previewAudioRef = previewAudioRef,
                    previewAudioDurationMs = previewAudioDurationMs,
                    previewAudioPositionMs = previewAudioPositionMs,
                    onOpenAttachment = onOpenAttachment,
                    onToggleAudioPreview = onToggleAudioPreview
                )
            }

            if (showProcessingBubble) {
                item(key = "processing-indicator") {
                    ProcessingBubble()
                }
            }
        }

        if (isLoadingOlderHistory) {
            HistoryStatusRow(
                isLoading = true,
                canLoadOlderHistory = canLoadOlderHistory,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 3.dp)
            )
        }

        ChatScrollOverlay(
            listState = listState,
            scrollIndicator = scrollIndicator,
            showScrollToLatestButton = totalItems > 0 && !isNearTail,
            chatInputBarClearance = chatInputBarClearance,
            onScrollToLatest = {
                if (tailIndex >= 0) {
                    scrollState.followLatest = true
                    scrollState.requestScroll(ChatScrollTarget.Latest(animated = true))
                }
            }
        )
    }
}

@Composable
private fun HistoryStatusRow(
    isLoading: Boolean,
    canLoadOlderHistory: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(top = 8.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(14.dp)
                    .padding(end = 6.dp),
                strokeWidth = 2.dp
            )
            Text(
                text = uiLabel("Loading chat..."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = if (canLoadOlderHistory) uiLabel("Chat") else uiLabel("Beginning of chat"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MessagesLoadingRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 28.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(16.dp)
                .padding(end = 6.dp),
            strokeWidth = 2.dp
        )
        Text(
            text = uiLabel("Loading chat..."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ChatMessageBubble(
    message: UiMessage,
    identity: IdentityDisplayState,
    useChinese: Boolean,
    displayedAssistantText: String?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    previewAudioRef: String?,
    previewAudioDurationMs: Int,
    previewAudioPositionMs: Int,
    onOpenAttachment: (UiAttachment) -> Unit,
    onToggleAudioPreview: (UiAttachment) -> Unit
) {
    val isUser = message.role == "user"
    val isTool = message.role == "tool"
    val isSystem = message.role == "system"
    val isDarkTheme = isSystemInDarkTheme()
    val bubbleColors = when {
        isUser -> ChatBubbleColors(
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            header = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.88f),
            time = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
        )
        isTool -> ChatBubbleColors(
            container = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f),
            content = MaterialTheme.colorScheme.onSecondaryContainer,
            header = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.88f),
            time = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f)
        )
        isSystem -> ChatBubbleColors(
            container = MaterialTheme.colorScheme.tertiaryContainer,
            content = MaterialTheme.colorScheme.onTertiaryContainer,
            header = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.88f),
            time = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.72f)
        )
        else -> ChatBubbleColors(
            container = if (isDarkTheme) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.52f)
            } else {
                MaterialTheme.colorScheme.surface
            },
            content = MaterialTheme.colorScheme.onSurface,
            header = MaterialTheme.colorScheme.onSurfaceVariant,
            time = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
        )
    }
    val visibleContent = when {
        message.role == "assistant" -> displayedAssistantText ?: message.content
        isTool && message.isCollapsible -> if (expanded) message.expandedContent.orEmpty() else message.content
        else -> message.content
    }
    val displayContent = if (
        useChinese &&
        (message.role == "assistant" || isSystem) &&
        shouldLocalizeUiMessage(visibleContent)
    ) {
        localizedUiMessage(visibleContent, useChinese = true)
    } else {
        visibleContent
    }
    if (isUser) {
        UserMessageBubble(
            message = message,
            label = identity.userDisplayName.ifBlank { uiLabel("You") },
            displayContent = displayContent,
            bubbleColors = bubbleColors,
            previewAudioRef = previewAudioRef,
            previewAudioDurationMs = previewAudioDurationMs,
            previewAudioPositionMs = previewAudioPositionMs,
            onOpenAttachment = onOpenAttachment,
            onToggleAudioPreview = onToggleAudioPreview
        )
    } else if (isTool) {
        ToolMessageBubble(
            message = message,
            displayContent = displayContent,
            bubbleColors = bubbleColors,
            expanded = expanded,
            onToggleExpanded = onToggleExpanded,
            previewAudioRef = previewAudioRef,
            previewAudioDurationMs = previewAudioDurationMs,
            previewAudioPositionMs = previewAudioPositionMs,
            onOpenAttachment = onOpenAttachment,
            onToggleAudioPreview = onToggleAudioPreview
        )
    } else {
        AssistantMessageBubble(
            message = message,
            label = if (isSystem) uiLabel("System") else identity.agentDisplayName.ifBlank { "PalmClaw" },
            displayContent = displayContent,
            bubbleColors = bubbleColors,
            previewAudioRef = previewAudioRef,
            previewAudioDurationMs = previewAudioDurationMs,
            previewAudioPositionMs = previewAudioPositionMs,
            onOpenAttachment = onOpenAttachment,
            onToggleAudioPreview = onToggleAudioPreview
        )
    }
}

@Composable
private fun UserMessageBubble(
    message: UiMessage,
    label: String,
    displayContent: String,
    bubbleColors: ChatBubbleColors,
    previewAudioRef: String?,
    previewAudioDurationMs: Int,
    previewAudioPositionMs: Int,
    onOpenAttachment: (UiAttachment) -> Unit,
    onToggleAudioPreview: (UiAttachment) -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = bubbleColors.container,
            contentColor = bubbleColors.content,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.width(340.dp)
        ) {
            CompositionLocalProvider(LocalChatBubbleColors provides bubbleColors) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    ChatBubbleHeader(label = label, createdAt = message.createdAt)
                    MessageMarkdown(displayContent, bubbleColors, fillMaxWidth = false)
                    MessageAttachmentList(
                        message = message,
                        previewAudioRef = previewAudioRef,
                        previewAudioDurationMs = previewAudioDurationMs,
                        previewAudioPositionMs = previewAudioPositionMs,
                        onOpenAttachment = onOpenAttachment,
                        onToggleAudioPreview = onToggleAudioPreview
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolMessageBubble(
    message: UiMessage,
    displayContent: String,
    bubbleColors: ChatBubbleColors,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    previewAudioRef: String?,
    previewAudioDurationMs: Int,
    previewAudioPositionMs: Int,
    onOpenAttachment: (UiAttachment) -> Unit,
    onToggleAudioPreview: (UiAttachment) -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(
            color = bubbleColors.container,
            contentColor = bubbleColors.content,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.width(340.dp)
        ) {
            CompositionLocalProvider(LocalChatBubbleColors provides bubbleColors) {
                Column(
                    modifier = Modifier.padding(
                        start = 12.dp,
                        end = 12.dp,
                        top = 1.dp,
                        bottom = 10.dp
                    )
                ) {
                    ChatBubbleHeader(label = uiLabel("Tool"), createdAt = message.createdAt, topPadding = 7.dp)
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (message.isCollapsible && !expanded) {
                            Text(
                                text = message.content,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 14.sp,
                                    lineHeight = 14.sp
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                                color = bubbleColors.content
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                        if (message.isCollapsible) {
                            CompactTextAction(
                                label = uiLabel(if (expanded) "Hide" else "Details"),
                                expanded = expanded,
                                onClick = onToggleExpanded
                            )
                        }
                    }
                    if (message.isCollapsible) {
                        if (expanded) MessageMarkdown(displayContent, bubbleColors)
                    } else {
                        MessageMarkdown(displayContent, bubbleColors)
                    }
                    val showAttachments = message.attachments.isNotEmpty() &&
                        (!message.isCollapsible || expanded)
                    if (showAttachments) {
                        MessageAttachmentList(
                            message = message,
                            previewAudioRef = previewAudioRef,
                            previewAudioDurationMs = previewAudioDurationMs,
                            previewAudioPositionMs = previewAudioPositionMs,
                            onOpenAttachment = onOpenAttachment,
                            onToggleAudioPreview = onToggleAudioPreview
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantMessageBubble(
    message: UiMessage,
    label: String,
    displayContent: String,
    bubbleColors: ChatBubbleColors,
    previewAudioRef: String?,
    previewAudioDurationMs: Int,
    previewAudioPositionMs: Int,
    onOpenAttachment: (UiAttachment) -> Unit,
    onToggleAudioPreview: (UiAttachment) -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(
            color = bubbleColors.container,
            contentColor = bubbleColors.content,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.width(340.dp)
        ) {
            CompositionLocalProvider(LocalChatBubbleColors provides bubbleColors) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    ChatBubbleHeader(label = label, createdAt = message.createdAt)
                    MessageMarkdown(displayContent, bubbleColors)
                    MessageAttachmentList(
                        message = message,
                        previewAudioRef = previewAudioRef,
                        previewAudioDurationMs = previewAudioDurationMs,
                        previewAudioPositionMs = previewAudioPositionMs,
                        onOpenAttachment = onOpenAttachment,
                        onToggleAudioPreview = onToggleAudioPreview
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageMarkdown(
    markdown: String,
    bubbleColors: ChatBubbleColors,
    fillMaxWidth: Boolean = true
) {
    MarkdownText(
        markdown = markdown,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
        inlineCodeBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        quoteBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.56f),
        codeBlockBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f),
        fillMaxWidth = fillMaxWidth,
        contentColor = bubbleColors.content
    )
}

@Composable
private fun MessageAttachmentList(
    message: UiMessage,
    previewAudioRef: String?,
    previewAudioDurationMs: Int,
    previewAudioPositionMs: Int,
    onOpenAttachment: (UiAttachment) -> Unit,
    onToggleAudioPreview: (UiAttachment) -> Unit
) {
    if (message.attachments.isNotEmpty()) {
        AttachmentList(
            attachments = message.attachments,
            currentPreviewAudioRef = previewAudioRef,
            currentPreviewAudioDurationMs = previewAudioDurationMs,
            currentPreviewAudioPositionMs = previewAudioPositionMs,
            onOpenAttachment = onOpenAttachment,
            onToggleAudioPreview = onToggleAudioPreview
        )
    }
}

@Composable
private fun ProcessingBubble() {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.width(340.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(14.dp)
                        .padding(end = 2.dp),
                    strokeWidth = 2.dp
                )
                Text(uiLabel("Processing..."))
            }
        }
    }
}
