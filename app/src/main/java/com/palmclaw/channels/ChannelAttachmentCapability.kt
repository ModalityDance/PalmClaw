package com.palmclaw.channels

data class ChannelAttachmentCapability(
    val supportsInboundFiles: Boolean,
    val supportsOutboundFiles: Boolean,
    val requiresAuthenticatedDownload: Boolean
) {
    companion object {
        val None = ChannelAttachmentCapability(
            supportsInboundFiles = false,
            supportsOutboundFiles = false,
            requiresAuthenticatedDownload = false
        )
    }
}
