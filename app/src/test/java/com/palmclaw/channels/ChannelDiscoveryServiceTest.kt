package com.palmclaw.channels

import com.palmclaw.bus.InboundMessage
import com.palmclaw.bus.OutboundMessage
import com.palmclaw.config.SessionChannelBinding
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelDiscoveryServiceTest {
    private val services = mutableListOf<ChannelDiscoveryService>()

    @After
    fun closeServices() {
        services.forEach(ChannelDiscoveryService::close)
    }

    @Test
    fun `telegram rejects a blank token without calling the client`() = runBlocking {
        var calls = 0
        val service = service(
            telegramClient = TelegramDiscoveryClient {
                calls += 1
                emptyList()
            }
        )

        val outcome = service.discoverTelegram(TelegramDiscoveryRequest("  "))

        val failed = outcome as ChannelDiscoveryOutcome.Failed<TelegramChatCandidate>
        assertEquals(ChannelDiscoveryFailureKind.INVALID_INPUT, failed.kind)
        assertEquals(0, calls)
    }

    @Test
    fun `telegram normalizes the token and returns domain candidates`() = runBlocking {
        var receivedToken = ""
        val service = service(
            telegramClient = TelegramDiscoveryClient { token ->
                receivedToken = token
                listOf(TelegramChatCandidate(chatId = "42", title = "Chat", kind = "private"))
            }
        )

        val outcome = service.discoverTelegram(TelegramDiscoveryRequest(" token "))

        val completed = outcome as ChannelDiscoveryOutcome.Completed<TelegramChatCandidate>
        assertEquals("token", receivedToken)
        assertEquals(listOf("42"), completed.candidates.map { it.chatId })
        assertEquals(null, completed.reason)
    }

    @Test
    fun `telegram preserves typed authentication failure`() = runBlocking {
        val service = service(
            telegramClient = TelegramDiscoveryClient {
                throw ChannelDiscoveryException(
                    ChannelDiscoveryFailureKind.AUTHENTICATION,
                    "Telegram authentication failed."
                )
            }
        )

        val outcome = service.discoverTelegram(TelegramDiscoveryRequest("token"))

        val failed = outcome as ChannelDiscoveryOutcome.Failed<TelegramChatCandidate>
        assertEquals(ChannelDiscoveryFailureKind.AUTHENTICATION, failed.kind)
        assertEquals("Telegram authentication failed.", failed.message)
    }

    @Test
    fun `telegram maps unexpected client failure to a safe network outcome`() = runBlocking {
        val service = service(
            telegramClient = TelegramDiscoveryClient { error("raw transport detail") }
        )

        val outcome = service.discoverTelegram(TelegramDiscoveryRequest("token"))

        val failed = outcome as ChannelDiscoveryOutcome.Failed<TelegramChatCandidate>
        assertEquals(ChannelDiscoveryFailureKind.NETWORK, failed.kind)
        assertEquals(
            "Discover chats failed. Check the bot token and network connection.",
            failed.message
        )
    }

    @Test
    fun `email returns active detection instead of stale diagnostic candidates`() = runBlocking {
        val diagnostics = FakeDiagnosticsSource().apply {
            emailSnapshot = EmailGatewaySnapshot(
                recentSenders = listOf(EmailSenderCandidate("cached@example.com", "Cached"))
            )
        }
        val service = service(
            emailDetector = EmailSenderDetector {
                listOf(EmailSenderCandidate("fresh@example.com", "Fresh"))
            },
            diagnosticsSource = diagnostics
        )

        val outcome = service.discoverEmail(completeEmailRequest())

        val completed = outcome as ChannelDiscoveryOutcome.Completed<EmailSenderCandidate>
        assertEquals(listOf("fresh@example.com"), completed.candidates.map { it.email })
    }

    @Test
    fun `email discovery requires imap but not smtp credentials`() = runBlocking {
        var calls = 0
        val service = service(
            emailDetector = EmailSenderDetector {
                calls += 1
                emptyList()
            }
        )
        val request = completeEmailRequest().copy(
            smtpHost = "",
            smtpUsername = "",
            smtpPassword = ""
        )

        val outcome = service.discoverEmail(request)

        val completed = outcome as ChannelDiscoveryOutcome.Completed<EmailSenderCandidate>
        assertEquals(ChannelDiscoveryCompletionReason.NO_CANDIDATES, completed.reason)
        assertEquals(1, calls)
    }

    @Test
    fun `email normalizes account config and keeps diagnostic fallback on failure`() = runBlocking {
        var receivedConfig: EmailAccountConfig? = null
        val diagnostics = FakeDiagnosticsSource().apply {
            emailSnapshot = EmailGatewaySnapshot(
                recentSenders = listOf(
                    EmailSenderCandidate("cached@example.com", "Cached", "diagnostic")
                )
            )
        }
        val service = service(
            emailDetector = EmailSenderDetector { config ->
                receivedConfig = config
                error("server detail must not escape")
            },
            diagnosticsSource = diagnostics
        )

        val outcome = service.discoverEmail(completeEmailRequest())

        val failed = outcome as ChannelDiscoveryOutcome.Failed<EmailSenderCandidate>
        assertEquals("inbox@example.com", receivedConfig?.imapUsername)
        assertEquals(993, receivedConfig?.imapPort)
        assertEquals(65_535, receivedConfig?.smtpPort)
        assertEquals(ChannelDiscoveryFailureKind.NETWORK, failed.kind)
        assertEquals("Email sender detection failed.", failed.message)
        assertEquals(listOf("cached@example.com"), failed.fallbackCandidates.map { it.email })
    }

    @Test
    fun `email preserves typed authentication failure and diagnostic fallback`() = runBlocking {
        val diagnostics = FakeDiagnosticsSource().apply {
            emailSnapshot = EmailGatewaySnapshot(
                recentSenders = listOf(EmailSenderCandidate("cached@example.com", "Cached"))
            )
        }
        val service = service(
            emailDetector = EmailSenderDetector {
                throw ChannelDiscoveryException(
                    ChannelDiscoveryFailureKind.AUTHENTICATION,
                    "Email authentication failed."
                )
            },
            diagnosticsSource = diagnostics
        )

        val outcome = service.discoverEmail(completeEmailRequest())

        val failed = outcome as ChannelDiscoveryOutcome.Failed<EmailSenderCandidate>
        assertEquals(ChannelDiscoveryFailureKind.AUTHENTICATION, failed.kind)
        assertEquals("Email authentication failed.", failed.message)
        assertEquals(listOf("cached@example.com"), failed.fallbackCandidates.map { it.email })
    }

    @Test
    fun `feishu reuses an active runtime without creating a temporary adapter`() = runBlocking {
        val request = completeFeishuRequest()
        val binding = request.toBinding()
        val key = requireNotNull(ChannelAdapterIdentity.primaryKeyForBinding(binding))
        val diagnostics = FakeDiagnosticsSource().apply {
            feishuSnapshots = mapOf(
                key to FeishuGatewaySnapshot(
                    running = true,
                    connected = true,
                    ready = true,
                    recentChats = listOf(FeishuChatCandidate("oc_chat", "Chat", "group"))
                )
            )
        }
        val factory = FakeAdapterFactory()
        val service = service(
            diagnosticsSource = diagnostics,
            runtimeSnapshotSource = ChannelRuntimeSnapshotSource { channel, adapterKey ->
                if (channel == "feishu" && adapterKey == key) {
                    ChannelRuntimeSnapshot(running = true, connected = true, ready = true)
                } else {
                    ChannelRuntimeSnapshot()
                }
            },
            adapterFactory = factory
        )

        val outcome = service.discoverFeishu(request, binding)

        val completed = outcome as ChannelDiscoveryOutcome.Completed<FeishuChatCandidate>
        assertEquals(listOf("oc_chat"), completed.candidates.map { it.chatId })
        assertEquals(0, factory.feishuCreated)
    }

    @Test
    fun `feishu temporary adapter stops after finding a candidate`() = runBlocking {
        val diagnostics = FakeDiagnosticsSource()
        val factory = FakeAdapterFactory().apply {
            onFeishuStart = { key ->
                diagnostics.feishuSnapshots = mapOf(
                    key to FeishuGatewaySnapshot(
                        running = true,
                        ready = true,
                        recentChats = listOf(FeishuChatCandidate("oc_new", "New", "group"))
                    )
                )
            }
        }
        val service = service(diagnosticsSource = diagnostics, adapterFactory = factory)

        val outcome = service.discoverFeishu(completeFeishuRequest(), null)

        val completed = outcome as ChannelDiscoveryOutcome.Completed<FeishuChatCandidate>
        assertEquals(listOf("oc_new"), completed.candidates.map { it.chatId })
        assertEquals(1, factory.feishuCreated)
        assertEquals(1, factory.adapters.single().startCount)
        assertEquals(1, factory.adapters.single().stopCount)
    }

    @Test
    fun `wecom reuses an active runtime without creating a temporary adapter`() = runBlocking {
        val request = WeComDiscoveryRequest("bot", "secret")
        val key = requireNotNull(
            ChannelAdapterIdentity.primaryKeyForBinding(
                SessionChannelBinding(
                    sessionId = "session",
                    channel = "wecom",
                    wecomBotId = request.botId,
                    wecomSecret = request.secret
                )
            )
        )
        val diagnostics = FakeDiagnosticsSource().apply {
            weComSnapshots = mapOf(
                key to WeComGatewaySnapshot(
                    running = true,
                    ready = true,
                    recentChats = listOf(WeComChatCandidate("room", "Room", "group"))
                )
            )
        }
        val factory = FakeAdapterFactory()
        val service = service(
            diagnosticsSource = diagnostics,
            runtimeSnapshotSource = ChannelRuntimeSnapshotSource { channel, adapterKey ->
                if (channel == "wecom" && adapterKey == key) {
                    ChannelRuntimeSnapshot(running = true, connected = true, ready = true)
                } else {
                    ChannelRuntimeSnapshot()
                }
            },
            adapterFactory = factory
        )

        val outcome = service.discoverWeCom(request)

        val completed = outcome as ChannelDiscoveryOutcome.Completed<WeComChatCandidate>
        assertEquals(listOf("room"), completed.candidates.map { it.chatId })
        assertEquals(0, factory.weComCreated)
    }

    @Test
    fun `wecom timeout uses the monotonic deadline and stops its adapter`() = runBlocking {
        val clock = FakeClock()
        val factory = FakeAdapterFactory()
        val service = service(
            clock = clock,
            sleeper = ChannelDiscoverySleeper { durationMs -> clock.advance(durationMs) },
            adapterFactory = factory
        )

        val outcome = service.discoverWeCom(WeComDiscoveryRequest(" bot ", " secret "))

        val completed = outcome as ChannelDiscoveryOutcome.Completed<WeComChatCandidate>
        assertEquals(ChannelDiscoveryCompletionReason.TIMEOUT, completed.reason)
        assertTrue(clock.nowMs() >= 15_000L)
        assertEquals(1, factory.weComCreated)
        assertEquals(1, factory.adapters.single().stopCount)
    }

    @Test
    fun `feishu explicit runtime error stops its temporary adapter`() = runBlocking {
        val diagnostics = FakeDiagnosticsSource()
        val factory = FakeAdapterFactory().apply {
            onFeishuStart = { key ->
                diagnostics.feishuSnapshots = mapOf(
                    key to FeishuGatewaySnapshot(
                        running = true,
                        ready = false,
                        lastError = "authentication failed"
                    )
                )
            }
        }
        val service = service(diagnosticsSource = diagnostics, adapterFactory = factory)

        val outcome = service.discoverFeishu(completeFeishuRequest(), null)

        val failed = outcome as ChannelDiscoveryOutcome.Failed<FeishuChatCandidate>
        assertEquals(ChannelDiscoveryFailureKind.AUTHENTICATION, failed.kind)
        assertEquals(
            "Feishu discovery connection is not ready. Check the credentials and try again.",
            failed.message
        )
        assertEquals(1, factory.adapters.single().stopCount)
    }

    @Test
    fun `same feishu identity shares one in flight adapter`() = runBlocking {
        val clock = FakeClock()
        val diagnostics = FakeDiagnosticsSource()
        val sleeper = BlockingSleeper(clock)
        val factory = FakeAdapterFactory()
        val service = service(
            diagnosticsSource = diagnostics,
            clock = clock,
            sleeper = sleeper,
            adapterFactory = factory
        )

        val first = async { service.discoverFeishu(completeFeishuRequest(), null) }
        sleeper.entered.await()
        val second = async { service.discoverFeishu(completeFeishuRequest(), null) }
        yield()
        diagnostics.feishuSnapshots = mapOf(
            primaryFeishuKey() to FeishuGatewaySnapshot(
                running = true,
                ready = true,
                recentChats = listOf(FeishuChatCandidate("oc_shared", "Shared", "group"))
            )
        )
        sleeper.release.complete(Unit)

        assertEquals(
            listOf("oc_shared"),
            (first.await() as ChannelDiscoveryOutcome.Completed<FeishuChatCandidate>).candidates.map { it.chatId }
        )
        assertEquals(
            listOf("oc_shared"),
            (second.await() as ChannelDiscoveryOutcome.Completed<FeishuChatCandidate>).candidates.map { it.chatId }
        )
        assertEquals(1, factory.feishuCreated)
    }

    @Test
    fun `different feishu identities start independent adapters`() = runBlocking {
        val clock = FakeClock()
        val diagnostics = FakeDiagnosticsSource()
        val sleeper = BlockingSleeper(clock)
        val factory = FakeAdapterFactory()
        val service = service(
            diagnosticsSource = diagnostics,
            clock = clock,
            sleeper = sleeper,
            adapterFactory = factory
        )
        val firstRequest = completeFeishuRequest()
        val secondRequest = completeFeishuRequest().copy(appId = "other-app")

        val first = async { service.discoverFeishu(firstRequest, null) }
        sleeper.entered.await()
        val second = async { service.discoverFeishu(secondRequest, null) }
        yield()

        assertEquals(2, factory.feishuCreated)
        first.cancel()
        second.cancel()
        first.cancelAndJoin()
        second.cancelAndJoin()
    }

    @Test
    fun `cancelling one shared waiter keeps the adapter for the remaining waiter`() = runBlocking {
        val clock = FakeClock()
        val diagnostics = FakeDiagnosticsSource()
        val sleeper = BlockingSleeper(clock)
        val factory = FakeAdapterFactory()
        val service = service(
            diagnosticsSource = diagnostics,
            clock = clock,
            sleeper = sleeper,
            adapterFactory = factory
        )

        val first = async { service.discoverFeishu(completeFeishuRequest(), null) }
        sleeper.entered.await()
        val second = async { service.discoverFeishu(completeFeishuRequest(), null) }
        yield()
        first.cancelAndJoin()

        assertEquals(0, factory.adapters.single().stopCount)
        diagnostics.feishuSnapshots = mapOf(
            primaryFeishuKey() to FeishuGatewaySnapshot(
                running = true,
                ready = true,
                recentChats = listOf(FeishuChatCandidate("oc_remaining", "Remaining", "group"))
            )
        )
        sleeper.release.complete(Unit)
        second.await()
        assertEquals(1, factory.adapters.single().stopCount)
    }

    @Test
    fun `cancelling the final waiter stops the temporary adapter`() = runBlocking {
        val clock = FakeClock()
        val sleeper = BlockingSleeper(clock)
        val factory = FakeAdapterFactory()
        val service = service(clock = clock, sleeper = sleeper, adapterFactory = factory)

        val caller = async { service.discoverWeCom(WeComDiscoveryRequest("bot", "secret")) }
        sleeper.entered.await()
        caller.cancelAndJoin()

        assertEquals(1, factory.adapters.single().stopCount)
    }

    @Test
    fun `closing the process service stops an active temporary adapter`() = runBlocking {
        val clock = FakeClock()
        val sleeper = BlockingSleeper(clock)
        val factory = FakeAdapterFactory()
        val service = service(clock = clock, sleeper = sleeper, adapterFactory = factory)
        val caller = async { service.discoverWeCom(WeComDiscoveryRequest("bot", "secret")) }
        sleeper.entered.await()

        service.close()
        runCatching { caller.await() }

        assertEquals(1, factory.adapters.single().stopCount)
    }

    @Test
    fun `temporary adapter construction failure returns a safe unexpected outcome`() = runBlocking {
        val service = service(
            adapterFactory = object : ChannelDiscoveryAdapterFactory {
                override fun createFeishu(
                    request: FeishuDiscoveryRequest,
                    adapterKey: String
                ): ChannelAdapter = error("secret construction detail")

                override fun createWeCom(
                    request: WeComDiscoveryRequest,
                    adapterKey: String
                ): ChannelAdapter = error("unused")
            }
        )

        val outcome = service.discoverFeishu(completeFeishuRequest(), null)

        val failed = outcome as ChannelDiscoveryOutcome.Failed<FeishuChatCandidate>
        assertEquals(ChannelDiscoveryFailureKind.UNEXPECTED, failed.kind)
        assertEquals("Feishu discovery failed.", failed.message)
    }

    private fun service(
        telegramClient: TelegramDiscoveryClient = TelegramDiscoveryClient { emptyList() },
        emailDetector: EmailSenderDetector = EmailSenderDetector { emptyList() },
        diagnosticsSource: ChannelDiscoveryDiagnosticsSource = FakeDiagnosticsSource(),
        runtimeSnapshotSource: ChannelRuntimeSnapshotSource = ChannelRuntimeSnapshotSource { _, _ ->
            ChannelRuntimeSnapshot()
        },
        adapterFactory: ChannelDiscoveryAdapterFactory = FakeAdapterFactory(),
        clock: FakeClock = FakeClock(),
        sleeper: ChannelDiscoverySleeper = ChannelDiscoverySleeper { durationMs -> clock.advance(durationMs) }
    ): ChannelDiscoveryService = ChannelDiscoveryService(
        telegramClient = telegramClient,
        emailDetector = emailDetector,
        diagnosticsSource = diagnosticsSource,
        runtimeSnapshotSource = runtimeSnapshotSource,
        adapterFactory = adapterFactory,
        clock = clock,
        sleeper = sleeper,
        ioDispatcher = Dispatchers.Unconfined,
        workScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    ).also(services::add)

    private fun completeFeishuRequest() = FeishuDiscoveryRequest(
        appId = " cli_app ",
        appSecret = " app-secret ",
        encryptKey = " encrypt ",
        verificationToken = " verify "
    )

    private fun FeishuDiscoveryRequest.toBinding() = SessionChannelBinding(
        sessionId = "session",
        channel = "feishu",
        feishuAppId = appId,
        feishuAppSecret = appSecret,
        feishuEncryptKey = encryptKey,
        feishuVerificationToken = verificationToken
    )

    private fun primaryFeishuKey(): String = requireNotNull(
        ChannelAdapterIdentity.primaryKeyForBinding(completeFeishuRequest().toBinding())
    )

    private fun completeEmailRequest() = EmailDiscoveryRequest(
        consentGranted = true,
        imapHost = " imap.example.com ",
        imapPort = "bad",
        imapUsername = " Inbox@Example.COM ",
        imapPassword = "imap-password",
        smtpHost = " smtp.example.com ",
        smtpPort = "70000",
        smtpUsername = " Outbox@Example.COM ",
        smtpPassword = "smtp-password",
        fromAddress = " Sender@Example.COM ",
        autoReplyEnabled = true
    )

    private class FakeClock : ChannelDiscoveryClock {
        private var value = 0L

        override fun nowMs(): Long = value

        fun advance(durationMs: Long) {
            value += durationMs
        }
    }

    private class BlockingSleeper(
        private val clock: FakeClock
    ) : ChannelDiscoverySleeper {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun sleep(durationMs: Long) {
            entered.complete(Unit)
            release.await()
            clock.advance(durationMs)
        }
    }

    private class FakeDiagnosticsSource : ChannelDiscoveryDiagnosticsSource {
        var feishuSnapshots: Map<String, FeishuGatewaySnapshot> = emptyMap()
        var weComSnapshots: Map<String, WeComGatewaySnapshot> = emptyMap()
        var emailSnapshot: EmailGatewaySnapshot = EmailGatewaySnapshot()

        override fun feishuSnapshots(): Map<String, FeishuGatewaySnapshot> = feishuSnapshots

        override fun weComSnapshot(adapterKey: String): WeComGatewaySnapshot =
            weComSnapshots[adapterKey] ?: WeComGatewaySnapshot()

        override fun emailSnapshot(adapterKey: String): EmailGatewaySnapshot = emailSnapshot
    }

    private class FakeAdapterFactory : ChannelDiscoveryAdapterFactory {
        var feishuCreated = 0
        var weComCreated = 0
        var onFeishuStart: (String) -> Unit = {}
        var onWeComStart: (String) -> Unit = {}
        val adapters = mutableListOf<FakeAdapter>()

        override fun createFeishu(request: FeishuDiscoveryRequest, adapterKey: String): ChannelAdapter {
            feishuCreated += 1
            return FakeAdapter("feishu", adapterKey) { onFeishuStart(adapterKey) }
                .also(adapters::add)
        }

        override fun createWeCom(request: WeComDiscoveryRequest, adapterKey: String): ChannelAdapter {
            weComCreated += 1
            return FakeAdapter("wecom", adapterKey) { onWeComStart(adapterKey) }
                .also(adapters::add)
        }
    }

    private class FakeAdapter(
        override val channelName: String,
        override val adapterKey: String,
        private val onStart: () -> Unit
    ) : ChannelAdapter {
        var startCount = 0
        var stopCount = 0

        override fun start(scope: CoroutineScope, publishInbound: suspend (InboundMessage) -> Unit) {
            startCount += 1
            onStart()
        }

        override fun canHandleOutbound(message: OutboundMessage): Boolean = false

        override suspend fun send(message: OutboundMessage) = Unit

        override fun stop() {
            stopCount += 1
        }
    }
}
