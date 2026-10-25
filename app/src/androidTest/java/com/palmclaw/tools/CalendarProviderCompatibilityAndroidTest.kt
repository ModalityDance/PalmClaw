package com.palmclaw.tools

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Exercises the production adapter with real Android cursors and varied provider projections. */
class CalendarProviderCompatibilityAndroidTest {
    @Before
    fun requireWrappedResolver() {
        // Run before assertThrows blocks so older devices skip rather than fail assertions.
        assumeTrue(Build.VERSION.SDK_INT >= 29)
    }

    @Test
    fun unsupportedDerivedColumnDoesNotBreakCoreEventRead() {
        val provider = ProjectionProvider(rejected = setOf(Events.CAN_INVITE_OTHERS))
        val gateway = gateway(provider)
        val event = requireNotNull(gateway.getEvent(7)).event
        assertEquals("Fixture", event.title)
        assertEquals(1L, event.calendarId)
        assertTrue(event.isOrganizer)
        assertTrue(event.canInviteOthers)
        val failures = provider.rejections
        assertNotNull(gateway.getEvent(7))
        assertEquals("Unsupported columns should not be retried on every read", failures, provider.rejections)
    }

    @Test
    fun sqliteProjectionRejectionAlsoFallsBack() {
        val provider = ProjectionProvider(rejected = setOf(Events.CAN_INVITE_OTHERS), sqliteRejection = true)
        assertEquals("Fixture", gateway(provider).getEvent(7)?.event?.title)
    }

    @Test
    fun explicitProviderDenialIsNotReplacedByDerivedPermission() {
        val provider = ProjectionProvider().apply {
            values[Events.IS_ORGANIZER] = 0
            values[Events.CAN_INVITE_OTHERS] = 0
            values[Events.GUESTS_CAN_INVITE_OTHERS] = 1
        }
        val event = requireNotNull(gateway(provider).getEvent(7)).event
        assertFalse(event.isOrganizer)
        assertFalse(event.canInviteOthers)
    }

    @Test
    fun bothDerivedColumnsMissingUseOwnerAndGuestPolicy() {
        val provider = ProjectionProvider(rejected = setOf(Events.IS_ORGANIZER, Events.CAN_INVITE_OTHERS))
        assertTrue(requireNotNull(gateway(provider).getEvent(7)).event.isOrganizer)
        provider.values[Events.ORGANIZER] = "other@example.com"
        val nonOwner = requireNotNull(gateway(provider).getEvent(7)).event
        assertFalse(nonOwner.isOrganizer)
        assertFalse(nonOwner.canInviteOthers)
        provider.values[Events.GUESTS_CAN_INVITE_OTHERS] = 1
        assertTrue(requireNotNull(gateway(provider).getEvent(7)).event.canInviteOthers)
    }

    @Test
    fun missingOwnerDoesNotGrantOrganizerRights() {
        val provider = ProjectionProvider(rejected = setOf(Events.IS_ORGANIZER, Events.CAN_INVITE_OTHERS)).apply {
            values[Events.ORGANIZER] = ""
            owner = ""
        }
        val event = requireNotNull(gateway(provider).getEvent(7)).event
        assertFalse(event.isOrganizer)
        assertFalse(event.canInviteOthers)
    }

    @Test
    fun omittedNullAndReorderedOptionalColumnsAreSafe() {
        val provider = ProjectionProvider(omitted = setOf(Events.CAN_INVITE_OTHERS), reverseColumns = true).apply {
            values[Events.IS_ORGANIZER] = null
        }
        val event = requireNotNull(gateway(provider).getEvent(7)).event
        assertEquals("Fixture", event.title)
        assertEquals(1000L, event.startMs)
        assertTrue(event.isOrganizer)
        assertTrue(event.canInviteOthers)
    }

