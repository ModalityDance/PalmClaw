package com.palmclaw.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.palmclaw.storage.entities.AttachmentRecordEntity

@Dao
interface AttachmentRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<AttachmentRecordEntity>)

    @Query("SELECT * FROM attachment_records WHERE messageId = :messageId ORDER BY createdAtMs ASC, attachmentId ASC")
    suspend fun getByMessageId(messageId: Long): List<AttachmentRecordEntity>

    @Query("DELETE FROM attachment_records WHERE messageId = :messageId")
    suspend fun deleteByMessageId(messageId: Long)

    @Query("DELETE FROM attachment_records WHERE sessionId = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)

    @Query("UPDATE attachment_records SET sessionId = :targetSessionId WHERE sessionId != :targetSessionId")
    suspend fun moveAllToSession(targetSessionId: String): Int
}
