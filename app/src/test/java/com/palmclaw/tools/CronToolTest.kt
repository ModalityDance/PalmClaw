package com.palmclaw.tools

import com.palmclaw.cron.CronFieldUpdate
import com.palmclaw.cron.CronJob
import com.palmclaw.cron.CronJobState
import com.palmclaw.cron.CronJobUpdate
import com.palmclaw.cron.CronJobUpdatePlanner
import com.palmclaw.cron.CronKinds
import com.palmclaw.cron.CronPayload
import com.palmclaw.cron.CronSchedule
import com.palmclaw.cron.CronServiceStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CronToolTest {
    @Test
    fun `schema exposes structured read and update actions`() {
        val schema = cronToolSchema()
        val properties = schema["properties"]!!.jsonObject
        val actions = properties["action"]!!.jsonObject["enum"] as JsonArray

        assertEquals(
            listOf(
                "add",
                "list",
                "get",
                "update",
                "remove",
                "enable_job",
                "run_now",
                "status",
                "set_enabled",
                "set_config"
            ),
            actions.map { it.jsonPrimitive.content }
        )
        assertTrue(properties.containsKey("offset"))
        assertTrue(properties.containsKey("limit"))
    }

    @Test
    fun `add returns a complete structured job`() = runBlocking {
        val backend = FakeCronBackend()
        val tool = tool(backend)

        val result = tool.run(
            """{
              "action":"add",
              "name":"Daily review",
              "message":"Review the experiment log",
              "every_seconds":60,
              "deliver":true,
              "session_id":"research"
            }""".trimIndent()
        )

        assertFalse(result.content, result.isError)
        val job = result.body()["job"]!!.jsonObject
        assertEquals("Daily review", job["name"]!!.jsonPrimitive.content)
        assertEquals("every", job["schedule"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals(60_000L, job["schedule"]!!.jsonObject["every_ms"]!!.jsonPrimitive.content.toLong())
        assertEquals(
            "Review the experiment log",
            job["payload"]!!.jsonObject["message"]!!.jsonPrimitive.content
        )
        assertEquals("research", job["payload"]!!.jsonObject["session_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `list is paged and get explicitly bounds a legacy long message`() = runBlocking {
        val longMessage = "x".repeat(4_000)
        val backend = FakeCronBackend().apply {
            repeat(7) { index ->
                seed(job("job-$index", longMessage, nextRunAtMs = index.toLong()))
            }
        }
        val tool = tool(backend)

        val firstPageResult = tool.run("""{"action":"list","limit":3}""")
        val firstPage = firstPageResult.body()
        val secondPage = tool.run("""{"action":"list","limit":3,"offset":3}""").body()
        val loadedResult = tool.run("""{"action":"get","job_id":"job-0"}""")
        val loaded = loadedResult.body()

        assertTrue(firstPageResult.content.length < 5_000)
        assertTrue(loadedResult.content.length < 5_000)
        assertEquals(7, firstPage["total_count"]!!.jsonPrimitive.content.toInt())
        assertEquals(3, (firstPage["jobs"] as JsonArray).size)
        assertTrue(firstPage["has_more"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(3, firstPage["next_offset"]!!.jsonPrimitive.content.toInt())
        assertEquals(3, (secondPage["jobs"] as JsonArray).size)
        val summaryPayload = (firstPage["jobs"] as JsonArray).first().jsonObject["payload"]!!.jsonObject
        assertTrue(summaryPayload.containsKey("message_preview"))
        assertFalse(summaryPayload.containsKey("message"))
        assertEquals(
            longMessage.take(3_000),
            loaded["job"]!!.jsonObject["payload"]!!.jsonObject["message"]!!.jsonPrimitive.content
        )
        assertTrue(
            loaded["job"]!!.jsonObject["payload"]!!.jsonObject["message_truncated"]!!
                .jsonPrimitive.content.toBoolean()
        )
    }

    @Test
    fun `update changes supplied fields and clears an explicit nullable target`() = runBlocking {
        val backend = FakeCronBackend().apply {
            seed(job("job-1", "original", nextRunAtMs = 60_000L))
        }
        val tool = tool(backend)

        val result = tool.run(
            """{
              "action":"update",
              "job_id":"job-1",
              "message":"updated",
              "channel":null
            }""".trimIndent()
        )

        assertFalse(result.content, result.isError)
        val job = result.body()["job"]!!.jsonObject
        assertEquals("updated", job["payload"]!!.jsonObject["message"]!!.jsonPrimitive.content)
        assertTrue(job["payload"]!!.jsonObject["channel"] is JsonNull)
        assertEquals(60_000L, job["state"]!!.jsonObject["next_run_at_ms"]!!.jsonPrimitive.content.toLong())
    }

    @Test
    fun `schedule update recomputes next run and preserves last result`() = runBlocking {
        val backend = FakeCronBackend().apply {
            seed(
                job("job-1", "original", nextRunAtMs = 60_000L).copy(
                    state = CronJobState(
                        nextRunAtMs = 60_000L,
                        lastRunAtMs = 10L,
                        lastStatus = "ok",
                        lastError = "old"
                    )
                )
            )
        }
        val tool = tool(backend)

        val result = tool.run(
            """{"action":"update","job_id":"job-1","every_seconds":120}"""
        )

        assertFalse(result.content, result.isError)
        val state = result.body()["job"]!!.jsonObject["state"]!!.jsonObject
        assertEquals(122_000L, state["next_run_at_ms"]!!.jsonPrimitive.content.toLong())
        assertEquals(10L, state["last_run_at_ms"]!!.jsonPrimitive.content.toLong())
        assertEquals("old", state["last_error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `missing jobs and empty updates return structured errors`() = runBlocking {
        val tool = tool(FakeCronBackend())

        val missing = tool.run("""{"action":"get","job_id":"missing"}""")
        val empty = tool.run("""{"action":"update","job_id":"missing"}""")

        assertTrue(missing.isError)
        assertEquals("not_found", missing.body()["code"]!!.jsonPrimitive.content)
        assertTrue(empty.isError)
        assertEquals("invalid_arguments", empty.body()["code"]!!.jsonPrimitive.content)
    }

    private fun tool(backend: FakeCronBackend) = CronTool(
        cron = backend,
        onSetServiceEnabled = { backend.serviceEnabled = it }
    )

    private fun ToolResult.body(): JsonObject = Json.parseToJsonElement(content).jsonObject

    private fun job(id: String, message: String, nextRunAtMs: Long) = CronJob(
        id = id,
        name = "Job $id",
        enabled = true,
        schedule = CronSchedule(kind = CronKinds.EVERY, everyMs = 60_000L),
        payload = CronPayload(
            message = message,
            deliver = true,
            channel = "email",
            to = "person@example.com",
            sessionId = "session-1"
        ),
        state = CronJobState(nextRunAtMs = nextRunAtMs),
        createdAtMs = 100L,
        updatedAtMs = 100L
    )
}

private class FakeCronBackend : CronToolBackend {
    private val jobs = linkedMapOf<String, CronJob>()
    var serviceEnabled: Boolean = true
    private var nowMs: Long = 2_000L

    fun seed(job: CronJob) {
        jobs[job.id] = job
    }

    override fun isExecutingJob(): Boolean = false
    override fun start() {
        serviceEnabled = true
    }

    override fun stop() {
        serviceEnabled = false
    }

    override suspend fun addJob(
        name: String,
        schedule: CronSchedule,
        payload: CronPayload,
        deleteAfterRun: Boolean
    ): CronJob {
        val id = "job-${jobs.size + 1}"
        val job = CronJob(
            id = id,
            name = name,
            schedule = schedule,
            payload = payload,
            state = CronJobState(nextRunAtMs = nextRun(schedule)),
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
            deleteAfterRun = deleteAfterRun
        )
        jobs[id] = job
        return job
    }

    override suspend fun listJobs(includeDisabled: Boolean): List<CronJob> = jobs.values
        .filter { includeDisabled || it.enabled }
        .sortedBy { it.state.nextRunAtMs ?: Long.MAX_VALUE }

    override suspend fun getJob(jobId: String): CronJob? = jobs[jobId]

    override suspend fun updateJob(jobId: String, update: CronJobUpdate): CronJob? {
        val existing = jobs[jobId] ?: return null
        val updated = CronJobUpdatePlanner.apply(
            existing = existing,
            update = update,
            nowMs = nowMs,
            validateSchedule = {},
            nextRunAtMs = ::nextRun
        )
        jobs[jobId] = updated
        return updated
    }

    override suspend fun removeJob(jobId: String): Boolean = jobs.remove(jobId) != null

    override suspend fun enableJob(jobId: String, enabled: Boolean): CronJob? = updateJob(
        jobId,
        CronJobUpdate(enabled = CronFieldUpdate.Set(enabled))
    )

    override suspend fun runJob(jobId: String, force: Boolean): Boolean =
        jobs[jobId]?.let { it.enabled || force } ?: false

    override suspend fun status(): CronServiceStatus = CronServiceStatus(
        enabled = serviceEnabled,
        jobs = jobs.size,
        nextWakeAtMs = jobs.values.filter { it.enabled }.mapNotNull { it.state.nextRunAtMs }.minOrNull(),
        minEveryMs = 1_000L,
        maxJobs = 50
    )

    private fun nextRun(schedule: CronSchedule): Long? = when (schedule.kind) {
        CronKinds.AT -> schedule.atMs
        CronKinds.EVERY -> schedule.everyMs?.let { nowMs + it }
        CronKinds.CRON -> nowMs + 60_000L
        else -> null
    }
}
