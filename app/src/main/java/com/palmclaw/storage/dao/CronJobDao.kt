package com.palmclaw.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.palmclaw.storage.entities.CronJobEntity

@Dao
interface CronJobDao {
    @Query("SELECT * FROM cron_jobs ORDER BY COALESCE(nextRunAtMs, 9223372036854775807) ASC, createdAtMs ASC")
    suspend fun getAll(): List<CronJobEntity>

    @Query("SELECT * FROM cron_jobs WHERE id = :jobId LIMIT 1")
    suspend fun getById(jobId: String): CronJobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: CronJobEntity)

    @Query("DELETE FROM cron_jobs WHERE id = :jobId")
    suspend fun deleteById(jobId: String)
}
