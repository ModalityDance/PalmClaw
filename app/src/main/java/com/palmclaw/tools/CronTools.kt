package com.palmclaw.tools

import com.palmclaw.config.CronConfig
import com.palmclaw.cron.CronFieldUpdate
import com.palmclaw.cron.CronJob
import com.palmclaw.cron.CronJobUpdate
import com.palmclaw.cron.CronPayload
import com.palmclaw.cron.CronSchedule
import com.palmclaw.cron.CronService
import com.palmclaw.cron.CronServiceStatus
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

fun createCronToolSet(
    cronService: CronService,
    onSetServiceEnabled: (suspend (Boolean) -> Unit)? = null,
    onUpdateConfig: (suspend (CronConfigUpdate) -> CronConfig)? = null
): List<Tool> = listOf(
    CronTool(
        cron = CronServiceToolBackend(cronService),
        onSetServiceEnabled = onSetServiceEnabled,
        onUpdateConfig = onUpdateConfig
    )
)

data class CronConfigUpdate(
    val enabled: Boolean? = null,
    val minEveryMs: Long? = null,
    val maxJobs: Int? = null
)

internal interface CronToolBackend {
    fun isExecutingJob(): Boolean
    fun start()
    fun stop()
    suspend fun addJob(
        name: String,
        schedule: CronSchedule,
        payload: CronPayload,
        deleteAfterRun: Boolean
    ): CronJob

    suspend fun listJobs(includeDisabled: Boolean): List<CronJob>
    suspend fun getJob(jobId: String): CronJob?
    suspend fun updateJob(jobId: String, update: CronJobUpdate): CronJob?
    suspend fun removeJob(jobId: String): Boolean
    suspend fun enableJob(jobId: String, enabled: Boolean): CronJob?
    suspend fun runJob(jobId: String, force: Boolean): Boolean
    suspend fun status(): CronServiceStatus
}

private class CronServiceToolBackend(
    private val service: CronService
) : CronToolBackend {
    override fun isExecutingJob(): Boolean = service.isExecutingJob()
    override fun start() = service.start()
    override fun stop() = service.stop()

    override suspend fun addJob(
        name: String,
        schedule: CronSchedule,
        payload: CronPayload,
        deleteAfterRun: Boolean
    ): CronJob = service.addJob(name, schedule, payload, deleteAfterRun)

    override suspend fun listJobs(includeDisabled: Boolean): List<CronJob> =
        service.listJobs(includeDisabled)

    override suspend fun getJob(jobId: String): CronJob? = service.getJob(jobId)

    override suspend fun updateJob(jobId: String, update: CronJobUpdate): CronJob? =
        service.updateJob(jobId, update)

    override suspend fun removeJob(jobId: String): Boolean = service.removeJob(jobId)

    override suspend fun enableJob(jobId: String, enabled: Boolean): CronJob? =
        service.enableJob(jobId, enabled)

    override suspend fun runJob(jobId: String, force: Boolean): Boolean =
        service.runJob(jobId, force)

    override suspend fun status(): CronServiceStatus = service.status()
}

