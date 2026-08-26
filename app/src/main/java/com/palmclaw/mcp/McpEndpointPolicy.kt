package com.palmclaw.mcp

import com.palmclaw.config.McpHttpServerConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

enum class McpEndpointDisposition {
    ALLOWED,
    ACTION_REQUIRED,
    REJECTED
}

enum class McpEndpointSecurity {
    HTTPS,
    LOOPBACK_HTTP,
    EMULATOR_HTTP,
    PRIVATE_LAN_HTTP
}

enum class McpEndpointNetworkScope {
    LOOPBACK,
    EMULATOR,
    PRIVATE_NETWORK,
    EXTERNAL
}

enum class McpEndpointIssue {
    URL_REQUIRED,
    INVALID_URL,
    UNSUPPORTED_SCHEME,
    USERINFO_NOT_ALLOWED,
    QUERY_CREDENTIAL_NOT_ALLOWED,
    FRAGMENT_NOT_ALLOWED,
    PUBLIC_HTTP_NOT_ALLOWED,
    INSECURE_HTTP_CONFIRMATION_REQUIRED,
    AUTH_REQUIRES_HTTPS
}

data class McpEndpointDecision(
    val disposition: McpEndpointDisposition,
    val security: McpEndpointSecurity? = null,
    val networkScope: McpEndpointNetworkScope? = null,
    val canonicalUrl: String? = null,
    val canonicalOrigin: String? = null,
    val issue: McpEndpointIssue? = null,
    val message: String,
    val warning: String? = null
) {
    val canConnect: Boolean
        get() = disposition == McpEndpointDisposition.ALLOWED

    val requiresAction: Boolean
        get() = disposition == McpEndpointDisposition.ACTION_REQUIRED

    val isInsecureHttp: Boolean
        get() = security != null && security != McpEndpointSecurity.HTTPS
}

/**
 * Classifies one user-configured MCP endpoint and owns the complete cleartext policy.
 *
 * Private-network HTTP approval is tied to the canonical origin, including its port.
 * Callers must evaluate every request target and redirect rather than treating an earlier
 * URL validation as a general network grant.
 */
object McpEndpointPolicy {
    /**
     * Produces a status-safe endpoint label. Credentials, query parameters, and fragments
     * are never reflected into runtime snapshots, including for rejected configurations.
     */
    internal fun safeDisplayUrl(rawUrl: String, canonicalUrl: String? = null): String {
        val parsed = (canonicalUrl ?: rawUrl.trim()).toHttpUrlOrNull()
            ?: return INVALID_ENDPOINT_DISPLAY
        return parsed.canonicalOrigin() + parsed.encodedPath
    }

