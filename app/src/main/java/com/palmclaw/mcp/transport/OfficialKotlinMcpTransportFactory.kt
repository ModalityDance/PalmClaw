package com.palmclaw.mcp.transport

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.headers
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.client.ReconnectionOptions
import io.modelcontextprotocol.kotlin.sdk.client.SseClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.shared.TransportSendOptions
import io.modelcontextprotocol.kotlin.sdk.types.AudioContent
import io.modelcontextprotocol.kotlin.sdk.types.BlobResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.CompleteRequest
import io.modelcontextprotocol.kotlin.sdk.types.CompleteRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequest
import io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.InitializeResult
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.types.ListPromptsRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListResourceTemplatesRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListResourcesRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.Method
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.PromptListChangedNotification
import io.modelcontextprotocol.kotlin.sdk.types.PromptReference
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ResourceLink
import io.modelcontextprotocol.kotlin.sdk.types.ResourceListChangedNotification
import io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplateReference
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotification
import io.modelcontextprotocol.kotlin.sdk.types.Role
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.ToolListChangedNotification
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.modelcontextprotocol.kotlin.sdk.types.UnknownResourceContents
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

/**
 * Official MCP Kotlin SDK 0.10.0 adapter.
 *
 * Version 0.10.0 speaks 2025-11-25 and the initialize-era revisions down to 2024-11-05.
 * It intentionally does not claim support for the stateless 2026-07-28 protocol.
 */
