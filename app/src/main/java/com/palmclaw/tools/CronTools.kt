package com.palmclaw.tools

import com.palmclaw.config.CronConfig
import com.palmclaw.cron.CronPayload
import com.palmclaw.cron.CronSchedule
import com.palmclaw.cron.CronService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

fun createCronToolSet(
    cronService: CronService,
    onSetServiceEnabled: (suspend (Boolean) -> Unit)? = null,
    onUpdateConfig: (suspend (CronConfigUpdate) -> CronConfig)? = null
): List<Tool> {
    return listOf(CronTool(cronService, onSetServiceEnabled, onUpdateConfig))
}

data class CronConfigUpdate(
    val enabled: Boolean? = null,
    val minEveryMs: Long? = null,
    val maxJobs: Int? = null
)

private class CronTool(
    private val cron: CronService,
    private val onSetServiceEnabled: (suspend (Boolean) -> Unit)?,
    private val onUpdateConfig: (suspend (CronConfigUpdate) -> CronConfig)?
) : Tool {
    override val name: String = "cron"
    override val description: String =
        "Manage scheduled jobs and cron policy. action=add|list|remove|enable_job|run_now|status|set_enabled|set_config"

    override val jsonSchema: JsonObject = buildJsonObject {
        put("type", "object")
        put("additionalProperties", false)
        put("required", Json.parseToJsonElement("[\"action\"]"))
        put(
            "properties",
            Json.parseToJsonElement(
                """
                {
                  "action":{"type":"string","enum":["add","list","remove","enable_job","run_now","status","set_enabled","set_config"]},
                  "name":{"type":"string"},
                  "message":{"type":"string"},
                  "every_seconds":{"type":"integer"},
                  "cron_expr":{"type":"string"},
                  "tz":{"type":"string"},
                  "at":{"type":"string","description":"ISO datetime or epoch milliseconds"},
                  "job_id":{"type":"string"},
                  "delete_after_run":{"type":"boolean"},
                  "deliver":{"type":"boolean"},
                  "channel":{"type":"string"},
                  "to":{"type":"string"},
                  "session_id":{"type":"string"},
                  "include_disabled":{"type":"boolean"},
                  "enabled":{"type":"boolean"},
                  "min_every_ms":{"type":"integer"},
                  "max_jobs":{"type":"integer"},
                  "force":{"type":"boolean"}
                }
                """.trimIndent()
            )
        )
    }

    override suspend fun run(argumentsJson: String): ToolResult {
        val args = Json.decodeFromString<Args>(argumentsJson)
        val action = args.action.trim().lowercase(Locale.US)
        return when (action) {
            "add" -> add(args)
            "list" -> list(args)
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
                nextStep = "Use action=add|list|remove|enable_job|run_now|status|set_enabled|set_config."
            )
        }
    }

    private suspend fun add(args: Args): ToolResult {
        val action = "add"
        if (cron.isExecutingJob()) {
            return cronError(
                action = action,
                code = "blocked_in_job",
                message = "Cannot schedule new jobs from within a cron job execution.",
                nextStep = "Schedule from a normal chat turn."
            )
        }

        val message = args.message?.trim().orEmpty()
        if (message.isBlank()) {
            return cronError(
                action = action,
                code = "invalid_arguments",
                message = "message is required.",
                nextStep = "Provide non-empty message."
            )
        }

        if (!args.tz.isNullOrBlank() && args.cronExpr.isNullOrBlank()) {
            return cronError(
                action = action,
                code = "invalid_arguments",
                message = "tz can only be used with cron_expr.",
                nextStep = "Provide cron_expr together with tz, or remove tz."
            )
        }

        val scheduleFields = listOf(
            args.everySeconds != null,
            !args.cronExpr.isNullOrBlank(),
            !args.at.isNullOrBlank()
        ).count { it }
        if (scheduleFields != 1) {
            return cronError(
                action = action,
                code = "invalid_arguments",
                message = "Exactly one schedule field is required: every_seconds OR cron_expr OR at.",
                nextStep = "Keep one schedule field and retry."
            )
        }

        val schedule = when {
            args.everySeconds != null -> {
                if (args.everySeconds <= 0) {
                    return cronError(
                        action = action,
                        code = "invalid_arguments",
                        message = "every_seconds must be > 0.",
                        nextStep = "Use a positive integer."
                    )
                }
                CronSchedule(kind = "every", everyMs = args.everySeconds * 1000L)
            }

            !args.cronExpr.isNullOrBlank() -> {
                CronSchedule(
                    kind = "cron",
                    expr = args.cronExpr.trim(),
                    tz = args.tz?.trim()?.ifBlank { null }
                )
            }

            else -> {
                val atMs = parseAtToMs(args.at!!.trim())
                    ?: return cronError(
                        action = action,
                        code = "invalid_arguments",
                        message = "Invalid at format; use ISO datetime or epoch milliseconds.",
                        nextStep = "Example: 2026-03-10T09:30:00+08:00"
                    )
                if (atMs <= System.currentTimeMillis()) {
                    return cronError(
                        action = action,
                        code = "invalid_arguments",
                        message = "'at' must be in the future.",
                        nextStep = "Pass a future time."
                    )
                }
                CronSchedule(kind = "at", atMs = atMs)
            }
        }

        val payload = CronPayload(
            kind = "agent_turn",
            message = message,
            deliver = args.deliver ?: true,
            channel = args.channel?.trim()?.ifBlank { null },
            to = args.to?.trim()?.ifBlank { null },
            sessionId = args.sessionId?.trim()?.ifBlank { null }
        )
        val jobName = args.name?.trim()?.ifBlank { null } ?: message.take(30).ifBlank { "cron-job" }
        val deleteAfterRun = args.deleteAfterRun ?: (schedule.kind == "at")

        return runCatching {
            val job = cron.addJob(
                name = jobName,
                schedule = schedule,
                payload = payload,
                deleteAfterRun = deleteAfterRun
            )

            var autoEnabled = false
            val statusBefore = cron.status()
            val wasEnabled = (statusBefore["enabled"] as? Boolean) == true
            if (!wasEnabled) {
                if (onSetServiceEnabled != null) {
                    onSetServiceEnabled.invoke(true)
                } else {
                    cron.start()
                }
                autoEnabled = true
            }

            val targetSessionId = payload.sessionId?.trim().orEmpty()
            val deliveryMode = when {
                targetSessionId.isNotBlank() && payload.deliver -> "session:$targetSessionId remote_if_bound"
                targetSessionId.isNotBlank() -> "session:$targetSessionId local_only"
                payload.deliver && !payload.channel.isNullOrBlank() && !payload.to.isNullOrBlank() ->
                    "gateway:${payload.channel}/${payload.to}"
                else -> "local_session_only"
            }

            cronOk(
                action = action,
                message = buildString {
                    append("Added cron job '${job.name}' (id=${job.id}), schedule=${job.schedule.kind}, next_run_ms=${job.state.nextRunAtMs}. ")
                    if (autoEnabled) {
                        append("Cron service was disabled and has been auto-enabled. ")
                    }
                    append("Delivery mode: $deliveryMode.")
                }
            ) {
                put("job_id", job.id)
                put("name", job.name)
                put("schedule_kind", job.schedule.kind)
                put("next_run_at_ms", job.state.nextRunAtMs ?: -1L)
                put("delete_after_run", job.deleteAfterRun)
                put("deliver", payload.deliver)
                put("auto_enabled", autoEnabled)
                put("delivery_mode", deliveryMode)
            }
        }.getOrElse { t ->
            cronError(
                action = action,
                code = "add_failed",
                message = t.message ?: t.javaClass.simpleName,
                nextStep = "Check schedule parameters and retry."
            )
        }
    }

    private suspend fun list(args: Args): ToolResult {
        val action = "list"
        val includeDisabled = args.includeDisabled ?: false
        val jobs = cron.listJobs(includeDisabled = includeDisabled)
        if (jobs.isEmpty()) {
            return cronOk(
                action = action,
                message = "No cron jobs found."
            ) {
                put("count", 0)
                put("include_disabled", includeDisabled)
            }
        }

        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val lines = jobs.map { job ->
            val next = job.state.nextRunAtMs?.let { formatter.format(Date(it)) } ?: "-"
            val last = job.state.lastStatus ?: "-"
            "${job.id} | ${job.name} | kind=${job.schedule.kind} | enabled=${job.enabled} | next=$next | last=$last"
        }
        return cronOk(
            action = action,
            message = "Listed ${jobs.size} cron job(s):\n" + lines.joinToString("\n")
        ) {
            put("count", jobs.size)
            put("include_disabled", includeDisabled)
            putJsonArray("job_ids") {
                jobs.forEach { add(it.id) }
            }
        }
    }

    private suspend fun remove(args: Args): ToolResult {
        val action = "remove"
        val jobId = args.jobId?.trim().orEmpty()
        if (jobId.isBlank()) {
            return cronError(
                action = action,
                code = "invalid_arguments",
                message = "job_id is required.",
                nextStep = "Provide job_id from cron list."
            )
        }

        val removed = cron.removeJob(jobId)
        return if (removed) {
            cronOk(
                action = action,
                message = "Removed cron job id=$jobId."
            ) {
                put("job_id", jobId)
            }
        } else {
            cronError(
                action = action,
                code = "not_found",
                message = "Job not found: $jobId.",
                nextStep = "Run list and use a valid job_id."
            )
        }
    }

    private suspend fun enableJob(args: Args): ToolResult {
        val action = "enable_job"
        val jobId = args.jobId?.trim().orEmpty()
        if (jobId.isBlank()) {
            return cronError(
                action = action,
                code = "invalid_arguments",
                message = "job_id is required.",
                nextStep = "Provide job_id from cron list."
            )
        }
        val enabled = args.enabled ?: return cronError(
            action = action,
            code = "invalid_arguments",
            message = "enabled is required.",
            nextStep = "Set enabled=true or false."
        )

        val updated = cron.enableJob(jobId, enabled)
        return if (updated != null) {
            cronOk(
                action = action,
                message = "Set job id=${updated.id} enabled=${updated.enabled}, next_run_ms=${updated.state.nextRunAtMs}."
            ) {
                put("job_id", updated.id)
                put("enabled", updated.enabled)
                put("next_run_at_ms", updated.state.nextRunAtMs ?: -1L)
            }
        } else {
            cronError(
                action = action,
                code = "not_found",
                message = "Job not found: $jobId.",
                nextStep = "Run list and use a valid job_id."
            )
        }
    }

    private suspend fun runNow(args: Args): ToolResult {
        val action = "run_now"
        val jobId = args.jobId?.trim().orEmpty()
        if (jobId.isBlank()) {
            return cronError(
                action = action,
                code = "invalid_arguments",
                message = "job_id is required.",
                nextStep = "Provide job_id from cron list."
            )
        }

        val force = args.force ?: false
        val success = cron.runJob(jobId, force = force)
        return if (success) {
            cronOk(
                action = action,
                message = "Triggered run_now for job id=$jobId (force=$force)."
            ) {
                put("job_id", jobId)
                put("force", force)
            }
        } else {
            cronError(
                action = action,
                code = "run_failed",
                message = "Failed to trigger run_now for job id=$jobId.",
                nextStep = "Check job exists and whether it is enabled, or retry with force=true."
            )
        }
    }

    private suspend fun status(): ToolResult {
        val action = "status"
        val data = cron.status()
        val enabled = data["enabled"] as? Boolean
        val jobs = (data["jobs"] as? Number)?.toInt()
        val nextWakeAt = (data["next_wake_at_ms"] as? Number)?.toLong()
        val minEveryMs = (data["min_every_ms"] as? Number)?.toLong()
        val maxJobs = (data["max_jobs"] as? Number)?.toInt()
        return cronOk(
            action = action,
            message = "Cron status: enabled=$enabled, jobs=$jobs, next_wake_at_ms=$nextWakeAt, min_every_ms=$minEveryMs, max_jobs=$maxJobs."
        ) {
            put("enabled", enabled ?: false)
            put("jobs", jobs ?: -1)
            put("next_wake_at_ms", nextWakeAt ?: -1L)
            put("min_every_ms", minEveryMs ?: -1L)
            put("max_jobs", maxJobs ?: -1)
        }
    }

    private suspend fun setEnabled(args: Args): ToolResult {
        val action = "set_enabled"
        val enabled = args.enabled ?: return cronError(
            action = action,
            code = "invalid_arguments",
            message = "enabled is required.",
            nextStep = "Set enabled=true or false."
        )
        if (onSetServiceEnabled != null) {
            onSetServiceEnabled.invoke(enabled)
        } else {
            if (enabled) cron.start() else cron.stop()
        }

        val status = cron.status()
        val currentEnabled = status["enabled"] as? Boolean
        val jobs = (status["jobs"] as? Number)?.toInt()
        val nextWakeAt = (status["next_wake_at_ms"] as? Number)?.toLong()
        return cronOk(
            action = action,
            message = "Cron service set to enabled=$enabled. Current jobs=$jobs."
        ) {
            put("enabled", currentEnabled ?: enabled)
            put("jobs", jobs ?: -1)
            put("next_wake_at_ms", nextWakeAt ?: -1L)
        }
    }

    private suspend fun setConfig(args: Args): ToolResult {
        val action = "set_config"
        val requestedEnabled = args.enabled
        val requestedMinEveryMs = args.minEveryMs
        val requestedMaxJobs = args.maxJobs
        if (requestedEnabled == null && requestedMinEveryMs == null && requestedMaxJobs == null) {
            return cronError(
                action = action,
                code = "invalid_arguments",
                message = "At least one of enabled, min_every_ms, max_jobs is required.",
                nextStep = "Provide one or more cron config fields to update."
            )
        }
        val callback = onUpdateConfig ?: return cronError(
            action = action,
            code = "unsupported",
            message = "Cron config persistence is not available in this runtime.",
            nextStep = "Use set_enabled or change cron settings from the app UI."
        )
        return runCatching {
            val updated = callback.invoke(
                CronConfigUpdate(
                    enabled = requestedEnabled,
                    minEveryMs = requestedMinEveryMs,
                    maxJobs = requestedMaxJobs
                )
            )
            cronOk(
                action = action,
                message = "Cron config updated: enabled=${updated.enabled}, min_every_ms=${updated.minEveryMs}, max_jobs=${updated.maxJobs}."
            ) {
                put("enabled", updated.enabled)
                put("min_every_ms", updated.minEveryMs)
                put("max_jobs", updated.maxJobs)
            }
        }.getOrElse { t ->
            cronError(
                action = action,
                code = "set_config_failed",
                message = t.message ?: t.javaClass.simpleName,
                nextStep = "Check value ranges and retry."
            )
        }
    }

    private fun parseAtToMs(input: String): Long? {
        input.toLongOrNull()?.let { raw ->
            if (raw > 0L) return raw
        }
        return runCatching { Instant.parse(input).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(input).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching {
                LocalDateTime.parse(input).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull()
    }

    private fun cronOk(
        action: String,
        message: String,
        extra: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit)? = null
    ): ToolResult {
        return ToolResult(
            toolCallId = "",
            content = message,
            isError = false,
            metadata = buildJsonObject {
                put("tool", name)
                put("action", action)
                put("status", "ok")
                extra?.invoke(this)
            }
        )
    }

    private fun cronError(
        action: String,
        code: String,
        message: String,
        nextStep: String? = null
    ): ToolResult {
        val content = buildString {
            append("$name/$action failed: $message")
            if (!nextStep.isNullOrBlank()) {
                append(" Next: ")
                append(nextStep)
            }
        }
        return ToolResult(
            toolCallId = "",
            content = content,
            isError = true,
            metadata = buildJsonObject {
                put("tool", name)
                put("action", action)
                put("status", "error")
                put("error", code)
                put("recoverable", !nextStep.isNullOrBlank())
                if (!nextStep.isNullOrBlank()) {
                    put("next_step", nextStep)
                }
            }
        )
    }

    @Serializable
    private data class Args(
        val action: String,
        val name: String? = null,
        val message: String? = null,
        @SerialName("every_seconds")
        val everySeconds: Long? = null,
        @SerialName("cron_expr")
        val cronExpr: String? = null,
        val tz: String? = null,
        val at: String? = null,
        @SerialName("job_id")
        val jobId: String? = null,
        @SerialName("delete_after_run")
        val deleteAfterRun: Boolean? = null,
        val deliver: Boolean? = null,
        val channel: String? = null,
        val to: String? = null,
        @SerialName("session_id")
        val sessionId: String? = null,
        @SerialName("include_disabled")
        val includeDisabled: Boolean? = null,
        val enabled: Boolean? = null,
        @SerialName("min_every_ms")
        val minEveryMs: Long? = null,
        @SerialName("max_jobs")
        val maxJobs: Int? = null,
        val force: Boolean? = null
    )
}
