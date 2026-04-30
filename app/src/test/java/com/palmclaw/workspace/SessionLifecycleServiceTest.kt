package com.palmclaw.workspace

import com.palmclaw.config.AppSession
import com.palmclaw.storage.SessionRepository
import com.palmclaw.storage.dao.MessageDao
import com.palmclaw.storage.dao.SessionDao
import com.palmclaw.storage.entities.MessageEntity
import com.palmclaw.storage.entities.SessionEntity
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionLifecycleServiceTest {
    private lateinit var rootDir: File
    private lateinit var sessionDao: FakeSessionDao
    private lateinit var messageDao: FakeMessageDao
    private lateinit var repository: SessionRepository
    private lateinit var workspaceManager: SessionWorkspaceManager

    @Before
    fun setUp() {
        rootDir = Files.createTempDirectory("session-lifecycle-service-test").toFile()
        sessionDao = FakeSessionDao()
        messageDao = FakeMessageDao()
        repository = SessionRepository(sessionDao, messageDao)
        workspaceManager = SessionWorkspaceManager(rootDir)
    }

    @After
    fun tearDown() {
        rootDir.deleteRecursively()
    }

    @Test
    fun `ensureLocalSession creates local session and workspace`() = runBlocking {
        val service = SessionLifecycleService(repository, workspaceManager)

        service.ensureLocalSession()

        val session = repository.getSession(AppSession.LOCAL_SESSION_ID)
        val snapshot = workspaceManager.getSnapshot(AppSession.LOCAL_SESSION_ID)
        assertNotNull(session)
        assertEquals(AppSession.LOCAL_SESSION_TITLE, session?.title)
        assertNotNull(snapshot)
        assertTrue(File(snapshot!!.workspaceRoot).exists())
    }

    @Test
    fun `create rename and delete session keep workspace metadata in sync`() = runBlocking {
        val clearedBindings = mutableListOf<String>()
        val removedCronJobs = mutableListOf<String>()
        val service = SessionLifecycleService(
            sessionRepository = repository,
            workspaceManager = workspaceManager,
            clearSessionChannelBinding = { clearedBindings += it },
            listCronJobIdsForSession = { listOf("cron-1", "cron-2") },
            removeCronJob = { removedCronJobs += it }
        )

        service.createSession("session:123", "Initial Title")
        val created = workspaceManager.getSnapshot("session:123")
        assertEquals("Initial Title", created?.sessionTitle)

        service.renameSession("session:123", "Renamed Title")
        val renamed = workspaceManager.getSnapshot("session:123")
        assertEquals(created?.workspaceRoot, renamed?.workspaceRoot)
        assertEquals("Renamed Title", renamed?.sessionTitle)

        service.deleteSession("session:123")

        assertEquals(listOf("session:123"), clearedBindings)
        assertEquals(listOf("cron-1", "cron-2"), removedCronJobs)
        assertEquals(null, repository.getSession("session:123"))
        assertFalse(File(created!!.workspaceRoot).exists())
    }

    @Test
    fun `deleteSession restores workspace if cleanup fails`() = runBlocking {
        val service = SessionLifecycleService(
            sessionRepository = repository,
            workspaceManager = workspaceManager,
            clearSessionChannelBinding = {},
            listCronJobIdsForSession = { listOf("cron-fail") },
            removeCronJob = { throw IllegalStateException("boom") }
        )
        service.createSession("session:rollback", "Rollback")
        val snapshot = workspaceManager.getSnapshot("session:rollback")!!
        File(snapshot.docsDir, "marker.txt").writeText("kept", Charsets.UTF_8)

        val failure = runCatching {
            service.deleteSession("session:rollback")
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertNotNull(repository.getSession("session:rollback"))
        assertTrue(File(snapshot.workspaceRoot).exists())
        assertEquals("kept", File(snapshot.docsDir, "marker.txt").readText(Charsets.UTF_8))
    }

    @Test
    fun `renameSession rejects local session id`() = runBlocking {
        val service = SessionLifecycleService(repository, workspaceManager)

        val error = runCatching {
            service.renameSession(AppSession.LOCAL_SESSION_ID, "Renamed")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(
            "Local session must be managed through ensureLocalSession only",
            error?.message
        )
    }

    @Test
    fun `deleteSession rejects local session id`() = runBlocking {
        val service = SessionLifecycleService(repository, workspaceManager)

        val error = runCatching {
            service.deleteSession(AppSession.LOCAL_SESSION_ID)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(
            "Local session must be managed through ensureLocalSession only",
            error?.message
        )
    }

    @Test
    fun `createSession rejects blank session id`() = runBlocking {
        val service = SessionLifecycleService(repository, workspaceManager)

        val error = runCatching {
            service.createSession("   ", "Title")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("sessionId is required", error?.message)
    }

    @Test
    fun `createSession rejects blank title`() = runBlocking {
        val service = SessionLifecycleService(repository, workspaceManager)

        val error = runCatching {
            service.createSession("session:blank-title", "   ")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("session title is required", error?.message)
    }

    @Test
    fun `renameSession rejects blank title`() = runBlocking {
        val service = SessionLifecycleService(repository, workspaceManager)

        val error = runCatching {
            service.renameSession("session:123", "   ")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("session title is required", error?.message)
    }

    private class FakeSessionDao : SessionDao {
        private val sessions = linkedMapOf<String, SessionEntity>()
        private val observed = MutableStateFlow<List<SessionEntity>>(emptyList())

        override suspend fun insert(session: SessionEntity) {
            sessions[session.id] = session
            publish()
        }

        override suspend fun getById(sessionId: String): SessionEntity? = sessions[sessionId]

        override suspend fun touch(sessionId: String, updatedAt: Long) {
            val existing = sessions[sessionId] ?: return
            sessions[sessionId] = existing.copy(updatedAt = updatedAt)
            publish()
        }

        override suspend fun rename(sessionId: String, title: String, updatedAt: Long) {
            val existing = sessions[sessionId] ?: return
            sessions[sessionId] = existing.copy(title = title, updatedAt = updatedAt)
            publish()
        }

        override suspend fun delete(sessionId: String) {
            sessions.remove(sessionId)
            publish()
        }

        override suspend fun deleteAllExcept(sessionId: String) {
            sessions.keys.filter { it != sessionId }.toList().forEach(sessions::remove)
            publish()
        }

        override fun observeAll(): Flow<List<SessionEntity>> = observed

        override suspend fun getAll(): List<SessionEntity> = sessions.values.toList()

        private fun publish() {
            observed.value = sessions.values.toList()
        }
    }

    private class FakeMessageDao : MessageDao {
        private val observed = MutableStateFlow<List<MessageEntity>>(emptyList())

        override fun observeBySession(sessionId: String): Flow<List<MessageEntity>> = observed

        override suspend fun getBySession(sessionId: String): List<MessageEntity> = emptyList()

        override suspend fun getLatestAssistantBySession(sessionId: String): MessageEntity? = null

        override suspend fun insert(message: MessageEntity): Long = 0L

        override suspend fun updateMessageContent(id: Long, content: String) = Unit

        override suspend fun appendMessageContent(id: Long, delta: String) = Unit

        override suspend fun updateToolCallJson(id: Long, toolCallJson: String?) = Unit

        override suspend fun deleteById(id: Long) = Unit

        override suspend fun clearSession(sessionId: String) = Unit

        override suspend fun moveAllMessagesToSession(targetSessionId: String): Int = 0
    }
}