class OfficialKotlinMcpTransportFactory(
    private val httpClientFactory: (McpTransportConnectRequest) -> HttpClient = ::createMcpHttpClient,
) : McpTransportClientFactory {

    override suspend fun connect(request: McpTransportConnectRequest): McpClientSession {
        if (request.transportPreference != McpTransportPreference.AUTO) {
            return connectSingle(request, request.transportPreference)
        }

        return try {
            connectSingle(request, McpTransportPreference.STREAMABLE_HTTP)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (!McpLegacyFallbackPolicy.shouldFallback(failure)) throw failure
            connectSingle(request, McpTransportPreference.LEGACY_HTTP_SSE)
        }
    }

    private suspend fun connectSingle(
        request: McpTransportConnectRequest,
        preference: McpTransportPreference,
    ): McpClientSession {
        val httpClient = httpClientFactory(request)
        var client: Client? = null
        var transport: Transport? = null

        try {
            val originGuard = McpRequestOriginGuard(request.endpoint)
            val officialClient = Client(
                clientInfo = Implementation(name = CLIENT_NAME, version = CLIENT_VERSION),
                options = ClientOptions(
                    capabilities = ClientCapabilities(),
                    enforceStrictCapabilities = true,
                ),
            )
            client = officialClient

            val selectedTransport = when (preference) {
                McpTransportPreference.STREAMABLE_HTTP -> createStreamableHttpTransport(
                    httpClient,
                    request,
                    originGuard,
                )

                McpTransportPreference.LEGACY_HTTP_SSE -> createLegacySseTransport(
                    httpClient,
                    request,
                    originGuard,
                )

                McpTransportPreference.AUTO -> error("AUTO must be resolved before opening a transport")
            }
            transport = selectedTransport.transport

            val protocolVersion = withMcpHandshakeTimeout(request.requestTimeoutMillis) {
                officialClient.connect(selectedTransport.transport)
                selectedTransport.negotiatedProtocolVersion.await()
            }
            val server = requireNotNull(officialClient.serverVersion) {
                "MCP server did not provide implementation information"
            }
            val capabilities = officialClient.serverCapabilities

            return OfficialKotlinMcpClientSession(
                client = officialClient,
                httpClient = httpClient,
                transport = selectedTransport.transport,
                transportKind = selectedTransport.kind,
                negotiated = McpNegotiatedInfo(
                    transport = selectedTransport.kind,
                    protocolVersion = protocolVersion,
                    server = McpImplementationInfo(
                        name = server.name,
                        version = server.version,
                        title = server.title,
                        websiteUrl = server.websiteUrl,
                    ),
                    instructions = officialClient.serverInstructions,
                ),
                capabilities = McpServerCapabilities(
                    tools = capabilities?.tools != null,
                    toolListChanged = capabilities?.tools?.listChanged == true,
                    resources = capabilities?.resources != null,
                    resourceListChanged = capabilities?.resources?.listChanged == true,
                    resourceSubscriptions = capabilities?.resources?.subscribe == true,
                    prompts = capabilities?.prompts != null,
                    promptListChanged = capabilities?.prompts?.listChanged == true,
                    completions = capabilities?.completions != null,
                ),
                request = request,
            )
        } catch (cancelled: CancellationException) {
            closeAfterConnectFailure(client, transport, httpClient)
            cancelled.rethrowOrMapInternalTimeout(outcomeUnknown = false)
        } catch (failure: Throwable) {
            closeAfterConnectFailure(client, transport, httpClient)
            throw failure.toMcpTransportException(outcomeUnknown = false)
        }
    }

    private fun createStreamableHttpTransport(
        httpClient: HttpClient,
        request: McpTransportConnectRequest,
        originGuard: McpRequestOriginGuard,
    ): SelectedTransport {
        val delegate = StreamableHttpClientTransport(
            client = httpClient,
            url = request.endpoint,
            // This only reconnects the optional GET notification stream. It never replays tools/call.
            reconnectionOptions = ReconnectionOptions(maxRetries = NOTIFICATION_STREAM_MAX_RETRIES),
            requestBuilder = { applyMcpRequestSecurity(originGuard, request.bearerToken) },
        )
        val negotiated = CompletableDeferred<String>()
        val observer = NegotiatedVersionTransport(delegate, negotiated) { delegate.protocolVersion = it }
        return SelectedTransport(observer, McpTransportKind.STREAMABLE_HTTP, negotiated)
    }

    private fun createLegacySseTransport(
        httpClient: HttpClient,
        request: McpTransportConnectRequest,
        originGuard: McpRequestOriginGuard,
    ): SelectedTransport {
        val delegate = SseClientTransport(
            client = httpClient,
            urlString = request.endpoint,
            requestBuilder = { applyMcpRequestSecurity(originGuard, request.bearerToken) },
        )
        val negotiated = CompletableDeferred<String>()
        return SelectedTransport(
            transport = NegotiatedVersionTransport(delegate, negotiated),
            kind = McpTransportKind.LEGACY_HTTP_SSE,
            negotiatedProtocolVersion = negotiated,
        )
    }

    private suspend fun closeAfterConnectFailure(client: Client?, transport: Transport?, httpClient: HttpClient) {
        withContext(NonCancellable) {
            runCatching { client?.close() }
            // Client owns the transport, but still attempt the transport close if Client.close()
            // itself fails. Official transports make repeated close calls safe.
            runCatching { transport?.close() }
            httpClient.close()
        }
    }

    private data class SelectedTransport(
        val transport: Transport,
        val kind: McpTransportKind,
        val negotiatedProtocolVersion: CompletableDeferred<String>,
    )

    companion object {
        private const val CLIENT_NAME = "PalmClaw"
        private const val CLIENT_VERSION = "0.2.1"
        private const val NOTIFICATION_STREAM_MAX_RETRIES = 3
    }
}

internal object McpLegacyFallbackPolicy {
    fun shouldFallback(failure: Throwable): Boolean {
        val transport = failure.findCause<McpTransportException>() ?: return false
        if (transport.code == McpTransportErrorCode.UNSUPPORTED) return true
        val status = transport.httpStatus ?: return false
        return status in FALLBACK_HTTP_STATUSES && when (status) {
            404 -> transport.code == McpTransportErrorCode.NOT_FOUND
            else -> transport.code == McpTransportErrorCode.PROTOCOL
        }
    }

    private val FALLBACK_HTTP_STATUSES = setOf(400, 404, 405)
}

internal class McpRequestOriginViolationException(message: String) : IllegalStateException(message)

/** Restricts every SDK request to the configured scheme, host, and effective port. */
internal class McpRequestOriginGuard(configuredEndpoint: String) {
    private val configuredOrigin = configuredEndpoint.toMcpHttpOrigin()
        ?: throw McpRequestOriginViolationException("Configured MCP endpoint is not an absolute HTTP(S) URL")

    fun requireSameOrigin(candidateEndpoint: String) {
        val candidateOrigin = candidateEndpoint.toMcpHttpOrigin()
        if (candidateOrigin == null || candidateOrigin != configuredOrigin) {
            throw McpRequestOriginViolationException("MCP request target changed origin")
        }
    }
}

