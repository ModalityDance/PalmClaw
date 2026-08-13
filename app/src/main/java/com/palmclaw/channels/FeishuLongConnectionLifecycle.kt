package com.palmclaw.channels

import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1

internal data class FeishuLongConnectionCallbacks(
    val onInboundSignal: () -> Unit,
    val onInboundEvent: (P2MessageReceiveV1) -> Unit
)

internal interface FeishuLongConnectionClient {
    fun start()
    fun close()
}

internal fun interface FeishuLongConnectionClientFactory {
    fun create(callbacks: FeishuLongConnectionCallbacks): FeishuLongConnectionClient
}

internal interface FeishuGatewayLogger {
    fun debug(message: String)
    fun warning(message: String)
}

internal object NoOpFeishuGatewayLogger : FeishuGatewayLogger {
    override fun debug(message: String) = Unit
    override fun warning(message: String) = Unit
}