internal class CronTool(
    private val cron: CronToolBackend,
    private val onSetServiceEnabled: (suspend (Boolean) -> Unit)? = null,
    private val onUpdateConfig: (suspend (CronConfigUpdate) -> CronConfig)? = null
) : Tool {
    override val name: String = "cron"
    override val description: String =
        "Manage scheduled jobs and cron policy with structured results. " +
            "action=add|list|get|update|remove|enable_job|run_now|status|set_enabled|set_config"

    override val jsonSchema: JsonObject = cronToolSchema()

    override suspend fun run(argumentsJson: String): ToolResult {
        val raw = Json.parseToJsonElement(argumentsJson).jsonObject
        val args = Json.decodeFromJsonElement<Args>(raw)
        val action = args.action.trim().lowercase(Locale.US)
        return when (action) {
            "add" -> add(args)
            "list" -> list(args)
            "get" -> get(args)
            "update" -> update(args, raw)
            "remove" -> remove(args)
            "enable_job" -> enableJob(args)
            "run_now" -> runNow(args)
            "status" -> status()
            "set_enabled" -> setEnabled(args)
            "set_config" -> setConfig(args)
            else -> cronError(
                action = action,
                code = "unsupported_action",
                message = "Unsupported action '${args.action}'.",
                nextStep = "Use one of the actions declared in the cron schema."
            )
        }
    }

    private suspend fun add(args: Args): ToolResult {
        val action = "add"
        if (cron.isExecutingJob()) {
            return cronError(
                action,
                "blocked_in_job",
                "Cannot schedule new jobs from within a cron job execution.",
                "Schedule from a normal chat turn."
            )
        }

        return runCatching {
            val message = requireText(args.message, "message", MAX_MESSAGE_LENGTH)
            val schedule = requireNotNull(parseSchedule(args, required = true))
            val payload = CronPayload(
                kind = "agent_turn",
                message = message,
                deliver = args.deliver ?: true,
                channel = normalizeOptional(args.channel),
                to = normalizeOptional(args.to),
                sessionId = normalizeOptional(args.sessionId)
            )
            val jobName = args.name?.let { requireText(it, "name", MAX_NAME_LENGTH) }
                ?: message.take(DEFAULT_NAME_LENGTH).ifBlank { "cron-job" }
            val deleteAfterRun = args.deleteAfterRun ?: (schedule.kind == "at")
            val job = cron.addJob(jobName, schedule, payload, deleteAfterRun)

            val wasEnabled = cron.status().enabled
            var autoEnabled = false
            if (!wasEnabled) {
                onSetServiceEnabled?.invoke(true) ?: cron.start()
                autoEnabled = true
            }

            cronOk(action, "Cron job created.") {
                put("auto_enabled", autoEnabled)
                put("job", job.toJson(includeMessage = true))
            }
        }.getOrElse { error ->
            cronError(
                action,
                if (error is IllegalArgumentException) "invalid_arguments" else "add_failed",
                error.message ?: error.javaClass.simpleName,
                "Check the schedule and job fields, then retry."
            )
        }
    }

    private suspend fun list(args: Args): ToolResult {
        val action = "list"
        val includeDisabled = args.includeDisabled ?: false
        val offset = args.offset ?: 0
        val limit = args.limit ?: DEFAULT_LIST_LIMIT
        if (offset < 0 || limit !in 1..MAX_LIST_LIMIT) {
            return cronError(
                action,
                "invalid_arguments",
                "offset must be >= 0 and limit must be between 1 and $MAX_LIST_LIMIT."
            )
        }
        val jobs = cron.listJobs(includeDisabled)
        val page = jobs.drop(offset).take(limit)
        val nextOffset = offset + page.size
        return cronOk(action, "Cron jobs listed.") {
            put("total_count", jobs.size)
            put("returned_count", page.size)
            put("include_disabled", includeDisabled)
            put("offset", offset)
            put("limit", limit)
            put("has_more", nextOffset < jobs.size)
            if (nextOffset < jobs.size) put("next_offset", nextOffset) else put("next_offset", JsonNull)
            putJsonArray("jobs") {
                page.forEach { add(it.toJson(includeMessage = false)) }
            }
        }
    }

    private suspend fun get(args: Args): ToolResult {
        val action = "get"
        val jobId = requiredJobId(args) ?: return missingJobId(action)
        val job = cron.getJob(jobId) ?: return notFound(action, jobId)
        return cronOk(action, "Cron job loaded.") {
            put("job", job.toJson(includeMessage = true))
        }
    }

    private suspend fun update(args: Args, raw: JsonObject): ToolResult {
        val action = "update"
        val jobId = requiredJobId(args) ?: return missingJobId(action)
        return runCatching {
            val scheduleKeys = setOf("at", "every_seconds", "cron_expr", "tz")
            val scheduleChanged = raw.keys.any(scheduleKeys::contains)
            val update = CronJobUpdate(
                name = requiredTextUpdate(raw, "name", args.name, MAX_NAME_LENGTH),
                enabled = requiredValueUpdate(raw, "enabled", args.enabled),
                schedule = if (scheduleChanged) {
                    CronFieldUpdate.Set(
                        requireNotNull(parseSchedule(args, required = true))
                    )
                } else {
                    CronFieldUpdate.Unchanged
                },
                message = requiredTextUpdate(raw, "message", args.message, MAX_MESSAGE_LENGTH),
                deliver = requiredValueUpdate(raw, "deliver", args.deliver),
                channel = nullableTextUpdate(raw, "channel", args.channel),
                to = nullableTextUpdate(raw, "to", args.to),
                sessionId = nullableTextUpdate(raw, "session_id", args.sessionId),
                deleteAfterRun = requiredValueUpdate(
                    raw,
                    "delete_after_run",
                    args.deleteAfterRun
                )
            )
            require(update.hasChanges()) { "At least one job field is required." }
            val updated = cron.updateJob(jobId, update) ?: return notFound(action, jobId)
            cronOk(action, "Cron job updated.") {
                put("job", updated.toJson(includeMessage = true))
            }
        }.getOrElse { error ->
            cronError(
                action,
                if (error is IllegalArgumentException) "invalid_arguments" else "update_failed",
                error.message ?: error.javaClass.simpleName,
                "Check the supplied fields and retry without changing job_id."
            )
        }
    }

    private suspend fun remove(args: Args): ToolResult {
        val action = "remove"
        val jobId = requiredJobId(args) ?: return missingJobId(action)
        return if (cron.removeJob(jobId)) {
            cronOk(action, "Cron job removed.") {
                put("job_id", jobId)
                put("removed", true)
            }
        } else {
            notFound(action, jobId)
        }
    }

    private suspend fun enableJob(args: Args): ToolResult {
        val action = "enable_job"
        val jobId = requiredJobId(args) ?: return missingJobId(action)
        val enabled = args.enabled ?: return cronError(
            action,
            "invalid_arguments",
            "enabled is required.",
            "Set enabled=true or false."
        )
        val updated = cron.enableJob(jobId, enabled) ?: return notFound(action, jobId)
        return cronOk(action, "Cron job enabled state updated.") {
            put("job", updated.toJson(includeMessage = true))
        }
    }

    private suspend fun runNow(args: Args): ToolResult {
        val action = "run_now"
        val jobId = requiredJobId(args) ?: return missingJobId(action)
        val force = args.force ?: false
        return if (cron.runJob(jobId, force)) {
            cronOk(action, "Cron job triggered.") {
                put("job_id", jobId)
                put("force", force)
                put("triggered", true)
            }
        } else {
            cronError(
                action,
                "run_failed",
                "The job does not exist or is disabled.",
                "Run list, or retry a disabled job with force=true."
            )
        }
    }

    private suspend fun status(): ToolResult {
        val status = cron.status()
        return cronOk("status", "Cron status loaded.") {
            put("enabled", status.enabled)
            put("jobs", status.jobs)
            putNullable("next_wake_at_ms", status.nextWakeAtMs)
            put("min_every_ms", status.minEveryMs)
            put("max_jobs", status.maxJobs)
        }
    }

    private suspend fun setEnabled(args: Args): ToolResult {
        val action = "set_enabled"
        val enabled = args.enabled ?: return cronError(
            action,
            "invalid_arguments",
            "enabled is required.",
            "Set enabled=true or false."
        )
        onSetServiceEnabled?.invoke(enabled) ?: if (enabled) cron.start() else cron.stop()
        val status = cron.status()
        return cronOk(action, "Cron service enabled state updated.") {
            put("enabled", status.enabled)
            put("jobs", status.jobs)
            putNullable("next_wake_at_ms", status.nextWakeAtMs)
        }
    }

    private suspend fun setConfig(args: Args): ToolResult {
        val action = "set_config"
        if (args.enabled == null && args.minEveryMs == null && args.maxJobs == null) {
            return cronError(
                action,
                "invalid_arguments",
                "At least one of enabled, min_every_ms, or max_jobs is required."
            )
        }
        val callback = onUpdateConfig ?: return cronError(
            action,
            "unsupported",
            "Cron config persistence is not available in this runtime.",
            "Change Cron settings from the app UI."
        )
        return runCatching {
            val updated = callback(
                CronConfigUpdate(
                    enabled = args.enabled,
                    minEveryMs = args.minEveryMs,
                    maxJobs = args.maxJobs
                )
            )
            cronOk(action, "Cron config updated.") {
                put("enabled", updated.enabled)
                put("min_every_ms", updated.minEveryMs)
                put("max_jobs", updated.maxJobs)
            }
        }.getOrElse { error ->
            cronError(
                action,
                "set_config_failed",
                error.message ?: error.javaClass.simpleName,
                "Check the value ranges and retry."
            )
        }
    }

    private fun parseSchedule(args: Args, required: Boolean): CronSchedule? {
        if (!args.tz.isNullOrBlank() && args.cronExpr.isNullOrBlank()) {
            throw IllegalArgumentException("tz can only be used with cron_expr.")
        }
        val count = listOf(
            args.everySeconds != null,
            !args.cronExpr.isNullOrBlank(),
            !args.at.isNullOrBlank()
        ).count { it }
        if (count == 0 && !required) return null
        require(count == 1) { "Exactly one schedule field is required: every_seconds, cron_expr, or at." }
        return when {
            args.everySeconds != null -> {
                require(args.everySeconds > 0) { "every_seconds must be > 0." }
                require(args.everySeconds <= Long.MAX_VALUE / 1_000L) { "every_seconds is too large." }
                CronSchedule(kind = "every", everyMs = args.everySeconds * 1_000L)
            }
            !args.cronExpr.isNullOrBlank() -> {
                val expression = args.cronExpr.trim()
                require(expression.length <= MAX_CRON_EXPRESSION_LENGTH) {
                    "cron_expr must be at most $MAX_CRON_EXPRESSION_LENGTH characters."
                }
                CronSchedule(
                    kind = "cron",
                    expr = expression,
                    tz = normalizeOptional(args.tz)
                )
            }
            else -> {
                val at = requireNotNull(args.at).trim()
                require(at.length <= MAX_AT_LENGTH) { "at must be at most $MAX_AT_LENGTH characters." }
                val atMs = parseAtToMs(at)
                    ?: throw IllegalArgumentException("Invalid at format; use ISO datetime or epoch milliseconds.")
                require(atMs > System.currentTimeMillis()) { "at must be in the future." }
                CronSchedule(kind = "at", atMs = atMs)
            }
        }
    }

    private fun parseAtToMs(input: String): Long? {
        input.toLongOrNull()?.takeIf { it > 0L }?.let { return it }
        return runCatching { Instant.parse(input).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(input).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(input).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull()
    }

    private fun requiredJobId(args: Args): String? = args.jobId
        ?.trim()
        ?.takeIf { it.isNotBlank() && it.length <= MAX_JOB_ID_LENGTH }

    private fun missingJobId(action: String): ToolResult = cronError(
        action,
        "invalid_arguments",
        "job_id is required.",
        "Use job_id returned by list, get, or add."
    )

    private fun notFound(action: String, jobId: String): ToolResult = cronError(
        action,
        "not_found",
        "Cron job not found: $jobId.",
        "Run list and use a valid job_id."
    )

    private fun requireText(value: String?, field: String, maxLength: Int): String {
        val normalized = value?.trim().orEmpty()
        require(normalized.isNotBlank()) { "$field must not be blank." }
        require(normalized.length <= maxLength) { "$field must be at most $maxLength characters." }
        return normalized
    }

    private fun requiredTextUpdate(
        raw: JsonObject,
        key: String,
        value: String?,
        maxLength: Int
    ): CronFieldUpdate<String> = if (raw.containsKey(key)) {
        CronFieldUpdate.Set(requireText(value, key, maxLength))
    } else {
        CronFieldUpdate.Unchanged
    }

    private fun <T : Any> requiredValueUpdate(
        raw: JsonObject,
        key: String,
        value: T?
    ): CronFieldUpdate<T> = if (raw.containsKey(key)) {
        CronFieldUpdate.Set(requireNotNull(value) { "$key must not be null." })
    } else {
        CronFieldUpdate.Unchanged
    }

    private fun nullableTextUpdate(
        raw: JsonObject,
        key: String,
        value: String?
    ): CronFieldUpdate<String?> = if (raw.containsKey(key)) {
        CronFieldUpdate.Set(normalizeOptional(value))
    } else {
        CronFieldUpdate.Unchanged
    }

    private fun normalizeOptional(value: String?): String? = value
        ?.trim()
        ?.ifBlank { null }
        ?.also {
            require(it.length <= MAX_TARGET_LENGTH) {
                "Optional target fields must be at most $MAX_TARGET_LENGTH characters."
            }
        }

    private fun cronOk(
        action: String,
        message: String,
        details: JsonObjectBuilder.() -> Unit = {}
    ): ToolResult {
        val body = buildJsonObject {
            put("status", "ok")
            put("tool", name)
            put("action", action)
            put("message", message)
            details()
        }
        return ToolResult(
            toolCallId = "",
            content = body.toString(),
            isError = false,
            metadata = body
        )
    }

    private fun cronError(
        action: String,
        code: String,
        message: String,
        nextStep: String? = null
    ): ToolResult {
        val body = buildJsonObject {
            put("status", "error")
            put("tool", name)
            put("action", action)
            put("code", code)
            put("message", message)
            nextStep?.let { put("next_step", it) }
        }
        return ToolResult(
            toolCallId = "",
            content = body.toString(),
            isError = true,
            metadata = JsonObject(body + mapOf("error" to JsonPrimitive(code)))
        )
    }

    private fun CronJob.toJson(includeMessage: Boolean): JsonObject = buildJsonObject {
        put("job_id", id)
        put("name", name.take(MAX_NAME_LENGTH))
        put("name_truncated", name.length > MAX_NAME_LENGTH)
        put("enabled", enabled)
        putJsonObject("schedule") {
            put("kind", schedule.kind)
            putNullable("at_ms", schedule.atMs)
            putNullable("every_ms", schedule.everyMs)
            putNullable("cron_expr", schedule.expr?.take(MAX_CRON_EXPRESSION_LENGTH))
            putNullable("tz", schedule.tz)
        }
        putJsonObject("payload") {
            put("kind", payload.kind)
            if (includeMessage) {
                put("message", payload.message.take(MAX_RESULT_MESSAGE_LENGTH))
                put("message_truncated", payload.message.length > MAX_RESULT_MESSAGE_LENGTH)
            } else {
                put("message_preview", payload.message.take(LIST_MESSAGE_PREVIEW_LENGTH))
                put("message_truncated", payload.message.length > LIST_MESSAGE_PREVIEW_LENGTH)
            }
            put("deliver", payload.deliver)
            putNullable("channel", payload.channel?.take(MAX_TARGET_LENGTH))
            putNullable("to", payload.to?.take(MAX_TARGET_LENGTH))
            putNullable("session_id", payload.sessionId?.take(MAX_TARGET_LENGTH))
        }
        putJsonObject("state") {
            putNullable("next_run_at_ms", state.nextRunAtMs)
            putNullable("last_run_at_ms", state.lastRunAtMs)
            putNullable("last_status", state.lastStatus)
            putNullable("last_error", state.lastError?.take(MAX_ERROR_LENGTH))
            put("last_error_truncated", (state.lastError?.length ?: 0) > MAX_ERROR_LENGTH)
        }
        put("delete_after_run", deleteAfterRun)
        put("created_at_ms", createdAtMs)
        put("updated_at_ms", updatedAtMs)
    }

    private fun JsonObjectBuilder.putNullable(key: String, value: String?) {
        if (value == null) put(key, JsonNull) else put(key, value)
    }

    private fun JsonObjectBuilder.putNullable(key: String, value: Long?) {
        if (value == null) put(key, JsonNull) else put(key, value)
    }

    @Serializable
    private data class Args(
        val action: String,
        val name: String? = null,
        val message: String? = null,
        @SerialName("every_seconds") val everySeconds: Long? = null,
        @SerialName("cron_expr") val cronExpr: String? = null,
        val tz: String? = null,
        val at: String? = null,
        @SerialName("job_id") val jobId: String? = null,
        @SerialName("delete_after_run") val deleteAfterRun: Boolean? = null,
        val deliver: Boolean? = null,
        val channel: String? = null,
        val to: String? = null,
        @SerialName("session_id") val sessionId: String? = null,
        @SerialName("include_disabled") val includeDisabled: Boolean? = null,
        val offset: Int? = null,
        val limit: Int? = null,
        val enabled: Boolean? = null,
        @SerialName("min_every_ms") val minEveryMs: Long? = null,
        @SerialName("max_jobs") val maxJobs: Int? = null,
        val force: Boolean? = null
    )

    private companion object {
        const val DEFAULT_NAME_LENGTH = 30
        const val MAX_NAME_LENGTH = 120
        const val MAX_MESSAGE_LENGTH = 4_000
        const val MAX_RESULT_MESSAGE_LENGTH = 3_000
        const val DEFAULT_LIST_LIMIT = 5
        const val MAX_LIST_LIMIT = 5
        const val LIST_MESSAGE_PREVIEW_LENGTH = 160
        const val MAX_TARGET_LENGTH = 512
        const val MAX_JOB_ID_LENGTH = 128
        const val MAX_CRON_EXPRESSION_LENGTH = 256
        const val MAX_AT_LENGTH = 100
        const val MAX_ERROR_LENGTH = 300
    }
}

internal fun cronToolSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    put("required", JsonArray(listOf(JsonPrimitive("action"))))
    putJsonObject("properties") {
        putJsonObject("action") {
            put("type", "string")
            put(
                "enum",
                JsonArray(
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
                    ).map { JsonPrimitive(it) }
                )
            )
        }
        putJsonObject("name") {
            put("type", "string")
            put("minLength", 1)
            put("maxLength", 120)
        }
        putJsonObject("message") {
            put("type", "string")
            put("minLength", 1)
            put("maxLength", 4_000)
        }
        putJsonObject("every_seconds") { put("type", "integer"); put("minimum", 1) }
        putJsonObject("cron_expr") {
            put("type", "string"); put("minLength", 1); put("maxLength", 256)
        }
        putJsonObject("tz") { put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null")))) }
        putJsonObject("at") {
            put("type", "string"); put("minLength", 1); put("maxLength", 100)
        }
        putJsonObject("job_id") {
            put("type", "string"); put("minLength", 1); put("maxLength", 128)
        }
        putJsonObject("delete_after_run") { put("type", "boolean") }
        putJsonObject("deliver") { put("type", "boolean") }
        listOf("channel", "to", "session_id").forEach { field ->
            putJsonObject(field) {
                put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))))
                put("maxLength", 512)
            }
        }
        putJsonObject("include_disabled") { put("type", "boolean") }
        putJsonObject("offset") { put("type", "integer"); put("minimum", 0) }
        putJsonObject("limit") { put("type", "integer"); put("minimum", 1); put("maximum", 5) }
        putJsonObject("enabled") { put("type", "boolean") }
        putJsonObject("min_every_ms") { put("type", "integer"); put("minimum", 1) }
        putJsonObject("max_jobs") { put("type", "integer"); put("minimum", 1) }
        putJsonObject("force") { put("type", "boolean") }
    }
}