internal fun HttpRequestBuilder.applyMcpRequestSecurity(
    originGuard: McpRequestOriginGuard,
    bearerToken: String?,
) {
    // The legacy server supplies its POST endpoint over SSE. Validate every SDK request before
    // attaching credentials so an absolute cross-origin endpoint cannot receive them.
    originGuard.requireSameOrigin(url.buildString())
    bearerToken?.let { bearerAuth(it) }
}

private data class McpHttpOrigin(
    val scheme: String,
    val host: String,
    val port: Int,
)

private fun String.toMcpHttpOrigin(): McpHttpOrigin? = toHttpUrlOrNull()?.let { url ->
    McpHttpOrigin(scheme = url.scheme, host = url.host, port = url.port)
}

internal suspend fun <T> withMcpHandshakeTimeout(
    timeoutMillis: Long,
    block: suspend () -> T,
): T = withTimeout(timeoutMillis) { block() }

private fun createMcpHttpClient(request: McpTransportConnectRequest): HttpClient = HttpClient(OkHttp) {
    expectSuccess = true
    followRedirects = false
    install(SSE)
    install(HttpTimeout) {
        requestTimeoutMillis = request.requestTimeoutMillis
        connectTimeoutMillis = request.requestTimeoutMillis
        // SSE connections are intentionally idle between events. A global socket timeout would
        // tear down healthy long-lived MCP sessions; ordinary POSTs remain bounded by request time.
        socketTimeoutMillis = null
    }
    engine {
        config {
            followRedirects(false)
            retryOnConnectionFailure(false)
            // OkHttp defaults to a 10-second read timeout, which would disconnect an idle but
            // healthy SSE stream. Non-SSE requests remain bounded by Ktor's request timeout.
            readTimeout(0L, TimeUnit.MILLISECONDS)
            addNetworkInterceptor(McpResponseSizeLimitInterceptor(request.responseLimits.maxResponseBytes))
        }
    }
    defaultRequest {
        headers {
            append(HttpHeaders.AcceptEncoding, "identity")
        }
    }
}

/** Observes the initialization response without exposing SDK types to the lifecycle boundary. */
internal class NegotiatedVersionTransport(
    private val delegate: Transport,
    private val negotiatedVersion: CompletableDeferred<String>,
    private val onNegotiated: (String) -> Unit = {},
) : Transport {
    private val closeLock = Any()
    private var closeObserver: (() -> Unit)? = null
    private var transportClosed = false
    private var closeObserverNotified = false

    override suspend fun start() = delegate.start()

    override suspend fun send(message: JSONRPCMessage, options: TransportSendOptions?) = delegate.send(message, options)

    override suspend fun close() = delegate.close()

    override fun onClose(block: () -> Unit) = delegate.onClose {
        try {
            block()
        } finally {
            signalTransportClosed()
        }
    }

    override fun onError(block: (Throwable) -> Unit) = delegate.onError(block)

    override fun onMessage(block: suspend (JSONRPCMessage) -> Unit) {
        delegate.onMessage { message ->
            val result = (message as? JSONRPCResponse)?.result as? InitializeResult
            if (result != null && !negotiatedVersion.isCompleted) {
                onNegotiated(result.protocolVersion)
                negotiatedVersion.complete(result.protocolVersion)
            }
            block(message)
        }
    }

    suspend fun terminateStreamableSession() {
        (delegate as? StreamableHttpClientTransport)?.terminateSession()
    }

    fun observeClose(observer: () -> Unit) {
        val replay = synchronized(closeLock) {
            closeObserver = observer
            observer.takeIf { transportClosed && !closeObserverNotified }
                ?.also { closeObserverNotified = true }
        }
        replay?.invoke()
    }

    private fun signalTransportClosed() {
        val observer = synchronized(closeLock) {
            transportClosed = true
            closeObserver?.takeIf { !closeObserverNotified }
                ?.also { closeObserverNotified = true }
        }
        observer?.invoke()
    }
}