    /**
     * Returns an opaque, stable identity for one effective MCP server configuration.
     *
     * The full canonical endpoint and authentication material participate in the digest,
     * but none of those values are retained or displayed verbatim.
     */
    internal fun configurationFingerprint(
        server: McpHttpServerConfig,
        canonicalUrl: String?,
        effectiveServerId: String = server.id
    ): String? {
        val endpoint = canonicalUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val fields = listOf(
            FINGERPRINT_VERSION,
            effectiveServerId.trim(),
            normalizeServerName(server.serverName),
            endpoint,
            server.authToken,
            server.toolTimeoutSeconds.coerceIn(5, 300).toString(),
            server.insecureHttpAllowedOrigin?.trim().orEmpty()
        )
        val material = buildString {
            fields.forEach { field ->
                append(field.toByteArray(StandardCharsets.UTF_8).size)
                append(':')
                append(field)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
        return "$FINGERPRINT_VERSION:$digest"
    }

    fun evaluate(
        rawUrl: String,
        authToken: String,
        insecureHttpAllowedOrigin: String?
    ): McpEndpointDecision {
        val trimmedUrl = rawUrl.trim()
        if (trimmedUrl.isBlank()) {
            return rejected(
                issue = McpEndpointIssue.URL_REQUIRED,
                message = "MCP server URL is required"
            )
        }
        if (hasAuthorityUserInfo(trimmedUrl)) {
            return rejected(
                issue = McpEndpointIssue.USERINFO_NOT_ALLOWED,
                message = "MCP server URL must not contain credentials"
            )
        }

        val parsed = trimmedUrl.toHttpUrlOrNull()
            ?: return invalidUrlDecision(trimmedUrl)
        val scheme = parsed.scheme.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") {
            return rejected(
                issue = McpEndpointIssue.UNSUPPORTED_SCHEME,
                message = "MCP server URL must use http or https"
            )
        }
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            return rejected(
                issue = McpEndpointIssue.USERINFO_NOT_ALLOWED,
                message = "MCP server URL must not contain credentials"
            )
        }
        if (parsed.fragment != null) {
            return rejected(
                issue = McpEndpointIssue.FRAGMENT_NOT_ALLOWED,
                message = "MCP server URL must not contain a fragment"
            )
        }
        if (parsed.queryParameterNames.any(::isSensitiveQueryParameter)) {
            return rejected(
                issue = McpEndpointIssue.QUERY_CREDENTIAL_NOT_ALLOWED,
                message = "MCP credentials must use the protected auth-token field, not URL query parameters"
            )
        }

        val canonicalUrl = parsed.toString()
        val canonicalOrigin = parsed.canonicalOrigin()
        val networkScope = classifyNetworkScope(parsed.host)
        if (scheme == "https") {
            return McpEndpointDecision(
                disposition = McpEndpointDisposition.ALLOWED,
                security = McpEndpointSecurity.HTTPS,
                networkScope = networkScope,
                canonicalUrl = canonicalUrl,
                canonicalOrigin = canonicalOrigin,
                message = "Secure HTTPS endpoint"
            )
        }

        val security = classifyHttpHost(parsed.host)
            ?: return McpEndpointDecision(
                disposition = McpEndpointDisposition.REJECTED,
                canonicalUrl = canonicalUrl,
                canonicalOrigin = canonicalOrigin,
                networkScope = networkScope,
                issue = McpEndpointIssue.PUBLIC_HTTP_NOT_ALLOWED,
                message = "Use HTTPS for public or hostname-based MCP endpoints"
            )

        if (security == McpEndpointSecurity.PRIVATE_LAN_HTTP) {
            if (authToken.isNotBlank()) {
                return McpEndpointDecision(
                    disposition = McpEndpointDisposition.ACTION_REQUIRED,
                    security = security,
                    networkScope = networkScope,
                    canonicalUrl = canonicalUrl,
                    canonicalOrigin = canonicalOrigin,
                    issue = McpEndpointIssue.AUTH_REQUIRES_HTTPS,
                    message = "Authentication tokens require HTTPS for non-loopback MCP endpoints"
                )
            }
            if (insecureHttpAllowedOrigin?.trim() != canonicalOrigin) {
                return McpEndpointDecision(
                    disposition = McpEndpointDisposition.ACTION_REQUIRED,
                    security = security,
                    networkScope = networkScope,
                    canonicalUrl = canonicalUrl,
                    canonicalOrigin = canonicalOrigin,
                    issue = McpEndpointIssue.INSECURE_HTTP_CONFIRMATION_REQUIRED,
                    message = "Confirm unencrypted LAN HTTP access to $canonicalOrigin"
                )
            }
            return McpEndpointDecision(
                disposition = McpEndpointDisposition.ALLOWED,
                security = security,
                networkScope = networkScope,
                canonicalUrl = canonicalUrl,
                canonicalOrigin = canonicalOrigin,
                message = "Unencrypted LAN HTTP endpoint",
                warning = "Traffic to $canonicalOrigin is not encrypted"
            )
        }

        return McpEndpointDecision(
            disposition = McpEndpointDisposition.ALLOWED,
            security = security,
            networkScope = networkScope,
            canonicalUrl = canonicalUrl,
            canonicalOrigin = canonicalOrigin,
            message = "Unencrypted local HTTP endpoint",
            warning = "Local MCP HTTP traffic is not encrypted"
        )
    }

    private fun invalidUrlDecision(rawUrl: String): McpEndpointDecision {
        val explicitScheme = rawUrl.substringBefore(':', missingDelimiterValue = "")
            .lowercase(Locale.US)
        return if (explicitScheme.isNotBlank() && explicitScheme !in setOf("http", "https")) {
            rejected(
                issue = McpEndpointIssue.UNSUPPORTED_SCHEME,
                message = "MCP server URL must use http or https"
            )
        } else {
            rejected(
                issue = McpEndpointIssue.INVALID_URL,
                message = "MCP server URL is invalid"
            )
        }
    }

