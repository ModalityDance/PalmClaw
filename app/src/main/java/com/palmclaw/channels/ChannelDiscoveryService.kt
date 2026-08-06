package com.palmclaw.channels

import com.palmclaw.config.SessionChannelBinding
import com.palmclaw.config.SessionChannelBindingRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ChannelDiscoveryService(
    private val telegramClient: TelegramDiscoveryClient,
    private val emailDetector: EmailSenderDetector,
    private val diagnosticsSource: ChannelDiscoveryDiagnosticsSource,
    private val runtimeSnapshotSource: ChannelRuntimeSnapshotSource,
    private val adapterFactory: ChannelDiscoveryAdapterFactory,
    private val clock: ChannelDiscoveryClock = ChannelDiscoveryClock {
        System.nanoTime() / 1_000_000L
    },
    private val sleeper: ChannelDiscoverySleeper = ChannelDiscoverySleeper { durationMs ->
        delay(durationMs)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val workScope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher)
) {
    private val feishuTasks = SharedDiscoveryRegistry<ChannelDiscoveryOutcome<FeishuChatCandidate>>(workScope)
    private val weComTasks = SharedDiscoveryRegistry<ChannelDiscoveryOutcome<WeComChatCandidate>>(workScope)

    suspend fun discoverTelegram(
        request: TelegramDiscoveryRequest
    ): ChannelDiscoveryOutcome<TelegramChatCandidate> {
        val token = SessionChannelBindingRules.normalizeTelegramBotToken(request.botToken)
        if (token.isBlank()) {
            return ChannelDiscoveryOutcome.Failed(
                kind = ChannelDiscoveryFailureKind.INVALID_INPUT,
                message = "Please enter Telegram bot token first."
            )
        }
        return try {
            val candidates = withContext(ioDispatcher) { telegramClient.discover(token) }
            ChannelDiscoveryOutcome.Completed(
                candidates = candidates,
                reason = if (candidates.isEmpty()) ChannelDiscoveryCompletionReason.NO_CANDIDATES else null
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ChannelDiscoveryException) {
            ChannelDiscoveryOutcome.Failed(failure.kind, failure.message.orEmpty())
        } catch (_: Throwable) {
            ChannelDiscoveryOutcome.Failed(
                kind = ChannelDiscoveryFailureKind.NETWORK,
                message = "Discover chats failed. Check the bot token and network connection."
            )
        }
    }

    suspend fun discoverEmail(
        request: EmailDiscoveryRequest
    ): ChannelDiscoveryOutcome<EmailSenderCandidate> {
        val config = request.normalizedConfig()
        val adapterKey = ChannelAdapterIdentity.primaryKeyForBinding(config.toBinding("email-discovery"))
        val fallback = adapterKey
            ?.let(diagnosticsSource::emailSnapshot)
            ?.recentSenders
            .orEmpty()
        if (!config.hasDiscoveryMailboxCredentials()) {
            return ChannelDiscoveryOutcome.Failed(
                kind = ChannelDiscoveryFailureKind.INVALID_INPUT,
                message = "Mailbox consent and IMAP credentials are required.",
                fallbackCandidates = fallback
            )
        }
        return try {
            val detected = withContext(ioDispatcher) { emailDetector.detect(config) }
            val candidates = detected.ifEmpty { fallback }
            ChannelDiscoveryOutcome.Completed(
                candidates = candidates,
                reason = if (candidates.isEmpty()) ChannelDiscoveryCompletionReason.NO_CANDIDATES else null
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ChannelDiscoveryException) {
            ChannelDiscoveryOutcome.Failed(
                kind = failure.kind,
                message = failure.message.orEmpty(),
                fallbackCandidates = fallback
            )
        } catch (_: Throwable) {
            ChannelDiscoveryOutcome.Failed(
                kind = ChannelDiscoveryFailureKind.NETWORK,
                message = "Email sender detection failed.",
                fallbackCandidates = fallback
            )
        }
    }

    suspend fun discoverFeishu(
        request: FeishuDiscoveryRequest,
        currentBinding: SessionChannelBinding?
    ): ChannelDiscoveryOutcome<FeishuChatCandidate> {
        val normalized = request.normalized()
        val requestedBinding = normalized.toBinding("feishu-discovery")
        val requestedKeys = ChannelAdapterIdentity.keysForBinding(requestedBinding)
        val primaryKey = requestedKeys.firstOrNull()
            ?: return ChannelDiscoveryOutcome.Failed(
                kind = ChannelDiscoveryFailureKind.INVALID_INPUT,
                message = "Enter App ID and App Secret first, then detect again."
            )
        val currentKeys = currentBinding
            ?.takeIf { ChannelAdapterIdentity.primaryKeyForBinding(it) == primaryKey }
            ?.let(ChannelAdapterIdentity::keysForBinding)
            .orEmpty()
        return try {
            feishuTasks.run(primaryKey) {
                discoverFeishuShared(normalized, requestedKeys, currentKeys, primaryKey)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            ChannelDiscoveryOutcome.Failed(
                kind = ChannelDiscoveryFailureKind.UNEXPECTED,
                message = "Feishu discovery failed."
            )
        }
    }

    suspend fun discoverWeCom(
        request: WeComDiscoveryRequest
    ): ChannelDiscoveryOutcome<WeComChatCandidate> {
        val normalized = request.normalized()
        val binding = normalized.toBinding("wecom-discovery")
        val adapterKey = ChannelAdapterIdentity.primaryKeyForBinding(binding)
            ?: return ChannelDiscoveryOutcome.Failed(
                kind = ChannelDiscoveryFailureKind.INVALID_INPUT,
                message = "Enter Bot ID and Secret first, then detect again."
            )
        return try {
            weComTasks.run(adapterKey) {
                discoverWeComShared(normalized, adapterKey)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            ChannelDiscoveryOutcome.Failed(
                kind = ChannelDiscoveryFailureKind.UNEXPECTED,
                message = "WeCom discovery failed."
            )
        }
    }

    fun close() {
        workScope.cancel()
    }

    private suspend fun discoverFeishuShared(
        request: FeishuDiscoveryRequest,
        requestedKeys: List<String>,
        currentKeys: List<String>,
        primaryKey: String
    ): ChannelDiscoveryOutcome<FeishuChatCandidate> {
        val compatibleKeys = (requestedKeys + currentKeys).distinct()
        if (hasActiveRuntime("feishu", compatibleKeys)) {
            return awaitFeishu(compatibleKeys, currentKeys)
        }
        val adapter = adapterFactory.createFeishu(request, primaryKey)
        if (hasActiveRuntime("feishu", compatibleKeys)) {
            return awaitFeishu(compatibleKeys, currentKeys)
        }
        return withTemporaryAdapter(adapter) {
            awaitFeishu(compatibleKeys, currentKeys)
        }
    }

    private suspend fun discoverWeComShared(
        request: WeComDiscoveryRequest,
        adapterKey: String
    ): ChannelDiscoveryOutcome<WeComChatCandidate> {
        if (hasActiveRuntime("wecom", listOf(adapterKey))) {
            return awaitWeCom(adapterKey)
        }
        val adapter = adapterFactory.createWeCom(request, adapterKey)
        if (hasActiveRuntime("wecom", listOf(adapterKey))) {
            return awaitWeCom(adapterKey)
        }
        return withTemporaryAdapter(adapter) { awaitWeCom(adapterKey) }
    }

    private suspend fun awaitFeishu(
        requestedKeys: List<String>,
        currentKeys: List<String>
    ): ChannelDiscoveryOutcome<FeishuChatCandidate> {
        val deadline = clock.nowMs() + DISCOVERY_WINDOW_MS
        while (true) {
            val result = ChannelDiscoverySnapshotInterpreter.collectFeishu(
                requestedAdapterKeys = requestedKeys,
                currentBindingAdapterKeys = currentKeys,
                snapshotsByAdapterKey = diagnosticsSource.feishuSnapshots()
            )
            if (result.candidates.isNotEmpty()) {
                return ChannelDiscoveryOutcome.Completed(
                    candidates = result.candidates,
                    info = "Feishu chats discovered. Tap one to use."
                )
            }
            val explicitError = result.snapshots.values.firstOrNull {
                it.lastError.isNotBlank() && !it.ready
            }
            if (explicitError != null) {
                return ChannelDiscoveryOutcome.Failed(
                    kind = ChannelDiscoveryFailureKind.AUTHENTICATION,
                    message = ChannelDiscoverySnapshotInterpreter.feishuInfo(result)
                )
            }
            val remainingMs = deadline - clock.nowMs()
            if (remainingMs <= 0L) {
                return ChannelDiscoveryOutcome.Completed(
                    candidates = emptyList(),
                    info = ChannelDiscoverySnapshotInterpreter.feishuInfo(result),
                    reason = ChannelDiscoveryCompletionReason.TIMEOUT
                )
            }
            sleeper.sleep(minOf(POLL_INTERVAL_MS, remainingMs))
        }
    }

    private suspend fun awaitWeCom(
        adapterKey: String
    ): ChannelDiscoveryOutcome<WeComChatCandidate> {
        val deadline = clock.nowMs() + DISCOVERY_WINDOW_MS
        while (true) {
            val snapshot = diagnosticsSource.weComSnapshot(adapterKey)
            if (snapshot.recentChats.isNotEmpty()) {
                return ChannelDiscoveryOutcome.Completed(
                    candidates = snapshot.recentChats.distinctBy { it.chatId },
                    info = "WeCom chats discovered. Tap one to use."
                )
            }
            if (snapshot.lastError.isNotBlank() && !snapshot.ready) {
                return ChannelDiscoveryOutcome.Failed(
                    kind = ChannelDiscoveryFailureKind.AUTHENTICATION,
                    message = ChannelDiscoverySnapshotInterpreter.weComInfo(snapshot)
                )
            }
            val remainingMs = deadline - clock.nowMs()
            if (remainingMs <= 0L) {
                return ChannelDiscoveryOutcome.Completed(
                    candidates = emptyList(),
                    info = ChannelDiscoverySnapshotInterpreter.weComInfo(snapshot),
                    reason = ChannelDiscoveryCompletionReason.TIMEOUT
                )
            }
            sleeper.sleep(minOf(POLL_INTERVAL_MS, remainingMs))
        }
    }

    private fun hasActiveRuntime(channel: String, adapterKeys: List<String>): Boolean =
        adapterKeys.any { adapterKey ->
            runtimeSnapshotSource.getSnapshot(channel, adapterKey).let { snapshot ->
                snapshot.running || snapshot.connected || snapshot.ready
            }
        }

    private suspend fun <T> withTemporaryAdapter(
        adapter: ChannelAdapter,
        block: suspend () -> T
    ): T {
        try {
            adapter.start(workScope) { }
            return block()
        } finally {
            adapter.stop()
        }
    }

    private fun FeishuDiscoveryRequest.normalized() = copy(
        appId = appId.trim(),
        appSecret = appSecret.trim(),
        encryptKey = encryptKey.trim(),
        verificationToken = verificationToken.trim()
    )

    private fun FeishuDiscoveryRequest.toBinding(sessionId: String) = SessionChannelBinding(
        sessionId = sessionId,
        channel = "feishu",
        feishuAppId = appId,
        feishuAppSecret = appSecret,
        feishuEncryptKey = encryptKey,
        feishuVerificationToken = verificationToken
    )

    private fun WeComDiscoveryRequest.normalized() = copy(
        botId = botId.trim(),
        secret = secret.trim()
    )

    private fun WeComDiscoveryRequest.toBinding(sessionId: String) = SessionChannelBinding(
        sessionId = sessionId,
        channel = "wecom",
        wecomBotId = botId,
        wecomSecret = secret
    )

    private fun EmailDiscoveryRequest.normalizedConfig() = EmailAccountConfig(
        consentGranted = consentGranted,
        imapHost = imapHost.trim(),
        imapPort = imapPort.toIntOrNull()?.coerceIn(1, 65_535) ?: DEFAULT_IMAP_PORT,
        imapUsername = SessionChannelBindingRules.normalizeEmailAddress(imapUsername),
        imapPassword = imapPassword,
        smtpHost = smtpHost.trim(),
        smtpPort = smtpPort.toIntOrNull()?.coerceIn(1, 65_535) ?: DEFAULT_SMTP_PORT,
        smtpUsername = SessionChannelBindingRules.normalizeEmailAddress(smtpUsername),
        smtpPassword = smtpPassword,
        fromAddress = SessionChannelBindingRules.normalizeEmailAddress(fromAddress),
        autoReplyEnabled = autoReplyEnabled
    )

    private fun EmailAccountConfig.hasDiscoveryMailboxCredentials(): Boolean =
        consentGranted &&
            imapHost.isNotBlank() &&
            imapUsername.isNotBlank() &&
            imapPassword.isNotBlank()

    private fun EmailAccountConfig.toBinding(sessionId: String) = SessionChannelBinding(
        sessionId = sessionId,
        channel = "email",
        emailConsentGranted = consentGranted,
        emailImapHost = imapHost,
        emailImapPort = imapPort,
        emailImapUsername = imapUsername,
        emailImapPassword = imapPassword,
        emailSmtpHost = smtpHost,
        emailSmtpPort = smtpPort,
        emailSmtpUsername = smtpUsername,
        emailSmtpPassword = smtpPassword,
        emailFromAddress = fromAddress,
        emailAutoReplyEnabled = autoReplyEnabled
    )

    private companion object {
        const val DISCOVERY_WINDOW_MS = 15_000L
        const val POLL_INTERVAL_MS = 350L
        const val DEFAULT_IMAP_PORT = 993
        const val DEFAULT_SMTP_PORT = 587
    }
}

private class SharedDiscoveryRegistry<T>(
    private val scope: CoroutineScope
) {
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, Entry<T>>()

    suspend fun run(key: String, block: suspend () -> T): T {
        val deferred = mutex.withLock {
            val current = entries[key]
            if (current != null) {
                current.waiters += 1
                current.deferred
            } else {
                val created = scope.async(start = CoroutineStart.LAZY) { block() }
                entries[key] = Entry(created, waiters = 1)
                created
            }
        }
        deferred.start()
        return try {
            deferred.await()
        } finally {
            val shouldCancel = mutex.withLock {
                val current = entries[key]
                if (current == null || current.deferred !== deferred) {
                    false
                } else {
                    current.waiters -= 1
                    if (current.waiters == 0) {
                        entries.remove(key)
                        true
                    } else {
                        false
                    }
                }
            }
            if (shouldCancel) {
                withContext(NonCancellable) {
                    deferred.cancelAndJoin()
                }
            }
        }
    }

    private data class Entry<T>(
        val deferred: Deferred<T>,
        var waiters: Int
    )
}