private class OfficialKotlinMcpClientSession(
    private val client: Client,
    private val httpClient: HttpClient,
    private val transport: Transport,
    private val transportKind: McpTransportKind,
    override val negotiated: McpNegotiatedInfo,
    override val capabilities: McpServerCapabilities,
    private val request: McpTransportConnectRequest,
) : McpClientSession {
    private val eventFlow = MutableSharedFlow<McpServerEvent>(
        replay = 1,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val events: Flow<McpServerEvent> = eventFlow
    private val closed = AtomicBoolean(false)

    init {
        (transport as? NegotiatedVersionTransport)?.observeClose {
            if (!closed.get()) {
                eventFlow.tryEmit(McpServerEvent.Disconnected)
            }
        }
        client.setNotificationHandler<ToolListChangedNotification>(Method.Defined.NotificationsToolsListChanged) {
            eventFlow.tryEmit(McpServerEvent.ToolsChanged)
            completedUnit()
        }
        client.setNotificationHandler<ResourceListChangedNotification>(Method.Defined.NotificationsResourcesListChanged) {
            eventFlow.tryEmit(McpServerEvent.ResourcesChanged)
            completedUnit()
        }
        client.setNotificationHandler<PromptListChangedNotification>(Method.Defined.NotificationsPromptsListChanged) {
            eventFlow.tryEmit(McpServerEvent.PromptsChanged)
            completedUnit()
        }
        client.setNotificationHandler<ResourceUpdatedNotification>(Method.Defined.NotificationsResourcesUpdated) { note ->
            eventFlow.tryEmit(McpServerEvent.ResourceUpdated(note.params.uri))
            completedUnit()
        }
    }

    override suspend fun listTools(cursor: String?): McpPage<McpRemoteTool> = invokeBounded {
        client.listTools(ListToolsRequest(cursor.toPageParams()), requestOptions()).let { result ->
            ensureItemLimit("tools/list", result.tools.size)
            McpPage(
                items = result.tools.map { tool ->
                    McpRemoteTool(
                        name = tool.name,
                        description = tool.description,
                        title = tool.title,
                        inputSchema = tool.inputSchema.toTransportJsonObject(),
                        outputSchema = tool.outputSchema?.toTransportJsonObject(),
                        annotations = tool.annotations?.let {
                            McpToolAnnotations(
                                title = it.title,
                                readOnlyHint = it.readOnlyHint,
                                destructiveHint = it.destructiveHint,
                                idempotentHint = it.idempotentHint,
                                openWorldHint = it.openWorldHint,
                            )
                        },
                        meta = tool.meta,
                    )
                },
                nextCursor = result.nextCursor,
                meta = result.meta,
            )
        }
    }

    override suspend fun callTool(name: String, arguments: JsonObject): McpToolCallResult = invokeBounded {
        // No adapter retry surrounds this call. A transport failure is returned to the lifecycle as-is.
        try {
            client.callTool(
                CallToolRequest(CallToolRequestParams(name = name, arguments = arguments)),
                requestOptions(),
            ).let { result ->
                McpToolCallResult(
                    content = result.content.map { it.toTransportContent(request.responseLimits) },
                    structuredContent = result.structuredContent,
                    isError = result.isError == true,
                    meta = result.meta,
                )
            }
        } catch (cancelled: CancellationException) {
            cancelled.rethrowOrMapInternalTimeout(outcomeUnknown = true)
        } catch (failure: Throwable) {
            throw failure.toMcpTransportException(outcomeUnknown = failure.isDispatchUncertain())
        }
    }

    override suspend fun listResources(cursor: String?): McpPage<McpRemoteResource> = invokeBounded {
        client.listResources(ListResourcesRequest(cursor.toPageParams()), requestOptions()).let { result ->
            ensureItemLimit("resources/list", result.resources.size)
            McpPage(
                items = result.resources.map { resource ->
                    McpRemoteResource(
                        uri = resource.uri,
                        name = resource.name,
                        title = resource.title,
                        description = resource.description,
                        mimeType = resource.mimeType,
                        size = resource.size,
                        annotations = resource.annotations?.toTransport(),
                        meta = resource.meta,
                    )
                },
                nextCursor = result.nextCursor,
                meta = result.meta,
            )
        }
    }

    override suspend fun listResourceTemplates(cursor: String?): McpPage<McpRemoteResourceTemplate> = invokeBounded {
        client.listResourceTemplates(ListResourceTemplatesRequest(cursor.toPageParams()), requestOptions()).let { result ->
            ensureItemLimit("resources/templates/list", result.resourceTemplates.size)
            McpPage(
                items = result.resourceTemplates.map { template ->
                    McpRemoteResourceTemplate(
                        uriTemplate = template.uriTemplate,
                        name = template.name,
                        title = template.title,
                        description = template.description,
                        mimeType = template.mimeType,
                        annotations = template.annotations?.toTransport(),
                        meta = template.meta,
                    )
                },
                nextCursor = result.nextCursor,
                meta = result.meta,
            )
        }
    }

    override suspend fun readResource(uri: String): McpReadResourceResult = invokeBounded {
        client.readResource(
            ReadResourceRequest(ReadResourceRequestParams(uri)),
            requestOptions(),
        ).let { result ->
            ensureItemLimit("resources/read", result.contents.size)
            McpReadResourceResult(
                contents = result.contents.map { it.toTransport(request.responseLimits) },
                meta = result.meta,
            )
        }
    }

    override suspend fun listPrompts(cursor: String?): McpPage<McpRemotePrompt> = invokeBounded {
        client.listPrompts(ListPromptsRequest(cursor.toPageParams()), requestOptions()).let { result ->
            ensureItemLimit("prompts/list", result.prompts.size)
            McpPage(
                items = result.prompts.map { prompt ->
                    McpRemotePrompt(
                        name = prompt.name,
                        description = prompt.description,
                        title = prompt.title,
                        arguments = prompt.arguments.orEmpty().map {
                            McpPromptArgument(
                                name = it.name,
                                description = it.description,
                                required = it.required == true,
                                title = it.title,
                            )
                        },
                        meta = prompt.meta,
                    )
                },
                nextCursor = result.nextCursor,
                meta = result.meta,
            )
        }
    }

    override suspend fun getPrompt(name: String, arguments: Map<String, String>): McpPromptResult = invokeBounded {
        client.getPrompt(
            GetPromptRequest(GetPromptRequestParams(name = name, arguments = arguments.takeIf { it.isNotEmpty() })),
            requestOptions(),
        ).let { result ->
            ensureItemLimit("prompts/get", result.messages.size)
            McpPromptResult(
                description = result.description,
                messages = result.messages.map {
                    McpPromptMessage(
                        role = it.role.toTransport(),
                        content = it.content.toTransportContent(request.responseLimits),
                    )
                },
                meta = result.meta,
            )
        }
    }

    override suspend fun complete(request: McpCompletionRequest): McpCompletionResult = invokeBounded {
        val reference = when (val ref = request.reference) {
            is McpCompletionReference.Prompt -> PromptReference(ref.name, ref.title)
            is McpCompletionReference.ResourceTemplate -> ResourceTemplateReference(ref.uri)
        }
        client.complete(
            CompleteRequest(
                CompleteRequestParams(
                    argument = CompleteRequestParams.Argument(request.argumentName, request.argumentValue),
                    ref = reference,
                    context = request.contextArguments.takeIf { it.isNotEmpty() }
                        ?.let(CompleteRequestParams::Context),
                ),
            ),
            requestOptions(),
        ).let { result ->
            ensureItemLimit("completion/complete", result.completion.values.size)
            McpCompletionResult(
                values = result.completion.values,
                total = result.completion.total,
                hasMore = result.completion.hasMore,
                meta = result.meta,
            )
        }
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        var terminationFailure: Throwable? = null
        try {
            if (transportKind == McpTransportKind.STREAMABLE_HTTP) {
                try {
                    (transport as? NegotiatedVersionTransport)?.terminateStreamableSession()
                } catch (cancelled: CancellationException) {
                    terminationFailure = cancelled.toMcpCancellationFailure(
                        outcomeUnknown = false,
                        currentContextActive = currentCoroutineContext().isActive,
                    )
                } catch (failure: Throwable) {
                    terminationFailure = failure.toMcpTransportException(outcomeUnknown = false)
                }
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    client.close()
                } catch (failure: Throwable) {
                    if (terminationFailure == null) terminationFailure = failure
                    else terminationFailure?.addSuppressed(failure)
                } finally {
                    httpClient.close()
                }
            }
        }
        terminationFailure?.let { failure ->
            if (failure is CancellationException) throw failure
            throw failure.toMcpTransportException(outcomeUnknown = false)
        }
    }

    private fun requestOptions(): RequestOptions = RequestOptions(timeout = request.requestTimeoutMillis.milliseconds)

    private fun ensureItemLimit(operation: String, count: Int) {
        if (count > request.responseLimits.maxItemsPerPage) {
            throw McpResponseLimitException(
                "$operation returned $count items; limit is ${request.responseLimits.maxItemsPerPage}",
            )
        }
    }

    private suspend fun <T> invokeBounded(block: suspend () -> T): T = try {
        ensureOpen()
        block()
    } catch (cancelled: CancellationException) {
        cancelled.rethrowOrMapInternalTimeout(outcomeUnknown = false)
    } catch (failure: McpTransportException) {
        throw failure
    } catch (failure: Throwable) {
        throw failure.toMcpTransportException(outcomeUnknown = false)
    }

    private fun ensureOpen() {
        check(!closed.get()) { "MCP session is closed" }
    }

}

