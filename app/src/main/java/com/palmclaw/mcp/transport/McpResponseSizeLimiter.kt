package com.palmclaw.mcp.transport

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer

/** Enforces response limits before Ktor or the MCP SDK can materialize an unbounded body. */
internal class McpResponseSizeLimitInterceptor(
    private val maxResponseBytes: Long,
) : Interceptor {
    init {
        require(maxResponseBytes > 0) { "maxResponseBytes must be positive" }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body ?: return response
        val eventStream = body.contentType().isEventStream()

        if (!eventStream) {
            try {
                validateMcpContentLength(body.contentLength(), maxResponseBytes)
            } catch (failure: McpResponseTooLargeIOException) {
                response.close()
                throw failure
            }
        }

        return response.newBuilder()
            .body(LimitedResponseBody(body, maxResponseBytes, eventStream))
            .build()
    }
}

internal class McpResponseTooLargeIOException(
    val limitBytes: Long,
    val observedBytes: Long,
) : IOException("MCP response exceeded $limitBytes bytes (observed at least $observedBytes bytes)")

internal fun validateMcpContentLength(contentLength: Long, maxResponseBytes: Long) {
    if (contentLength > maxResponseBytes) {
        throw McpResponseTooLargeIOException(maxResponseBytes, contentLength)
    }
}

internal fun limitMcpResponseSource(
    source: Source,
    maxResponseBytes: Long,
    eventStream: Boolean,
): BufferedSource = McpBoundedResponseSource(source, maxResponseBytes, eventStream).buffer()

private class LimitedResponseBody(
    private val delegate: ResponseBody,
    maxResponseBytes: Long,
    eventStream: Boolean,
) : ResponseBody() {
    private val limitedSource by lazy(LazyThreadSafetyMode.NONE) {
        limitMcpResponseSource(delegate.source(), maxResponseBytes, eventStream)
    }

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun source(): BufferedSource = limitedSource
}

/**
 * Bounds a normal response as a whole. SSE responses are long-lived, so they are bounded per event
 * instead; this still rejects a single oversized JSON-RPC event without imposing a session byte cap.
 */
private class McpBoundedResponseSource(
    delegate: Source,
    private val maxResponseBytes: Long,
    private val eventStream: Boolean,
) : ForwardingSource(delegate) {
    private var observedBytes = 0L
    private var currentEventBytes = 0L
    private var currentLineHasData = false
    private var previousWasCarriageReturn = false

    init {
        require(maxResponseBytes > 0) { "maxResponseBytes must be positive" }
    }

    override fun read(sink: Buffer, byteCount: Long): Long {
        val boundedByteCount = if (eventStream) {
            minOf(byteCount, maxResponseBytes + 1L)
        } else {
            minOf(byteCount, maxResponseBytes - observedBytes + 1L)
        }
        val startOffset = sink.size
        val read = super.read(sink, boundedByteCount)
        if (read == -1L) return -1L

        if (!eventStream) {
            observedBytes += read
            ensureWithinLimit(observedBytes)
            return read
        }

        var offset = startOffset
        val endOffset = startOffset + read
        while (offset < endOffset) {
            inspectEventByte(sink[offset])
            offset++
        }
        return read
    }

    private fun inspectEventByte(value: Byte) {
        if ((value.toInt() and 0xff) == LINE_FEED && previousWasCarriageReturn) {
            previousWasCarriageReturn = false
            return
        }
        currentEventBytes++
        when (value.toInt() and 0xff) {
            CARRIAGE_RETURN -> {
                finishEventLine()
                previousWasCarriageReturn = true
            }

            LINE_FEED -> {
                if (!previousWasCarriageReturn) finishEventLine()
                previousWasCarriageReturn = false
            }

            else -> {
                currentLineHasData = true
                previousWasCarriageReturn = false
            }
        }
        ensureWithinLimit(currentEventBytes)
    }

    private fun finishEventLine() {
        if (!currentLineHasData) currentEventBytes = 0L
        currentLineHasData = false
    }

    private fun ensureWithinLimit(observed: Long) {
        if (observed > maxResponseBytes) {
            throw McpResponseTooLargeIOException(maxResponseBytes, observed)
        }
    }

    private companion object {
        const val CARRIAGE_RETURN = 13
        const val LINE_FEED = 10
    }
}

private fun MediaType?.isEventStream(): Boolean =
    this?.let { it.type == "text" && it.subtype == "event-stream" } == true
