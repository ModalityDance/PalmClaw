package com.palmclaw.providers

import java.io.IOException

/** Historical turns can cross models, but a live signed tool chain cannot. */
internal fun validateContinuationScope(messages: List<ChatMessage>, scope: String?) {
    val start = messages.indexOfLast { it.role == "user" }.coerceAtLeast(0)
    val mismatch = messages.drop(start).any { message ->
        message.protocolMetadata?.let { it.scope != scope } == true ||
            message.toolCalls.orEmpty().any { call ->
                call.protocolMetadata?.let { it.scope != scope } == true
            }
    }
    if (mismatch) {
        throw IOException(
            "Cannot continue this tool chain with a different provider, endpoint, or model. " +
                "Restore the previous configuration or start a new conversation. " +
                "Previously completed tool actions remain completed; inspect their results before repeating actions."
        )
    }
}