private fun completedUnit(): CompletableDeferred<Unit> = CompletableDeferred(Unit).also { it.complete(Unit) }

private fun String?.toPageParams(): PaginatedRequestParams? = this?.let(::PaginatedRequestParams)

/** Uses the SDK serializer so input and output schemas cannot diverge from its wire model. */
internal fun ToolSchema.toTransportJsonObject(): JsonObject =
    McpJson.encodeToJsonElement(ToolSchema.serializer(), this).jsonObject

private fun io.modelcontextprotocol.kotlin.sdk.types.Annotations.toTransport(): McpAnnotations = McpAnnotations(
    audience = audience?.map { it.toTransport() },
    priority = priority,
    lastModified = lastModified,
)

private fun Role.toTransport(): McpRole = when (this) {
    Role.User -> McpRole.USER
    Role.Assistant -> McpRole.ASSISTANT
}

private fun io.modelcontextprotocol.kotlin.sdk.types.ContentBlock.toTransportContent(
    limits: McpResponseLimits,
): McpContentBlock = when (this) {
    is TextContent -> McpContentBlock.Text(
        text = text.checkedText(limits),
        annotations = annotations?.toTransport(),
        meta = meta,
    )

    is ImageContent -> McpContentBlock.Image(
        data = data.checkedBase64(limits),
        mimeType = mimeType,
        annotations = annotations?.toTransport(),
        meta = meta,
    )

    is AudioContent -> McpContentBlock.Audio(
        data = data.checkedBase64(limits),
        mimeType = mimeType,
        annotations = annotations?.toTransport(),
        meta = meta,
    )

    is ResourceLink -> McpContentBlock.ResourceLink(
        uri = uri,
        name = name,
        title = title,
        description = description,
        mimeType = mimeType,
        size = size,
        annotations = annotations?.toTransport(),
        meta = meta,
    )

    is EmbeddedResource -> McpContentBlock.EmbeddedResource(
        resource = resource.toTransport(limits),
        annotations = annotations?.toTransport(),
        meta = meta,
    )
}

