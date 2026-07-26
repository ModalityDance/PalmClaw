package com.palmclaw.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarRecurrenceCodecTest {

    @Test
    fun `weekly recurrence maps structured fields to android values`() {
        val result = CalendarRecurrenceCodec.encode(
            spec = CalendarRecurrenceSpec(
                frequency = "weekly",
                interval = 2,
                count = 10,
                by_weekdays = listOf("monday", "friday"),
                week_start = "monday",
                additional_dates_ms = listOf(1_782_864_000_000L),
                excluded_dates_ms = listOf(1_782_259_200_000L)
            ),
            startMs = 1_782_000_000_000L,
            endMs = 1_782_003_600_000L,
            allDay = false
        )

        require(result is CalendarRecurrenceEncodeResult.Success)
        assertEquals("FREQ=WEEKLY;INTERVAL=2;COUNT=10;BYDAY=MO,FR;WKST=MO", result.rrule)
        assertEquals("20260701T000000Z", result.rdate)
        assertEquals("20260624T000000Z", result.exdate)
        assertEquals("PT3600S", result.duration)
    }

    @Test
    fun `monthly positional recurrence is encoded without raw rrule input`() {
        val result = CalendarRecurrenceCodec.encode(
            spec = CalendarRecurrenceSpec(
                frequency = "monthly",
                by_weekdays = listOf("tuesday"),
                by_set_positions = listOf(2)
            ),
            startMs = 1_782_000_000_000L,
            endMs = 1_782_001_800_000L,
            allDay = false
        )

        require(result is CalendarRecurrenceEncodeResult.Success)
        assertEquals("FREQ=MONTHLY;BYDAY=TU;BYSETPOS=2", result.rrule)
    }

    @Test
    fun `count and until are mutually exclusive`() {
        val result = CalendarRecurrenceCodec.encode(
            spec = CalendarRecurrenceSpec(
                frequency = "daily",
                count = 3,
                until_ms = 1_900_000_000_000L
            ),
            startMs = 1_782_000_000_000L,
            endMs = 1_782_003_600_000L,
            allDay = false
        )

        require(result is CalendarRecurrenceEncodeResult.Invalid)
        assertEquals("recurrence_end_conflict", result.code)
    }

    @Test
    fun `all day recurrence uses complete utc days`() {
        val start = 1_782_000_000_000L
        val result = CalendarRecurrenceCodec.encode(
            spec = CalendarRecurrenceSpec(frequency = "yearly"),
            startMs = start,
            endMs = start + 2 * CalendarRecurrenceCodec.DAY_MS,
            allDay = true
        )

        require(result is CalendarRecurrenceEncodeResult.Success)
        assertEquals("P2D", result.duration)
    }

    @Test
    fun `recurrence dates reject precision loss and add exclude conflicts`() {
        val imprecise = CalendarRecurrenceCodec.encode(
            spec = CalendarRecurrenceSpec(frequency = "daily", additional_dates_ms = listOf(1_782_000_000_001L)),
            startMs = 1_782_000_000_000L,
            endMs = 1_782_003_600_000L,
            allDay = false
        )
        require(imprecise is CalendarRecurrenceEncodeResult.Invalid)
        assertEquals("invalid_recurrence_date_precision", imprecise.code)

        val conflict = CalendarRecurrenceCodec.encode(
            spec = CalendarRecurrenceSpec(
                frequency = "daily",
                additional_dates_ms = listOf(1_782_864_000_000L),
                excluded_dates_ms = listOf(1_782_864_000_000L)
            ),
            startMs = 1_782_000_000_000L,
            endMs = 1_782_003_600_000L,
            allDay = false
        )
        require(conflict is CalendarRecurrenceEncodeResult.Invalid)
        assertEquals("conflicting_recurrence_date", conflict.code)
    }

    @Test
    fun `supported provider rule round trips to structured view`() {
        val decoded = CalendarRecurrenceCodec.decode(
            rrule = "FREQ=MONTHLY;INTERVAL=1;BYDAY=TU;BYSETPOS=2",
            rdate = null,
            exdate = null
        )

        assertTrue(decoded.supported)
        assertEquals("monthly", decoded.spec?.frequency)
        assertEquals(listOf("tuesday"), decoded.spec?.by_weekdays)
        assertEquals(listOf(2), decoded.spec?.by_set_positions)
    }

    @Test
    fun `unknown provider clauses remain available without partial structured rule`() {
        val decoded = CalendarRecurrenceCodec.decode(
            rrule = "FREQ=DAILY;BYHOUR=9",
            rdate = "TZID=Asia/Shanghai:20260701T090000",
            exdate = null
        )

        assertFalse(decoded.supported)
        assertNull(decoded.spec)
        assertEquals("FREQ=DAILY;BYHOUR=9", decoded.rawRrule)
        assertEquals("TZID=Asia/Shanghai:20260701T090000", decoded.rawRdate)
    }

    @Test
    fun `invalid provider rule remains raw instead of becoming structured`() {
        val decoded = CalendarRecurrenceCodec.decode(
            rrule = "FREQ=DAILY;COUNT=0;UNTIL=20260701T000000Z",
            rdate = null,
            exdate = null
        )

        assertFalse(decoded.supported)
        assertNull(decoded.spec)
        assertEquals("FREQ=DAILY;COUNT=0;UNTIL=20260701T000000Z", decoded.rawRrule)
    }

    @Test
    fun `duration parser handles provider week day and time values`() {
        assertEquals(14 * CalendarRecurrenceCodec.DAY_MS, CalendarRecurrenceCodec.parseDurationMillis("P2W"))
        assertEquals(CalendarRecurrenceCodec.DAY_MS, CalendarRecurrenceCodec.parseDurationMillis("P1D"))
        assertEquals(3_661_000L, CalendarRecurrenceCodec.parseDurationMillis("PT1H1M1S"))
        assertNull(CalendarRecurrenceCodec.parseDurationMillis("PT"))
        assertNull(CalendarRecurrenceCodec.parseDurationMillis("invalid"))
    }
}
