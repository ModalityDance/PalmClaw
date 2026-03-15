package com.palmclaw.storage.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cron_jobs")
data class CronJobEntity(
    @PrimaryKey val id: String,
    val name: String,
    val enabled: Boolean,
    val scheduleKind: String,
    val scheduleAtMs: Long?,
    val scheduleEveryMs: Long?,
    val scheduleExpr: String?,
    val scheduleTz: String?,
    val payloadKind: String,
    val payloadMessage: String,
    val payloadDeliver: Boolean,
    val payloadChannel: String?,
    val payloadTo: String?,
    val payloadSessionId: String?,
    val nextRunAtMs: Long?,
    val lastRunAtMs: Long?,
    val lastStatus: String?,
    val lastError: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val deleteAfterRun: Boolean
)