private fun io.modelcontextprotocol.kotlin.sdk.types.ResourceContents.toTransport(
    limits: McpResponseLimits,
): McpResourceContents = when (this) {
    is TextResourceContents -> McpResourceContents.Text(
        uri = uri,
        mimeType = mimeType,
        text = text.checkedText(limits),
        meta = meta,
    )

    is BlobResourceContents -> McpResourceContents.Blob(
        uri = uri,
        mimeType = mimeType,
        data = blob.checkedBase64(limits),
        meta = meta,
    )

    is UnknownResourceContents -> McpResourceContents.Unknown(uri = uri, mimeType = mimeType, meta = meta)
}

private fun String.checkedText(limits: McpResponseLimits): String {
    if (length > limits.maxTextChars) {
        throw McpResponseLimitException("MCP text content exceeds ${limits.maxTextChars} characters")
    }
    return this
}

private fun String.checkedBase64(limits: McpResponseLimits): String {
    if (length > limits.maxBase64Chars) {
        throw McpResponseLimitException("MCP binary content exceeds ${limits.maxBase64Chars} base64 characters")
    }
    return this
}

internal fun Throwable.toMcpTransportException(outcomeUnknown: Boolean): McpTransportException {
    if (this is McpResponseLimitException && outcomeUnknown && !this.outcomeUnknown) {
        return McpResponseLimitException(
            message = message ?: "MCP response exceeded its limit",
            cause = this,
            outcomeUnknown = true,
        )
    }
    if (this is McpTransportException) return this
    findCause<McpResponseTooLargeIOException>()?.let { limitFailure ->
        return McpResponseLimitException(
            message = limitFailure.message ?: "MCP response exceeded its byte limit",
            cause = limitFailure,
            outcomeUnknown = outcomeUnknown,
        )
    }
    findCause<McpRequestOriginViolationException>()?.let { originFailure ->
        return McpTransportException(
            code = McpTransportErrorCode.PROTOCOL,
            message = "MCP request target violated the configured origin",
            recoverable = false,
            outcomeUnknown = false,
            cause = originFailure,
        )
    }
    return when (this) {
        is StreamableHttpError -> httpFailure(code, outcomeUnknown)

        is ResponseException -> {
            httpFailure(response.status.value, outcomeUnknown)
        }

        is HttpRequestTimeoutException,
        is TimeoutCancellationException,
        is SocketTimeoutException,
        is ConnectTimeoutException,
        -> McpTransportException(
            code = McpTransportErrorCode.TIMEOUT,
            message = "MCP request timed out",
            recoverable = true,
            outcomeUnknown = outcomeUnknown,
            cause = this,
        )

        is ConnectException,
        is IOException,
        -> McpTransportException(
            code = McpTransportErrorCode.NETWORK,
            message = "MCP network request failed",
            recoverable = true,
            outcomeUnknown = outcomeUnknown,
            cause = this,
        )

        is McpException -> McpTransportException(
            code = McpTransportErrorCode.PROTOCOL,
            message = message ?: "MCP protocol error",
            recoverable = false,
            outcomeUnknown = outcomeUnknown,
            cause = this,
        )

        else -> McpTransportException(
            code = McpTransportErrorCode.PROTOCOL,
            message = message ?: "MCP transport error",
            recoverable = false,
            outcomeUnknown = outcomeUnknown,
            cause = this,
        )
    }
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    val visited = mutableSetOf<Throwable>()
    while (current != null && visited.add(current)) {
        if (current is T) return current
        current = current.cause
    }
    return null
}

