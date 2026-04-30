package com.palmclaw.workspace

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionUiLifecycleServiceTest {

    @Test
    fun `createSession trims title and delegates create call`() = runBlocking {
        val calls = mutableListOf<String>()
        val service = buildService(
            createSession = { sessionId, title -> calls += "create:$sessionId:$title" },
            sessionIdGenerator = { "session:test-1" }
        )

        val sessionId = service.createSession("  Team Plan  ")

        assertEquals("session:test-1", sessionId)
        assertEquals(listOf("create:session:test-1:Team Plan"), calls)
    }

    @Test
    fun `renameSession validates input and delegates rename`() = runBlocking {
        val calls = mutableListOf<String>()
        val service = buildService(
            renameSession = { sessionId, title -> calls += "rename:$sessionId:$title" }
        )

        service.renameSession("  session:1  ", "  Renamed  ")

        assertEquals(listOf("rename:session:1:Renamed"), calls)
    }

    @Test
    fun `deleteSession refreshes gateway runtime after lifecycle delete`() = runBlocking {
        val calls = mutableListOf<String>()
        val service = buildService(
            deleteSession = { sessionId -> calls += "delete:$sessionId" },
            refreshGatewayRuntimeConfig = { calls += "refresh-gateway" }
        )

        service.deleteSession(" session:2 ")

        assertEquals(
            listOf("delete:session:2", "refresh-gateway"),
            calls
        )
    }

    @Test
    fun `deleteSession does not refresh gateway runtime when lifecycle delete fails`() = runBlocking {
        val calls = mutableListOf<String>()
        val service = buildService(
            deleteSession = { throw IllegalStateException("boom") },
            refreshGatewayRuntimeConfig = { calls += "refresh-gateway" }
        )

        val error = runCatching {
            service.deleteSession(" session:2 ")
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `ensureLocalSessionExists delegates to lifecycle ensure`() = runBlocking {
        var called = false
        val service = buildService(
            ensureLocalSession = { called = true }
        )

        service.ensureLocalSessionExists()

        assertTrue(called)
    }

    @Test
    fun `createSession rejects blank title`() = runBlocking {
        val service = buildService()

        val error = runCatching {
            service.createSession("   ")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("session title is required", error?.message)
    }

    private fun buildService(
        createSession: suspend (String, String) -> Unit = { _, _ -> },
        renameSession: suspend (String, String) -> Unit = { _, _ -> },
        deleteSession: suspend (String) -> Unit = {},
        ensureLocalSession: suspend () -> Unit = {},
        refreshGatewayRuntimeConfig: () -> Unit = {},
        sessionIdGenerator: () -> String = { "session:generated" }
    ): SessionUiLifecycleService {
        return SessionUiLifecycleService(
            createSessionAction = createSession,
            renameSessionAction = renameSession,
            deleteSessionAction = deleteSession,
            ensureLocalSessionAction = ensureLocalSession,
            refreshGatewayRuntimeConfig = refreshGatewayRuntimeConfig,
            sessionIdGenerator = sessionIdGenerator
        )
    }
}
