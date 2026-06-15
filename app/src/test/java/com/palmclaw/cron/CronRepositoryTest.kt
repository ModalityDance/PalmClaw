package com.palmclaw.cron

import com.palmclaw.storage.dao.CronJobDao
import com.palmclaw.storage.entities.CronJobEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CronRepositoryTest {
    @Test
    fun `listDueJobs returns only enabled due jobs ordered by next run`() = runBlocking {
        val dao = FakeCronJobDao()
        val repository = CronRepository(dao)
        dao.upsert(entity(id = "late", nextRunAtMs = 200, createdAtMs = 2))
        dao.upsert(entity(id = "early", nextRunAtMs = 100, createdAtMs = 3))
        dao.upsert(entity(id = "disabled", enabled = false, nextRunAtMs = 50, createdAtMs = 1))
        dao.upsert(entity(id = "future", nextRunAtMs = 300, createdAtMs = 4))
        dao.upsert(entity(id = "null-next", nextRunAtMs = null, createdAtMs = 5))

        val due = repository.listDueJobs(triggerAtMs = 200)

        assertEquals(listOf("early", "late"), due.map { it.id })
    }

    @Test
    fun `nextWakeAtMs ignores disabled and null next run jobs`() = runBlocking {
        val dao = FakeCronJobDao()
        val repository = CronRepository(dao)
        dao.upsert(entity(id = "disabled", enabled = false, nextRunAtMs = 50))
        dao.upsert(entity(id = "null-next", nextRunAtMs = null))
        assertNull(repository.nextWakeAtMs())

        dao.upsert(entity(id = "wake-2", nextRunAtMs = 200))
        dao.upsert(entity(id = "wake-1", nextRunAtMs = 100))

        assertEquals(100L, repository.nextWakeAtMs())
        assertEquals(4, repository.countJobs())
    }

    private class FakeCronJobDao : CronJobDao {
        private val jobs = linkedMapOf<String, CronJobEntity>()

        override suspend fun getAll(): List<CronJobEntity> {
            return jobs.values.sortedWith(
                compareBy<CronJobEntity> { it.nextRunAtMs ?: Long.MAX_VALUE }
                    .thenBy { it.createdAtMs }
            )
        }

        override suspend fun getDueJobs(triggerAtMs: Long): List<CronJobEntity> {
            return jobs.values
                .filter { it.enabled && it.nextRunAtMs != null && it.nextRunAtMs <= triggerAtMs }
                .sortedWith(compareBy<CronJobEntity> { it.nextRunAtMs ?: Long.MAX_VALUE }.thenBy { it.createdAtMs })
        }

        override suspend fun getNextWakeAtMs(): Long? {
            return jobs.values.asSequence()
                .filter { it.enabled }
                .mapNotNull { it.nextRunAtMs }
                .minOrNull()
        }

        override suspend fun countJobs(): Int = jobs.size

        override suspend fun getById(jobId: String): CronJobEntity? = jobs[jobId]

        override suspend fun upsert(job: CronJobEntity) {
            jobs[job.id] = job
        }

        override suspend fun deleteById(jobId: String) {
            jobs.remove(jobId)
        }
    }

    private fun entity(
        id: String,
        enabled: Boolean = true,
        nextRunAtMs: Long?,
        createdAtMs: Long = 1
    ): CronJobEntity {
        return CronJobEntity(
            id = id,
            name = id,
            enabled = enabled,
            scheduleKind = CronKinds.AT,
            scheduleAtMs = nextRunAtMs,
            scheduleEveryMs = null,
            scheduleExpr = null,
            scheduleTz = null,
            payloadKind = "agent_turn",
            payloadMessage = "message",
            payloadDeliver = false,
            payloadChannel = null,
            payloadTo = null,
            payloadSessionId = null,
            nextRunAtMs = nextRunAtMs,
            lastRunAtMs = null,
            lastStatus = null,
            lastError = null,
            createdAtMs = createdAtMs,
            updatedAtMs = createdAtMs,
            deleteAfterRun = false
        )
    }
}
