package com.palmclaw.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.palmclaw.storage.dao.AttachmentRecordDao
import com.palmclaw.storage.dao.CronJobDao
import com.palmclaw.storage.dao.MessageDao
import com.palmclaw.storage.dao.SessionDao
import com.palmclaw.storage.entities.AttachmentRecordEntity
import com.palmclaw.storage.entities.CronJobEntity
import com.palmclaw.storage.entities.MessageEntity
import com.palmclaw.storage.entities.SessionEntity
import java.util.UUID

@Database(
    entities = [MessageEntity::class, SessionEntity::class, CronJobEntity::class, AttachmentRecordEntity::class],
    version = 6,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun sessionDao(): SessionDao
    abstract fun cronJobDao(): CronJobDao
    abstract fun attachmentRecordDao(): AttachmentRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "palmclaw.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .addMigrations(MIGRATION_2_3)
                    .addMigrations(MIGRATION_3_4)
                    .addMigrations(MIGRATION_4_5)
                    .addMigrations(MIGRATION_5_6)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS sessions (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )

                val fallbackId = "default-" + UUID.randomUUID().toString()
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO sessions (id, title, createdAt, updatedAt)
                    SELECT
                        sessionId,
                        sessionId,
                        MIN(createdAt),
                        MAX(createdAt)
                    FROM messages
                    GROUP BY sessionId
                    """.trimIndent()
                )

                db.execSQL(
                    """
                    INSERT OR IGNORE INTO sessions (id, title, createdAt, updatedAt)
                    VALUES (
                        '$fallbackId',
                        'Chat',
                        COALESCE((SELECT MIN(createdAt) FROM messages), strftime('%s','now') * 1000),
                        COALESCE((SELECT MAX(createdAt) FROM messages), strftime('%s','now') * 1000)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
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
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE messages
                    ADD COLUMN attachmentsJson TEXT
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
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
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_attachment_records_messageId_updatedAtMs " +
                        "ON attachment_records(messageId, updatedAtMs)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_attachment_records_sessionId_updatedAtMs " +
                        "ON attachment_records(sessionId, updatedAtMs)"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys=OFF")
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO sessions (id, title, createdAt, updatedAt)
                    SELECT
                        sessionId,
                        sessionId,
                        MIN(createdAt),
                        MAX(createdAt)
                    FROM messages
                    WHERE sessionId NOT IN (SELECT id FROM sessions)
                    GROUP BY sessionId
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS messages_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId TEXT NOT NULL,
                        role TEXT NOT NULL,
                        content TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        toolCallJson TEXT,
                        toolResultJson TEXT,
                        attachmentsJson TEXT,
                        FOREIGN KEY(sessionId) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO messages_new (
                        id,
                        sessionId,
                        role,
                        content,
                        createdAt,
                        toolCallJson,
                        toolResultJson,
                        attachmentsJson
                    )
                    SELECT
                        id,
                        sessionId,
                        role,
                        content,
                        createdAt,
                        toolCallJson,
                        toolResultJson,
                        attachmentsJson
                    FROM messages
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE messages")
                db.execSQL("ALTER TABLE messages_new RENAME TO messages")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_messages_sessionId_createdAt " +
                        "ON messages(sessionId, createdAt)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS attachment_records_new (
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
                        updatedAtMs INTEGER NOT NULL,
                        FOREIGN KEY(messageId) REFERENCES messages(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(sessionId) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR REPLACE INTO attachment_records_new (
                        attachmentId,
                        messageId,
                        sessionId,
                        direction,
                        ownerRole,
                        kind,
                        label,
                        mimeType,
                        sizeBytes,
                        workspacePath,
                        sourceUri,
                        remoteLocator,
                        transferState,
                        failureMessage,
                        metadataJson,
                        createdAtMs,
                        updatedAtMs
                    )
                    SELECT
                        a.attachmentId,
                        a.messageId,
                        m.sessionId,
                        a.direction,
                        a.ownerRole,
                        a.kind,
                        a.label,
                        a.mimeType,
                        a.sizeBytes,
                        a.workspacePath,
                        a.sourceUri,
                        a.remoteLocator,
                        a.transferState,
                        a.failureMessage,
                        a.metadataJson,
                        a.createdAtMs,
                        a.updatedAtMs
                    FROM attachment_records a
                    INNER JOIN messages m ON m.id = a.messageId
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE attachment_records")
                db.execSQL("ALTER TABLE attachment_records_new RENAME TO attachment_records")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_attachment_records_messageId_updatedAtMs " +
                        "ON attachment_records(messageId, updatedAtMs)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_attachment_records_sessionId_updatedAtMs " +
                        "ON attachment_records(sessionId, updatedAtMs)"
                )
                db.query("PRAGMA foreign_key_check").use { cursor ->
                    check(!cursor.moveToFirst()) { "Foreign key check failed during migration 5 to 6" }
                }
                db.execSQL("PRAGMA foreign_keys=ON")
            }
        }
    }
}