private suspend fun CancellationException.rethrowOrMapInternalTimeout(outcomeUnknown: Boolean): Nothing {
    throw toMcpCancellationFailure(
        outcomeUnknown = outcomeUnknown,
        currentContextActive = currentCoroutineContext().isActive,
    )
}

/**
 * An SDK-owned `withTimeout` leaves its caller active; user cancellation and an outer timeout do not.
 * This distinction keeps request timeouts structured without swallowing lifecycle cancellation.
 */
internal fun CancellationException.toMcpCancellationFailure(
    outcomeUnknown: Boolean,
    currentContextActive: Boolean,
): Throwable = if (this is TimeoutCancellationException && currentContextActive) {
    toMcpTransportException(outcomeUnknown)
} else {
    this
}

private fun Throwable.httpFailure(status: Int?, outcomeUnknown: Boolean): McpTransportException {
    val actualStatus = status ?: -1
    val code = when (actualStatus) {
        401 -> McpTransportErrorCode.AUTHENTICATION_REQUIRED
        403 -> McpTransportErrorCode.PERMISSION_DENIED
        404 -> McpTransportErrorCode.NOT_FOUND
        429 -> McpTransportErrorCode.RATE_LIMITED
        in 500..599 -> McpTransportErrorCode.SERVER_ERROR
        else -> McpTransportErrorCode.PROTOCOL
    }
    return McpTransportException(
        code = code,
        message = status?.let { "MCP request failed with HTTP $it" } ?: "MCP HTTP transport failed",
        recoverable = actualStatus == 429 || actualStatus in 500..599,
        outcomeUnknown = outcomeUnknown,
        httpStatus = status,
        cause = this,
    )
}

private fun Throwable.isDispatchUncertain(): Boolean = when (this) {
    is McpResponseLimitException -> true
    is ResponseException -> false
    is ConnectException -> false
    is ConnectTimeoutException -> false
    is HttpRequestTimeoutException,
    is TimeoutCancellationException,
    is SocketTimeoutException,
    is IOException,
    -> true

    else -> false
}
