package com.palmclaw.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Composable
internal fun ChatMessageListPane(
    state: ChatContentState,
    identity: IdentityDisplayState,
    useChinese: Boolean,
    inputBarSurfaceHeightPx: Int,
    previewAudioRef: String?,
    previewAudioDurationMs: Int,
    previewAudioPositionMs: Int,
    onOpenAttachment: (UiAttachment) -> Unit,
    onToggleAudioPreview: (UiAttachment) -> Unit,
    onLoadOlderMessages: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val displayedAssistantText = remember { mutableStateMapOf<Long, String>() }
    var initializedMessages by rememberSaveable { mutableStateOf(false) }
    var trackedMessageIds by rememberSaveable { mutableStateOf<List<Long>>(emptyList()) }
    var generationAnchorMessageId by rememberSaveable { mutableStateOf<Long?>(null) }
    var hasInitialJumpToBottom by rememberSaveable { mutableStateOf(false) }
    var followLatest by rememberSaveable { mutableStateOf(true) }
    var olderHistoryLoadingStartedAtMs by rememberSaveable { mutableStateOf(0L) }
    var pendingHistoryRestore by remember { mutableStateOf<HistoryRestoreRequest?>(null) }
    val expandedToolMessages = remember { mutableStateMapOf<Long, Boolean>() }

    val visibleMessages = state.messages
    val canLoadOlderHistory = state.canLoadOlderMessages
    val isLoadingOlderHistory = state.messagesLoadingOlder
    val showHistoryStatus = visibleMessages.isNotEmpty()
    val headerItemCount = if (showHistoryStatus) 1 else 0
    val hasAssistantOutputAfterAnchor = remember(
        generationAnchorMessageId,
        visibleMessages,
        displayedAssistantText.size
    ) {
        val anchor = generationAnchorMessageId
        anchor != null && visibleMessages.any { message ->
            message.id > anchor &&
                message.role == "assistant" &&
                (displayedAssistantText[message.id] ?: message.content).isNotBlank()
        }
    }
    val showProcessingBubble = state.isGenerating && !hasAssistantOutputAfterAnchor
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
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeVisible = imeBottomPx > 0
    val chatInputBarClearance = with(density) {
        val fallback = CHAT_INPUT_BAR_CLEARANCE.roundToPx()
        val outerVerticalPadding = 8.dp.roundToPx()
        val overlayHeight = maxOf(inputBarSurfaceHeightPx + outerVerticalPadding, fallback)
        val totalOverlayHeight = overlayHeight + if (imeVisible) imeBottomPx else 0
        (totalOverlayHeight.toDp() - 10.dp).coerceAtLeast(52.dp) + CHAT_TAIL_VISIBLE_GAP
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
    var programmaticScrolling by remember { mutableStateOf(false) }
    var nearTailBeforeImeOpen by rememberSaveable { mutableStateOf(true) }
    var scrollToLatestAfterSend by remember { mutableStateOf(false) }
    val autoScrollMutex = remember { Mutex() }
    val scope = rememberCoroutineScope()

    suspend fun moveToLatest(animated: Boolean) {
        if (tailIndex < 0) return
        autoScrollMutex.withLock {
            programmaticScrolling = true
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
                programmaticScrolling = false
            }
        }
    }

    LaunchedEffect(
        state.isGenerating,
        visibleMessages.lastOrNull()?.id,
        visibleMessages.lastOrNull()?.role
    ) {
        if (!state.isGenerating) {
            generationAnchorMessageId = null
            return@LaunchedEffect
        }
        val lastMessage = visibleMessages.lastOrNull()
        val latestUserLikeMessageId = visibleMessages
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
    }

    LaunchedEffect(listState.isScrollInProgress, isNearTail, programmaticScrolling) {
        if (programmaticScrolling) {
            if (isNearTail) followLatest = true
            return@LaunchedEffect
        }
        if (listState.isScrollInProgress && !isNearTail) {
            followLatest = false
        } else if (isNearTail) {
            followLatest = true
        }
    }
    LaunchedEffect(imeVisible, isNearTail) {
        if (!imeVisible) nearTailBeforeImeOpen = isNearTail
    }
    LaunchedEffect(imeVisible, nearTailBeforeImeOpen, tailIndex) {
        if (imeVisible && nearTailBeforeImeOpen && tailIndex >= 0) {
            followLatest = true
            delay(32)
            moveToLatest(animated = false)
        }
    }
    LaunchedEffect(scrollToLatestAfterSend, visibleMessages.lastOrNull()?.id, tailIndex) {
        if (!scrollToLatestAfterSend || tailIndex < 0) return@LaunchedEffect
        moveToLatest(animated = false)
        scrollToLatestAfterSend = false
    }
    LaunchedEffect(visibleMessages) {
        if (!initializedMessages) {
            if (visibleMessages.isEmpty()) return@LaunchedEffect
            displayedAssistantText.clear()
            visibleMessages.forEach { message ->
                if (message.role == "assistant") displayedAssistantText[message.id] = message.content
            }
            trackedMessageIds = visibleMessages.map { it.id }
            initializedMessages = true
            return@LaunchedEffect
        }

        val previousIds = trackedMessageIds
        val currentIds = visibleMessages.map { it.id }
        val sharesStablePrefix = previousIds.size <= currentIds.size &&
            previousIds.indices.all { index -> previousIds[index] == currentIds[index] }

        if (!sharesStablePrefix) {
            displayedAssistantText.clear()
            visibleMessages.forEach { message ->
                if (message.role == "assistant") displayedAssistantText[message.id] = message.content
            }
            trackedMessageIds = currentIds
            return@LaunchedEffect
        }

        if (currentIds.size > previousIds.size) {
            val appended = visibleMessages.subList(previousIds.size, visibleMessages.size)
            appended.forEach { message ->
                if (message.role == "assistant") displayedAssistantText[message.id] = message.content
            }
            if (appended.lastOrNull()?.role == "user") {
                followLatest = true
                scrollToLatestAfterSend = true
            }
        }

        visibleMessages.lastOrNull { it.role == "assistant" }?.let { latestAssistant ->
            if (displayedAssistantText[latestAssistant.id] != latestAssistant.content) {
                displayedAssistantText[latestAssistant.id] = latestAssistant.content
            }
        }
        trackedMessageIds = currentIds
    }
    LaunchedEffect(state.currentSessionId) {
        hasInitialJumpToBottom = false
        initializedMessages = false
        trackedMessageIds = emptyList()
        displayedAssistantText.clear()
        generationAnchorMessageId = null
        followLatest = true
        scrollToLatestAfterSend = false
        pendingHistoryRestore = null
        olderHistoryLoadingStartedAtMs = 0L
    }
    LaunchedEffect(
        pendingHistoryRestore,
        visibleMessages.size,
        visibleMessages.firstOrNull()?.id,
        headerItemCount,
        canLoadOlderHistory
    ) {
        val restore = pendingHistoryRestore ?: return@LaunchedEffect
        if (
            restore.previousFirstMessageId != null &&
            visibleMessages.firstOrNull()?.id == restore.previousFirstMessageId &&
            canLoadOlderHistory
        ) {
            return@LaunchedEffect
        }
        val localIndex = visibleMessages.indexOfFirst { it.id == restore.anchorMessageId }
        if (localIndex < 0) {
            val elapsed = System.currentTimeMillis() - olderHistoryLoadingStartedAtMs
            val remain = HISTORY_LOADING_MIN_VISIBLE_MS - elapsed
            if (remain > 0) delay(remain)
            pendingHistoryRestore = null
            olderHistoryLoadingStartedAtMs = 0L
            return@LaunchedEffect
        }
        listState.scrollToItem(
            index = (headerItemCount + localIndex).coerceAtLeast(0),
            scrollOffset = -restore.anchorOffsetFromTop.coerceAtLeast(0)
        )
        val elapsed = System.currentTimeMillis() - olderHistoryLoadingStartedAtMs
        val remain = HISTORY_LOADING_MIN_VISIBLE_MS - elapsed
        if (remain > 0) delay(remain)
        pendingHistoryRestore = null
        olderHistoryLoadingStartedAtMs = 0L
    }
    LaunchedEffect(
        hasInitialJumpToBottom,
        listState.firstVisibleItemIndex,
        listState.firstVisibleItemScrollOffset,
        canLoadOlderHistory,
        isLoadingOlderHistory,
        visibleMessages.size,
        visibleMessages.firstOrNull()?.id
    ) {
        if (!hasInitialJumpToBottom) return@LaunchedEffect
        if (!canLoadOlderHistory || isLoadingOlderHistory) return@LaunchedEffect
        val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        if (!atTop) return@LaunchedEffect

        delay(HISTORY_LOAD_TRIGGER_DELAY_MS)
        val stillAtTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        if (!stillAtTop || !canLoadOlderHistory || isLoadingOlderHistory) return@LaunchedEffect

        val firstVisibleInfo = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index >= headerItemCount }
        val anchorMessageId = firstVisibleInfo?.let { info ->
            visibleMessages.getOrNull(info.index - headerItemCount)?.id
        } ?: visibleMessages.firstOrNull()?.id ?: return@LaunchedEffect
        val anchorOffsetFromTop = firstVisibleInfo?.let { info ->
            (info.offset - listState.layoutInfo.viewportStartOffset).coerceAtLeast(0)
        } ?: 0

        olderHistoryLoadingStartedAtMs = System.currentTimeMillis()
        followLatest = false
        pendingHistoryRestore = HistoryRestoreRequest(
            anchorMessageId = anchorMessageId,
            anchorOffsetFromTop = anchorOffsetFromTop,
            previousFirstMessageId = visibleMessages.firstOrNull()?.id
        )
        onLoadOlderMessages()
    }
    LaunchedEffect(
        visibleMessages.lastOrNull()?.id,
        showProcessingBubble,
        followLatest,
        isNearTail
    ) {
        if (tailIndex < 0) return@LaunchedEffect
        if (!hasInitialJumpToBottom) {
            moveToLatest(animated = false)
            hasInitialJumpToBottom = true
            return@LaunchedEffect
        }
        if (!followLatest) return@LaunchedEffect
        if (isNearTail) return@LaunchedEffect
        moveToLatest(animated = true)
    }

    Box(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                start = 3.dp,
                end = 3.dp,
                bottom = chatInputBarClearance
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showHistoryStatus) {
                item(key = "history-status") {
                    HistoryStatusRow(
                        isLoading = isLoadingOlderHistory,
                        canLoadOlderHistory = canLoadOlderHistory
                    )
                }
            }

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
                val messageExpanded = message.role == "tool" && expandedToolMessages[message.id] == true
                ChatMessageBubble(
                    message = message,
                    identity = identity,
                    useChinese = useChinese,
                    displayedAssistantText = displayedAssistantText[message.id],
                    expanded = messageExpanded,
                    onToggleExpanded = {
                        expandedToolMessages[message.id] = !messageExpanded
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

        ChatScrollOverlay(
            listState = listState,
            scrollIndicator = scrollIndicator,
            showScrollToLatestButton = totalItems > 0 && !isNearTail,
            chatInputBarClearance = chatInputBarClearance,
            onScrollToLatest = {
                if (tailIndex >= 0) {
                    followLatest = true
                    scope.launch { moveToLatest(animated = true) }
                }
            }
        )
        LaunchedEffect(totalItems > 0 && !isNearTail && followLatest) {
            if (followLatest && tailIndex >= 0) {
                moveToLatest(animated = true)
            }
        }
    }
}

@Composable
private fun HistoryStatusRow(
    isLoading: Boolean,
    canLoadOlderHistory: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
