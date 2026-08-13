package com.palmclaw.channels

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class FeishuAuthenticationProbeTest {
    @Test
    fun `tenant token response accepts a complete authenticated result`() {
        val result = FeishuTenantTokenResponseMapper.map(
            httpStatus = 200,
            responseBody = """{"code":0,"tenant_access_token":"tenant-token","expire":7200}"""
        )

        assertTrue(result is FeishuTenantTokenResult.Success)
        result as FeishuTenantTokenResult.Success
        assertEquals("tenant-token", result.accessToken)
        assertEquals(7_200L, result.expiresInSeconds)
    }

    @Test
    fun `common invalid app credentials are non-retryable authentication failures`() {
        val responses = listOf(
            """{"code":10003,"msg":"app id or app secret is invalid"}""",
            """{"code":10003,"msg":"invalid app_id or app_secret"}""",
            """{"code":10015,"msg":"wrong app secret"}""",
            """{"code":99991543,"msg":"app_id or app_secret does not exist"}"""
        )

        responses.forEach { body ->
            val result = FeishuTenantTokenResponseMapper.map(200, body)

            assertTrue(result is FeishuTenantTokenResult.Failure)
            result as FeishuTenantTokenResult.Failure
            assertEquals(ChannelRuntimeErrorCode.AUTHENTICATION_FAILED, result.error.code)
            assertFalse(result.error.retryable)
        }
    }

    @Test
    fun `http authentication failures are non-retryable without parsing a body`() {
        listOf(401, 403).forEach { status ->
            val result = FeishuTenantTokenResponseMapper.map(status, "not-json")

            assertTrue(result is FeishuTenantTokenResult.Failure)
            result as FeishuTenantTokenResult.Failure
            assertEquals(ChannelRuntimeErrorCode.AUTHENTICATION_FAILED, result.error.code)
            assertFalse(result.error.retryable)
        }
    }

    @Test
    fun `rate limiting remains retryable`() {
        val result = FeishuTenantTokenResponseMapper.map(
            httpStatus = 429,
            responseBody = """{"code":99991400,"msg":"request trigger frequency limit"}"""
        )

        assertTrue(result is FeishuTenantTokenResult.Failure)
        result as FeishuTenantTokenResult.Failure
        assertEquals(ChannelRuntimeErrorCode.RATE_LIMITED, result.error.code)
        assertTrue(result.error.retryable)
    }

    @Test
    fun `server failures remain retryable`() {
        val result = FeishuTenantTokenResponseMapper.map(
            httpStatus = 503,
            responseBody = """{"code":1,"msg":"service unavailable"}"""
        )

        assertTrue(result is FeishuTenantTokenResult.Failure)
        result as FeishuTenantTokenResult.Failure
        assertEquals(ChannelRuntimeErrorCode.UNKNOWN, result.error.code)
        assertTrue(result.error.retryable)
    }

    @Test
    fun `SDK credential exception text maps to a non-retryable authentication failure`() {
        val error = FeishuAuthenticationErrorMapper.fromThrowable(
            IllegalStateException("invalid app_id or app_secret")
        )

        assertEquals(ChannelRuntimeErrorCode.AUTHENTICATION_FAILED, error.code)
        assertFalse(error.retryable)
    }

    @Test
    fun `probe maps network exceptions without making a network call`() = runBlocking {
        var calls = 0
        val probe = FeishuTenantAccessTokenProbe {
            calls += 1
            throw SocketTimeoutException("timed out")
        }

        val result = probe.authenticate()

        assertEquals(1, calls)
        assertTrue(result is FeishuAuthenticationProbeResult.Failure)
        result as FeishuAuthenticationProbeResult.Failure
        assertEquals(ChannelRuntimeErrorCode.NETWORK_UNAVAILABLE, result.error.code)
        assertTrue(result.error.retryable)
    }

    @Test
    fun `probe preserves a typed credential rejection`() = runBlocking {
        val expected = NormalizedChannelError(ChannelRuntimeErrorCode.AUTHENTICATION_FAILED)
        val probe = FeishuTenantAccessTokenProbe {
            throw FeishuTenantTokenRequestException(expected)
        }

        val result = probe.authenticate()

        assertEquals(FeishuAuthenticationProbeResult.Failure(expected), result)
    }

    @Test
    fun `cancelling a pending token call cancels its transport`() = runBlocking {
        val call = PendingFeishuTokenHttpCall()
        val request = launch { call.awaitResponse() }
        yield()

        request.cancelAndJoin()

        assertEquals(1, call.cancelCount)
    }

    private class PendingFeishuTokenHttpCall : FeishuTokenHttpCall {
        var cancelCount: Int = 0

        override fun enqueue(callback: FeishuTokenHttpCallback) = Unit

        override fun cancel() {
            cancelCount += 1
        }
    }
}
