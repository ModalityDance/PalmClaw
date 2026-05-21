package com.palmclaw.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.palmclaw.attachments.AttachmentRecordRepository
import com.palmclaw.storage.entities.AttachmentRecordEntity
import com.palmclaw.storage.entities.MessageEntity
import com.palmclaw.storage.entities.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseIntegrityTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun deleteSessionCascadesMessagesAndAttachmentRecords() = runBlocking {
        val attachmentRepository = AttachmentRecordRepository(
            attachmentRecordDao = db.attachmentRecordDao(),
            messageDao = db.messageDao()
        )
        val sessionRepository = SessionRepository(
            sessionDao = db.sessionDao(),
            messageDao = db.messageDao(),
            attachmentRecordRepository = attachmentRepository,
            database = db
        )
        db.sessionDao().insert(
            SessionEntity(
                id = "session-1",
                title = "Session 1",
                createdAt = 1,
                updatedAt = 1
            )
        )
        val messageId = db.messageDao().insert(
            MessageEntity(
                sessionId = "session-1",
                role = "user",
                content = "hello",
                createdAt = 2
            )
        )
        db.attachmentRecordDao().insertAll(
            listOf(
                AttachmentRecordEntity(
                    attachmentId = "att-1",
                    messageId = messageId,
                    sessionId = "session-1",
                    direction = "inbound",
                    ownerRole = "user",
                    kind = "file",
                    label = "a.txt",
                    transferState = "ready",
                    createdAtMs = 3,
                    updatedAtMs = 3
                )
            )
        )

        sessionRepository.deleteSession("session-1")

        assertEquals(emptyList<MessageEntity>(), db.messageDao().getBySession("session-1"))
        assertEquals(0, countRows("attachment_records"))
    }

    private fun countRows(tableName: String): Int {
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $tableName").use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }
}
