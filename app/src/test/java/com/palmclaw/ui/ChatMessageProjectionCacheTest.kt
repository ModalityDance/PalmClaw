package com.palmclaw.ui

import com.palmclaw.storage.entities.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ChatMessageProjectionCacheTest {
    private var projectionCalls = 0
    private val cache = ChatMessageProjectionCache(
        initialPageSize = 3,
        olderPageSize = 2,
        projectMessages = { _, entities ->
            projectionCalls += 1
            entities.map { entity ->
                UiMessage(
                    id = entity.id,
                    role = entity.role,
                    content = entity.content,
                    createdAt = entity.createdAt
                )
            }
        }
    )

    @Test
    fun `replaceRecent returns cached projection when entities are unchanged`() = runBlocking {
        val first = message(id = 1L, content = "first")
        val second = message(id = 2L, content = "second")

        val initial = cache.replaceRecent("session-1", listOf(first, second))
        val repeated = cache.replaceRecent("session-1", listOf(first, second))

        assertEquals(1, projectionCalls)
        assertSame(initial.messages, repeated.messages)
        assertEquals(listOf("first", "second"), repeated.messages.map { it.content })
    }

    @Test
    fun `replaceRecent preserves optimistic message when stale database flow arrives`() = runBlocking {
        cache.replaceRecent("session-1", emptyList())
        val optimistic = UiMessage(id = -1L, role = "user", content = "hello", createdAt = 100L)

        val afterOptimistic = cache.appendOptimistic("session-1", optimistic)
        val afterStaleFlow = cache.replaceRecent("session-1", emptyList())

        assertEquals(listOf("hello"), afterOptimistic.messages.map { it.content })
        assertEquals(listOf("hello"), afterStaleFlow.messages.map { it.content })
        assertTrue(afterStaleFlow.messages.single().id < 0L)
    }

    @Test
    fun `replaceRecent removes optimistic duplicate when database confirms user message`() = runBlocking {
        cache.replaceRecent("session-1", emptyList())
        cache.appendOptimistic(
            "session-1",
            UiMessage(id = -1L, role = "user", content = "hello", createdAt = 100L)
        )

        val confirmed = cache.replaceRecent(
            "session-1",
            listOf(message(id = 42L, role = "user", content = "hello", createdAt = 101L))
        )

        assertEquals(1, confirmed.messages.size)
        assertEquals(42L, confirmed.messages.single().id)
        assertEquals("hello", confirmed.messages.single().content)
        assertFalse(confirmed.messages.any { it.id < 0L })
    }

    @Test
    fun `prependOlder keeps existing recent messages and prepends older page`() = runBlocking {
        cache.replaceRecent(
            "session-1",
            listOf(
                message(id = 3L, content = "three", createdAt = 300L),
                message(id = 4L, content = "four", createdAt = 400L),
                message(id = 5L, content = "five", createdAt = 500L)
            )
        )

        val result = cache.prependOlder(
            "session-1",
            listOf(
                message(id = 1L, content = "one", createdAt = 100L),
                message(id = 2L, content = "two", createdAt = 200L)
            )
        )

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), result.entities.map { it.id })
        assertEquals(listOf("one", "two", "three", "four", "five"), result.messages.map { it.content })
        assertTrue(result.canLoadOlder)
    }

    @Test
    fun `snapshot returns projected messages for visited session`() = runBlocking {
        cache.replaceRecent("session-1", listOf(message(id = 1L, content = "cached")))

        val snapshot = cache.snapshot("session-1")

        assertEquals("cached", snapshot?.messages?.single()?.content)
        assertNull(cache.snapshot("session-2"))
    }

    @Test
    fun `clearSession drops only target session cache`() = runBlocking {
        cache.replaceRecent("session-1", listOf(message(id = 1L, content = "one")))
        cache.replaceRecent("session-2", listOf(message(id = 2L, content = "two")))

        cache.clear("session-1")

        assertNull(cache.snapshot("session-1"))
        assertEquals("two", cache.snapshot("session-2")?.messages?.single()?.content)
    }

    private fun message(
        id: Long,
        role: String = "assistant",
        content: String,
        createdAt: Long = id
    ): MessageEntity {
        return MessageEntity(
            id = id,
            sessionId = "session-1",
            role = role,
            content = content,
            createdAt = createdAt
        )
    }
}
