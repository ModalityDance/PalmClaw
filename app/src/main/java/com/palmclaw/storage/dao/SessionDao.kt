package com.palmclaw.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.palmclaw.storage.entities.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getById(sessionId: String): SessionEntity?

    @Query("UPDATE sessions SET updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun touch(sessionId: String, updatedAt: Long)

    @Query("UPDATE sessions SET title = :title, updatedAt = :updatedAt WHERE id = :sessionId")
    suspend fun rename(sessionId: String, title: String, updatedAt: Long)

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun delete(sessionId: String)

    @Query("DELETE FROM sessions WHERE id != :sessionId")
    suspend fun deleteAllExcept(sessionId: String)

    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC, createdAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC, createdAt DESC")
    suspend fun getAll(): List<SessionEntity>
}