    @Test
    fun requiredColumnFailureRemainsAnError() {
        assertThrows(IllegalArgumentException::class.java) {
            gateway(ProjectionProvider(rejected = setOf(Events.DTSTART))).getEvent(7)
        }
    }

    @Test
    fun permissionFailureIsNotTreatedAsAnUnsupportedColumn() {
        val provider = ProjectionProvider(securityFailure = true)
        assertThrows(SecurityException::class.java) { gateway(provider).getEvent(7) }
        assertEquals(1, provider.queries)
    }

    @Test
    fun unrelatedProjectionErrorIsNotSilentlyDowngraded() {
        val provider = ProjectionProvider(combinedFailure = true)
        assertThrows(IllegalArgumentException::class.java) { gateway(provider).getEvent(7) }
    }

    @Test
    fun nullProviderCursorIsNotReportedAsMissingEvent() {
        assertThrows(IllegalStateException::class.java) {
            gateway(ProjectionProvider(nullCursor = true)).getEvent(7)
        }
    }

    private fun gateway(provider: ProjectionProvider): AndroidCalendarProviderGateway {
        // ContentResolver.wrap exercises ContentProvider transport without registering a real account.
        val context = ApplicationProvider.getApplicationContext<Context>()
        provider.attachInfo(context, ProviderInfo().apply { authority = "com.android.calendar" })
        val resolver = ContentResolver.wrap(provider)
        return AndroidCalendarProviderGateway(object : ContextWrapper(context) {
            override fun getContentResolver(): ContentResolver = resolver
        })
    }

    private class ProjectionProvider(
        private val rejected: Set<String> = emptySet(),
        private val omitted: Set<String> = emptySet(),
        private val reverseColumns: Boolean = false,
        private val sqliteRejection: Boolean = false,
        private val securityFailure: Boolean = false,
        private val combinedFailure: Boolean = false,
        private val nullCursor: Boolean = false
    ) : ContentProvider() {
        var queries = 0
        var rejections = 0
        var owner = "owner@example.com"
        val values = mutableMapOf<String, Any?>(
            Events._ID to 7L, Events.CALENDAR_ID to 1L, Events.TITLE to "Fixture",
            Events.DTSTART to 1000L, Events.DTEND to 2000L, Events.ALL_DAY to 0,
            Events.EVENT_TIMEZONE to "UTC", Events.AVAILABILITY to 0, Events.ACCESS_LEVEL to 0,
            Events.GUESTS_CAN_MODIFY to 0, Events.GUESTS_CAN_INVITE_OTHERS to 0,
            Events.GUESTS_CAN_SEE_GUESTS to 0, Events.STATUS to Events.STATUS_CONFIRMED,
            Events.ORGANIZER to owner, Events.SELF_ATTENDEE_STATUS to 0,
            Events.IS_ORGANIZER to 1, Events.CAN_INVITE_OTHERS to 1
        )

        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                           selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
            queries++
            if (securityFailure) throw SecurityException("Permission denied")
            if (nullCursor) return null
            val columns = requireNotNull(projection).toList()
            val eventQuery = uri.pathSegments.firstOrNull() == "events"
            if (eventQuery && columns.any { it in rejected }) {
                rejections++
                if (sqliteRejection) throw SQLiteException("no such column")
                throw IllegalArgumentException("Invalid column")
            }
            if (eventQuery && combinedFailure && columns.size > 1 && Events.CAN_INVITE_OTHERS in columns) {
                throw IllegalArgumentException("Unrelated query failure")
            }
            val selected = columns.filterNot { it in omitted }.let { if (reverseColumns) it.reversed() else it }
            return MatrixCursor(selected.toTypedArray()).apply {
                when (uri.pathSegments.firstOrNull()) {
                    "events" -> addRow(selected.map { values[it] })
                    "calendars" -> addRow(selected.map { if (it == Calendars.OWNER_ACCOUNT) owner else 1L })
                }
            }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only test provider")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only test provider")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = error("Read-only test provider")
    }
}
