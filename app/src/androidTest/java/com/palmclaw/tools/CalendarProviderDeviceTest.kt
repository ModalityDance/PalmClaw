package com.palmclaw.tools

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.CalendarContract
import androidx.test.platform.app.InstrumentationRegistry
import com.palmclaw.providers.ToolCall
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in real provider QA. Uses only uniquely named fixtures in a writable LOCAL calendar. */
class CalendarProviderDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val resolver get() = context.contentResolver

    @Test
    fun localEventRoundTrip(): Unit = runBlocking { verifyRoundTrip(recurring = false) }

    @Test
    fun localRecurringEventRoundTrip(): Unit = runBlocking { verifyRoundTrip(recurring = true) }

    private suspend fun verifyRoundTrip(recurring: Boolean) {
        assumeTrue("Enable with -e calendarDeviceQa true on a test device",
            InstrumentationRegistry.getArguments().getString("calendarDeviceQa") == "true")
        assumeTrue("Grant calendar permissions before running device QA", listOf(
            Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR
        ).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED })
        val localIds = resolver.query(CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_TYPE),
            null, null, null)?.use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    if (cursor.getString(1).equals(CalendarContract.ACCOUNT_TYPE_LOCAL, ignoreCase = true)) add(cursor.getLong(0))
                }
            }
        }.orEmpty()
        val calendar = AndroidCalendarProviderGateway(context).listCalendars().firstOrNull { it.id in localIds && it.writable }
        assumeTrue("No writable local calendar; never use a cloud account for automatic QA", calendar != null)
        val calendarId = requireNotNull(calendar).id
        val title = "PalmClaw QA ${UUID.randomUUID()}"
        val start = (System.currentTimeMillis() / 1000L) * 1000L + 172_800_000L
        val end = start + 3_600_000L
        val selection = "${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.TITLE}=? AND ${CalendarContract.Events.DELETED}=0"
        val selectionArgs = arrayOf(calendarId.toString(), title)
        fun fixtureIds(): List<Long> = checkNotNull(resolver.query(CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID), selection, selectionArgs, null)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
        val tool = CalendarControlTool(context, confirmationRequester = { _, _, _, _ -> true })
        val registry = ToolRegistry(mapOf(tool.name to tool), debugLog = {})
        suspend fun call(action: String, fields: JsonObjectBuilder.() -> Unit = {}): ToolResult {
            val args = buildJsonObject {
                put("action", action)
                put("request_if_missing", false)
                fields()
            }
            val result = registry.execute(ToolCall("calendar-device-qa", tool.name, args.toString()))
            assertFalse("$action returned an error: ${result.content}", result.isError)
            return result
        }
        assertTrue(fixtureIds().isEmpty())
        try {
            val created = call("create_event") {
                put("title", title)
                put("calendar_id", calendarId)
                put("start_ms", start)
                put("end_ms", end)
                if (recurring) putJsonObject("recurrence") { put("frequency", "daily"); put("count", 2) }
            }
            assertEquals("verified", created.metadata?.get("verification_status")?.jsonPrimitive?.content)
            val eventId = requireNotNull(created.metadata?.get("event_id")?.jsonPrimitive?.long)
            assertEquals(listOf(eventId), fixtureIds())
            val fetched = call("get_event") { put("event_id", eventId) }
            assertEquals(title, fetched.metadata?.get("title")?.jsonPrimitive?.content)
            val listed = call("list_events") {
                put("from_ms", start - 1000L)
                put("to_ms", end + if (recurring) 86_400_000L else 1000L)
                put("count", 100)
            }
            val ownInstances = requireNotNull(listed.metadata?.get("events")?.jsonArray).filter {
                it.jsonObject["event_id"]?.jsonPrimitive?.long == eventId
            }
            assertEquals(if (recurring) 2 else 1, ownInstances.size)
            call("list_calendars")
            call("update_event") { put("event_id", eventId); put("location", "PalmClaw QA location") }
            val updated = call("get_event") { put("event_id", eventId) }
            assertEquals("PalmClaw QA location", updated.metadata?.get("location")?.jsonPrimitive?.content)
            // Confirmation is bypassed only for this test's newly created event ID.
            call("delete_event") { put("event_id", eventId) }
            assertTrue(fixtureIds().isEmpty())
            report("${if (recurring) "recurring" else "single"} event create/get/list/update/delete: PASS")
        } finally {
            // If an assertion fails after insertion, clean only this run's exact unique fixture.
            fixtureIds().forEach { id ->
                resolver.delete(CalendarContract.Events.CONTENT_URI,
                    "${CalendarContract.Events._ID}=? AND ${CalendarContract.Events.CALENDAR_ID}=? AND ${CalendarContract.Events.TITLE}=?",
                    arrayOf(id.toString(), calendarId.toString(), title))
            }
            assertTrue("Temporary QA event must not remain", fixtureIds().isEmpty())
            report("fixture cleanup: PASS")
        }
    }

    private fun report(message: String) {
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "[CalendarDeviceQA] $message\n") })
    }
}
