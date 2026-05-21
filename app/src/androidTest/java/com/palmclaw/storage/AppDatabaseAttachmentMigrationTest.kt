package com.palmclaw.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseAttachmentMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "attachment-migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `migration 3 to 4 adds attachmentsJson column and preserves message rows`() {
        val dbFile = context.getDatabasePath(databaseName)
        if (dbFile.exists()) {
            dbFile.delete()
        }

        val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                sessionId TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                toolCallJson TEXT,
                toolResultJson TEXT
            )
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_messages_sessionId_createdAt
            ON messages(sessionId, createdAt)
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sessions (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cron_jobs (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                enabled INTEGER NOT NULL,
                scheduleKind TEXT NOT NULL,
                scheduleAtMs INTEGER,
                scheduleEveryMs INTEGER,
                scheduleExpr TEXT,
                scheduleTz TEXT,
                payloadKind TEXT NOT NULL,
                payloadMessage TEXT NOT NULL,
                payloadDeliver INTEGER NOT NULL,
                payloadChannel TEXT,
                payloadTo TEXT,
                payloadSessionId TEXT,
                nextRunAtMs INTEGER,
                lastRunAtMs INTEGER,
                lastStatus TEXT,
                lastError TEXT,
                createdAtMs INTEGER NOT NULL,
                updatedAtMs INTEGER NOT NULL,
                deleteAfterRun INTEGER NOT NULL
            )
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            INSERT INTO messages (id, sessionId, role, content, createdAt, toolCallJson, toolResultJson)
            VALUES (1, 'session-1', 'user', 'hello', 123, NULL, NULL)
            """.trimIndent()
        )
        sqlite.version = 3
        sqlite.close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6
            )
            .build()
        db.openHelper.writableDatabase.close()
        db.close()

        val migrated = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        migrated.rawQuery("PRAGMA table_info(messages)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            var foundAttachmentsColumn = false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == "attachmentsJson") {
                    foundAttachmentsColumn = true
                    break
                }
            }
            assertTrue(foundAttachmentsColumn)
        }
        migrated.rawQuery(
            "SELECT content, attachmentsJson FROM messages WHERE id = 1",
            null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("hello", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
        migrated.close()
    }

    @Test
    fun `migration 4 to 5 adds attachment records table and preserves messages`() {
        val dbFile = context.getDatabasePath(databaseName)
        if (dbFile.exists()) {
            dbFile.delete()
        }

        val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                sessionId TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                toolCallJson TEXT,
                toolResultJson TEXT,
                attachmentsJson TEXT
            )
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_messages_sessionId_createdAt
            ON messages(sessionId, createdAt)
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sessions (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cron_jobs (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                enabled INTEGER NOT NULL,
                scheduleKind TEXT NOT NULL,
                scheduleAtMs INTEGER,
                scheduleEveryMs INTEGER,
                scheduleExpr TEXT,
                scheduleTz TEXT,
                payloadKind TEXT NOT NULL,
                payloadMessage TEXT NOT NULL,
                payloadDeliver INTEGER NOT NULL,
                payloadChannel TEXT,
                payloadTo TEXT,
                payloadSessionId TEXT,
                nextRunAtMs INTEGER,
                lastRunAtMs INTEGER,
                lastStatus TEXT,
                lastError TEXT,
                createdAtMs INTEGER NOT NULL,
                updatedAtMs INTEGER NOT NULL,
                deleteAfterRun INTEGER NOT NULL
            )
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            INSERT INTO messages (id, sessionId, role, content, createdAt, toolCallJson, toolResultJson, attachmentsJson)
            VALUES (1, 'session-1', 'assistant', 'hello', 123, NULL, NULL, '[{"kind":"file","reference":"/workspace/report.pdf"}]')
            """.trimIndent()
        )
        sqlite.version = 4
        sqlite.close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6
            )
            .build()
        db.openHelper.writableDatabase.close()
        db.close()

        val migrated = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        migrated.rawQuery("PRAGMA table_info(attachment_records)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            var foundAttachmentId = false
            var foundRemoteLocator = false
            while (cursor.moveToNext()) {
                when (cursor.getString(nameIndex)) {
                    "attachmentId" -> foundAttachmentId = true
                    "remoteLocator" -> foundRemoteLocator = true
                }
            }
            assertTrue(foundAttachmentId)
            assertTrue(foundRemoteLocator)
        }
        migrated.rawQuery(
            "SELECT content, attachmentsJson FROM messages WHERE id = 1",
            null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("hello", cursor.getString(0))
            assertEquals("""[{"kind":"file","reference":"/workspace/report.pdf"}]""", cursor.getString(1))
        }
        migrated.close()
    }

    @Test
    fun `migration 5 to 6 adds foreign keys repairs sessions and prunes orphan attachments`() {
        val dbFile = context.getDatabasePath(databaseName)
        if (dbFile.exists()) {
            dbFile.delete()
        }

        val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        createVersion5Schema(sqlite)
        sqlite.execSQL(
            """
            INSERT INTO sessions (id, title, createdAt, updatedAt)
            VALUES ('session-1', 'Session 1', 100, 200)
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            INSERT INTO messages (id, sessionId, role, content, createdAt, toolCallJson, toolResultJson, attachmentsJson)
            VALUES (1, 'session-1', 'user', 'hello', 123, NULL, NULL, NULL)
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            INSERT INTO messages (id, sessionId, role, content, createdAt, toolCallJson, toolResultJson, attachmentsJson)
            VALUES (2, 'missing-session', 'assistant', 'repair me', 456, NULL, NULL, NULL)
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            INSERT INTO attachment_records (
                attachmentId, messageId, sessionId, direction, ownerRole, kind, label,
                mimeType, sizeBytes, workspacePath, sourceUri, remoteLocator, transferState,
                failureMessage, metadataJson, createdAtMs, updatedAtMs
            )
            VALUES (
                'att-1', 1, 'wrong-session', 'inbound', 'user', 'file', 'a.txt',
                'text/plain', 10, '/workspace/a.txt', NULL, NULL, 'ready',
                NULL, NULL, 100, 100
            )
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            INSERT INTO attachment_records (
                attachmentId, messageId, sessionId, direction, ownerRole, kind, label,
                mimeType, sizeBytes, workspacePath, sourceUri, remoteLocator, transferState,
                failureMessage, metadataJson, createdAtMs, updatedAtMs
            )
            VALUES (
                'orphan-att', 99, 'session-1', 'inbound', 'user', 'file', 'gone.txt',
                'text/plain', 10, '/workspace/gone.txt', NULL, NULL, 'ready',
                NULL, NULL, 100, 100
            )
            """.trimIndent()
        )
        sqlite.version = 5
        sqlite.close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6
            )
            .build()
        db.openHelper.writableDatabase.close()
        db.close()

        val migrated = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
        migrated.rawQuery("SELECT title FROM sessions WHERE id = 'missing-session'", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("missing-session", cursor.getString(0))
        }
        migrated.rawQuery("SELECT sessionId FROM attachment_records WHERE attachmentId = 'att-1'", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("session-1", cursor.getString(0))
        }
        migrated.rawQuery("SELECT COUNT(*) FROM attachment_records WHERE attachmentId = 'orphan-att'", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.rawQuery("PRAGMA foreign_key_list(messages)", null).use { cursor ->
            assertTrue(cursor.count > 0)
        }
        migrated.execSQL("PRAGMA foreign_keys=ON")
        migrated.execSQL("DELETE FROM sessions WHERE id = 'session-1'")
        migrated.rawQuery("SELECT COUNT(*) FROM messages WHERE sessionId = 'session-1'", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.rawQuery("SELECT COUNT(*) FROM attachment_records WHERE attachmentId = 'att-1'", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
    }

    @Test
    fun `migration 1 to 6 opens current database and preserves legacy messages`() {
        val dbFile = context.getDatabasePath(databaseName)
        if (dbFile.exists()) {
            dbFile.delete()
        }

        val sqlite = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                sessionId TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                toolCallJson TEXT,
                toolResultJson TEXT
            )
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_messages_sessionId_createdAt
            ON messages(sessionId, createdAt)
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            INSERT INTO messages (id, sessionId, role, content, createdAt, toolCallJson, toolResultJson)
            VALUES (1, 'legacy-session', 'user', 'legacy hello', 123, NULL, NULL)
            """.trimIndent()
        )
        sqlite.version = 1
        sqlite.close()

        val db = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6
            )
            .build()
        db.openHelper.writableDatabase.close()
        db.close()

        val migrated = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
        migrated.rawQuery("SELECT content, attachmentsJson FROM messages WHERE id = 1", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("legacy hello", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
        migrated.rawQuery("SELECT id FROM sessions WHERE id = 'legacy-session'", null).use { cursor ->
            assertTrue(cursor.moveToFirst())
        }
        migrated.rawQuery("PRAGMA foreign_key_list(attachment_records)", null).use { cursor ->
            assertTrue(cursor.count >= 2)
        }
        migrated.close()
    }

    private fun createVersion5Schema(sqlite: SQLiteDatabase) {
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                sessionId TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                toolCallJson TEXT,
                toolResultJson TEXT,
                attachmentsJson TEXT
            )
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            CREATE INDEX IF NOT EXISTS index_messages_sessionId_createdAt
            ON messages(sessionId, createdAt)
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sessions (
                id TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cron_jobs (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                enabled INTEGER NOT NULL,
                scheduleKind TEXT NOT NULL,
                scheduleAtMs INTEGER,
                scheduleEveryMs INTEGER,
                scheduleExpr TEXT,
                scheduleTz TEXT,
                payloadKind TEXT NOT NULL,
                payloadMessage TEXT NOT NULL,
                payloadDeliver INTEGER NOT NULL,
                payloadChannel TEXT,
                payloadTo TEXT,
                payloadSessionId TEXT,
                nextRunAtMs INTEGER,
                lastRunAtMs INTEGER,
                lastStatus TEXT,
                lastError TEXT,
                createdAtMs INTEGER NOT NULL,
                updatedAtMs INTEGER NOT NULL,
                deleteAfterRun INTEGER NOT NULL
            )
            """.trimIndent()
        )
        sqlite.execSQL(
            """
            CREATE TABLE IF NOT EXISTS attachment_records (
                attachmentId TEXT NOT NULL PRIMARY KEY,
                messageId INTEGER NOT NULL,
                sessionId TEXT NOT NULL,
                direction TEXT NOT NULL,
                ownerRole TEXT NOT NULL,
                kind TEXT NOT NULL,
                label TEXT NOT NULL,
                mimeType TEXT,
                sizeBytes INTEGER,
                workspacePath TEXT,
                sourceUri TEXT,
                remoteLocator TEXT,
                transferState TEXT NOT NULL,
                failureMessage TEXT,
                metadataJson TEXT,
                createdAtMs INTEGER NOT NULL,
                updatedAtMs INTEGER NOT NULL
            )
            """.trimIndent()
        )
        sqlite.execSQL(
            "CREATE INDEX IF NOT EXISTS index_attachment_records_messageId_updatedAtMs " +
                "ON attachment_records(messageId, updatedAtMs)"
        )
        sqlite.execSQL(
            "CREATE INDEX IF NOT EXISTS index_attachment_records_sessionId_updatedAtMs " +
                "ON attachment_records(sessionId, updatedAtMs)"
        )
    }
}