    private fun hasAuthorityUserInfo(rawUrl: String): Boolean {
        val authorityAndRest = rawUrl.substringAfter("://", missingDelimiterValue = "")
        if (authorityAndRest.isEmpty()) return false
        val authority = authorityAndRest
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        return '@' in authority
    }

    private fun normalizeServerName(input: String): String = input.trim()
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9_\\-]+"), "_")
        .trim('_')
        .take(40)
        .ifBlank { "default" }

    private fun isSensitiveQueryParameter(name: String): Boolean {
        val normalized = name.lowercase(Locale.US).filter(Char::isLetterOrDigit)
        return listOf(
            "token",
            "secret",
            "password",
            "credential",
            "authorization",
            "apikey",
            "accesskey",
            "signature"
        ).any(normalized::contains)
    }

    private fun classifyHttpHost(host: String): McpEndpointSecurity? {
        val normalizedHost = host.lowercase(Locale.US)
        if (normalizedHost == "localhost" || normalizedHost == "::1") {
            return McpEndpointSecurity.LOOPBACK_HTTP
        }
        if (isPrivateIpv6(normalizedHost)) return McpEndpointSecurity.PRIVATE_LAN_HTTP
        val ipv4 = parseIpv4(normalizedHost) ?: return null
        if (ipv4[0] == 127) return McpEndpointSecurity.LOOPBACK_HTTP
        if (ipv4.contentEquals(intArrayOf(10, 0, 2, 2)) ||
            ipv4.contentEquals(intArrayOf(10, 0, 3, 2))
        ) {
            return McpEndpointSecurity.EMULATOR_HTTP
        }
        val isPrivate = ipv4[0] == 10 ||
            (ipv4[0] == 172 && ipv4[1] in 16..31) ||
            (ipv4[0] == 192 && ipv4[1] == 168)
        return if (isPrivate) McpEndpointSecurity.PRIVATE_LAN_HTTP else null
    }

    private fun classifyNetworkScope(host: String): McpEndpointNetworkScope {
        val normalizedHost = host.lowercase(Locale.US)
        if (normalizedHost == "localhost" || normalizedHost == "::1") {
            return McpEndpointNetworkScope.LOOPBACK
        }
        if (isPrivateIpv6(normalizedHost)) return McpEndpointNetworkScope.PRIVATE_NETWORK
        val ipv4 = parseIpv4(normalizedHost)
        if (ipv4 != null) {
            if (ipv4[0] == 127) return McpEndpointNetworkScope.LOOPBACK
            if (ipv4.contentEquals(intArrayOf(10, 0, 2, 2)) ||
                ipv4.contentEquals(intArrayOf(10, 0, 3, 2))
            ) {
                return McpEndpointNetworkScope.EMULATOR
            }
            if (ipv4[0] == 10 ||
                (ipv4[0] == 172 && ipv4[1] in 16..31) ||
                (ipv4[0] == 192 && ipv4[1] == 168)
            ) {
                return McpEndpointNetworkScope.PRIVATE_NETWORK
            }
        }
        if (normalizedHost.endsWith(".local")) {
            return McpEndpointNetworkScope.PRIVATE_NETWORK
        }
        return McpEndpointNetworkScope.EXTERNAL
    }

    private fun isPrivateIpv6(host: String): Boolean {
        if (':' !in host) return false
        val firstHextet = host.substringBefore(':').toIntOrNull(16) ?: return false
        val uniqueLocal = (firstHextet and 0xfe00) == 0xfc00
        val linkLocal = (firstHextet and 0xffc0) == 0xfe80
        return uniqueLocal || linkLocal
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val values = IntArray(4)
        parts.forEachIndexed { index, part ->
            if (part.isEmpty() || part.any { !it.isDigit() }) return null
            values[index] = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
        }
        return values
    }

    private fun HttpUrl.canonicalOrigin(): String {
        val hostPart = if (host.contains(':')) "[$host]" else host
        val defaultPort = (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
        return buildString {
            append(scheme)
            append("://")
            append(hostPart)
            if (!defaultPort) {
                append(':')
                append(port)
            }
        }
    }

    private fun rejected(
        issue: McpEndpointIssue,
        message: String
    ): McpEndpointDecision {
        return McpEndpointDecision(
            disposition = McpEndpointDisposition.REJECTED,
            issue = issue,
            message = message
        )
    }

    private const val INVALID_ENDPOINT_DISPLAY = "<invalid endpoint>"
    private const val FINGERPRINT_VERSION = "mcp-config-v1"
}
