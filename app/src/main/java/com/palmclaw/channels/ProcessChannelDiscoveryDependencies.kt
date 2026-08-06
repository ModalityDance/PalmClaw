package com.palmclaw.channels

import android.content.Context
import javax.mail.AuthenticationFailedException

object ProcessChannelDiscoveryDiagnosticsSource : ChannelDiscoveryDiagnosticsSource {
    override fun feishuSnapshots(): Map<String, FeishuGatewaySnapshot> =
        FeishuGatewayDiagnostics.getSnapshots()

    override fun weComSnapshot(adapterKey: String): WeComGatewaySnapshot =
        WeComGatewayDiagnostics.getSnapshot(adapterKey)

    override fun emailSnapshot(adapterKey: String): EmailGatewaySnapshot =
        EmailGatewayDiagnostics.getSnapshot(adapterKey)
}

object DefaultEmailSenderDetector : EmailSenderDetector {
    override fun detect(config: EmailAccountConfig): List<EmailSenderCandidate> = try {
        EmailChannelAdapter.detectRecentSenders(config)
    } catch (_: AuthenticationFailedException) {
        throw ChannelDiscoveryException(
            ChannelDiscoveryFailureKind.AUTHENTICATION,
            "Email authentication failed."
        )
    }
}

class AndroidChannelDiscoveryAdapterFactory(
    context: Context
) : ChannelDiscoveryAdapterFactory {
    private val appContext = context.applicationContext

    override fun createFeishu(
        request: FeishuDiscoveryRequest,
        adapterKey: String
    ): ChannelAdapter = FeishuChannelAdapter(
        adapterKey = adapterKey,
        appId = request.appId,
        appSecret = request.appSecret,
        encryptKey = request.encryptKey,
        verificationToken = request.verificationToken,
        allowedChatTargets = emptySet()
    )

    override fun createWeCom(
        request: WeComDiscoveryRequest,
        adapterKey: String
    ): ChannelAdapter = WeComChannelAdapter(
        context = appContext,
        adapterKey = adapterKey,
        botId = request.botId,
        secret = request.secret,
        allowedChatTargets = emptySet(),
        captureOnly = true
    )
}
