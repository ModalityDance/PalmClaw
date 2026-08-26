package com.palmclaw.tools

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
internal data class CalendarRecurrenceSpec(
    val frequency: String,
    val interval: Int? = null,
    val count: Int? = null,
    val until_ms: Long? = null,
    val by_weekdays: List<String> = emptyList(),
    val by_month_days: List<Int> = emptyList(),
    val by_months: List<Int> = emptyList(),
    val by_set_positions: List<Int> = emptyList(),
    val week_start: String? = null,
    val additional_dates_ms: List<Long> = emptyList(),
    val excluded_dates_ms: List<Long> = emptyList()
)

@Serializable
internal data class CalendarReminderInput(
    val minutes_before: Int,
    val method: String = "alert"
)

@Serializable
internal data class CalendarAttendeeInput(
    val email: String,
    val name: String? = null,
    val type: String = "required"
)

internal sealed interface CalendarRecurrenceEncodeResult {
    data class Success(
        val rrule: String,
        val rdate: String?,
        val exdate: String?,
        val duration: String
    ) : CalendarRecurrenceEncodeResult

    data class Invalid(
        val code: String,
        val message: String
    ) : CalendarRecurrenceEncodeResult
}

internal data class CalendarRecurrenceView(
    val supported: Boolean,
    val spec: CalendarRecurrenceSpec?,
    val rawRrule: String?,
    val rawRdate: String?,
    val rawExdate: String?
)

internal object CalendarRecurrenceCodec {
    const val DAY_MS = 24L * 60L * 60L * 1000L

    private val dayToCode = linkedMapOf(
        "monday" to "MO",
        "tuesday" to "TU",
        "wednesday" to "WE",
        "thursday" to "TH",
        "friday" to "FR",
        "saturday" to "SA",
        "sunday" to "SU"
    )
    private val codeToDay = dayToCode.entries.associate { (day, code) -> code to day }
    private val utcDateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)
    private val utcDateFormatter = DateTimeFormatter.BASIC_ISO_DATE

    fun encode(
        spec: CalendarRecurrenceSpec,
        startMs: Long,
        endMs: Long,
        allDay: Boolean
    ): CalendarRecurrenceEncodeResult {
        val frequency = spec.frequency.trim().lowercase(Locale.US)
        val frequencyCode = when (frequency) {
            "daily" -> "DAILY"
            "weekly" -> "WEEKLY"
            "monthly" -> "MONTHLY"
            "yearly" -> "YEARLY"
            else -> return invalid("invalid_recurrence_frequency", "Unsupported recurrence frequency '${spec.frequency}'.")
        }
        val interval = spec.interval ?: 1
        if (interval !in 1..999) {
            return invalid("invalid_recurrence_interval", "interval must be between 1 and 999.")
        }
        if (spec.count != null && spec.until_ms != null) {
            return invalid("recurrence_end_conflict", "count and until_ms cannot both be set.")
        }
        if (spec.count != null && spec.count !in 1..9999) {
            return invalid("invalid_recurrence_count", "count must be between 1 and 9999.")
        }
        if (spec.until_ms != null && spec.until_ms < startMs) {
            return invalid("invalid_recurrence_until", "until_ms must not be before start_ms.")
        }

        val weekdays = spec.by_weekdays.map { it.trim().lowercase(Locale.US) }
        if (weekdays.size > 7 || weekdays.any { it !in dayToCode }) {
            return invalid("invalid_recurrence_weekdays", "by_weekdays must contain valid unique weekday names.")
        }
        if (weekdays.distinct().size != weekdays.size) {
            return invalid("duplicate_recurrence_value", "by_weekdays cannot contain duplicate values.")
        }
        if (spec.by_month_days.size > 62 || spec.by_month_days.any { it == 0 || it !in -31..31 }) {
            return invalid("invalid_recurrence_month_days", "by_month_days values must be -31..-1 or 1..31.")
        }
        if (spec.by_month_days.distinct().size != spec.by_month_days.size) {
            return invalid("duplicate_recurrence_value", "by_month_days cannot contain duplicate values.")
        }
        if (spec.by_months.size > 12 || spec.by_months.any { it !in 1..12 }) {
            return invalid("invalid_recurrence_months", "by_months values must be between 1 and 12.")
        }
        if (spec.by_months.distinct().size != spec.by_months.size) {
            return invalid("duplicate_recurrence_value", "by_months cannot contain duplicate values.")
        }
        if (spec.by_set_positions.size > 32 || spec.by_set_positions.any { it == 0 || it !in -366..366 }) {
            return invalid("invalid_recurrence_positions", "by_set_positions values must be -366..-1 or 1..366.")
        }
        if (spec.by_set_positions.distinct().size != spec.by_set_positions.size) {
            return invalid("duplicate_recurrence_value", "by_set_positions cannot contain duplicate values.")
        }
        if (spec.by_set_positions.isNotEmpty() &&
            weekdays.isEmpty() && spec.by_month_days.isEmpty() && spec.by_months.isEmpty()
        ) {
            return invalid("invalid_recurrence_positions", "by_set_positions requires another by_* field.")
        }
        val weekStart = spec.week_start?.trim()?.lowercase(Locale.US)
        if (weekStart != null && weekStart !in dayToCode) {
            return invalid("invalid_recurrence_week_start", "week_start must be a weekday name.")
        }
        val boundedDates = spec.additional_dates_ms.size <= MAX_DATE_VALUES &&
            spec.excluded_dates_ms.size <= MAX_DATE_VALUES
        if (!boundedDates) {
            return invalid("too_many_recurrence_dates", "additional_dates_ms and excluded_dates_ms allow at most $MAX_DATE_VALUES values each.")
        }
        if (spec.additional_dates_ms.distinct().size != spec.additional_dates_ms.size ||
            spec.excluded_dates_ms.distinct().size != spec.excluded_dates_ms.size
        ) {
            return invalid("duplicate_recurrence_value", "Recurrence date arrays cannot contain duplicate values.")
        }
        if (spec.additional_dates_ms.any(spec.excluded_dates_ms.toSet()::contains)) {
            return invalid("conflicting_recurrence_date", "A recurrence date cannot be both additional and excluded.")
        }
        val recurrenceDates = listOfNotNull(spec.until_ms) + spec.additional_dates_ms + spec.excluded_dates_ms
        val invalidDatePrecision = if (allDay) {
            recurrenceDates.any { it % DAY_MS != 0L }
        } else {
            recurrenceDates.any { it % 1000L != 0L }
        }
        if (invalidDatePrecision) {
            return invalid(
                "invalid_recurrence_date_precision",
                if (allDay) {
                    "All-day recurrence dates must use UTC midnight boundaries."
                } else {
                    "Timed recurrence dates must use whole-second timestamps."
                }
            )
        }

        val duration = durationFor(startMs, endMs, allDay)
            ?: return invalid(
                "invalid_recurrence_duration",
                if (allDay) {
                    "All-day recurring events require a positive whole-day duration."
                } else {
                    "Recurring events require a positive whole-second duration."
                }
            )

        val clauses = mutableListOf("FREQ=$frequencyCode")
        if (interval != 1) clauses += "INTERVAL=$interval"
        spec.count?.let { clauses += "COUNT=$it" }
        spec.until_ms?.let { clauses += "UNTIL=${formatDate(it, allDay)}" }
        if (weekdays.isNotEmpty()) clauses += "BYDAY=${weekdays.joinToString(",") { dayToCode.getValue(it) }}"
        if (spec.by_month_days.isNotEmpty()) clauses += "BYMONTHDAY=${spec.by_month_days.joinToString(",")}"
        if (spec.by_months.isNotEmpty()) clauses += "BYMONTH=${spec.by_months.joinToString(",")}"
        if (spec.by_set_positions.isNotEmpty()) clauses += "BYSETPOS=${spec.by_set_positions.joinToString(",")}"
        weekStart?.let { clauses += "WKST=${dayToCode.getValue(it)}" }

        return CalendarRecurrenceEncodeResult.Success(
            rrule = clauses.joinToString(";"),
            rdate = encodeDateList(spec.additional_dates_ms, allDay),
            exdate = encodeDateList(spec.excluded_dates_ms, allDay),
            duration = duration
        )
    }

    fun decode(rrule: String?, rdate: String?, exdate: String?): CalendarRecurrenceView {
        val rawRule = rrule?.trim()?.takeIf { it.isNotEmpty() }
        val rawRdate = rdate?.trim()?.takeIf { it.isNotEmpty() }
        val rawExdate = exdate?.trim()?.takeIf { it.isNotEmpty() }
        if (rawRule == null) {
            return CalendarRecurrenceView(
                supported = false,
                spec = null,
                rawRrule = null,
                rawRdate = rawRdate,
                rawExdate = rawExdate
            )
        }

        val clauses = linkedMapOf<String, String>()
        for (part in rawRule.split(';')) {
            val key = part.substringBefore('=', "").uppercase(Locale.US)
            val value = part.substringAfter('=', "")
            if (key.isBlank() || value.isBlank() || key !in SUPPORTED_CLAUSES || clauses.put(key, value) != null) {
                return unsupported(rawRule, rawRdate, rawExdate)
            }
        }
        val frequency = when (clauses["FREQ"]?.uppercase(Locale.US)) {
            "DAILY" -> "daily"
            "WEEKLY" -> "weekly"
            "MONTHLY" -> "monthly"
            "YEARLY" -> "yearly"
            else -> return unsupported(rawRule, rawRdate, rawExdate)
        }
        val interval = clauses["INTERVAL"]?.toIntOrNull() ?: 1
        val count = clauses["COUNT"]?.toIntOrNull()
        val until = clauses["UNTIL"]?.let(::parseDateValue)
        if (interval !in 1..999 || (clauses.containsKey("COUNT") && count == null) ||
            (clauses.containsKey("UNTIL") && until == null)
        ) {
            return unsupported(rawRule, rawRdate, rawExdate)
        }
        val weekdays = parseWeekdays(clauses["BYDAY"]) ?: return unsupported(rawRule, rawRdate, rawExdate)
        val monthDays = parseIntList(clauses["BYMONTHDAY"]) ?: return unsupported(rawRule, rawRdate, rawExdate)
        val months = parseIntList(clauses["BYMONTH"]) ?: return unsupported(rawRule, rawRdate, rawExdate)
        val positions = parseIntList(clauses["BYSETPOS"]) ?: return unsupported(rawRule, rawRdate, rawExdate)
        val weekStart = clauses["WKST"]?.uppercase(Locale.US)?.let { codeToDay[it] ?: return unsupported(rawRule, rawRdate, rawExdate) }
        val additionalDates = parseDateList(rawRdate) ?: return unsupported(rawRule, rawRdate, rawExdate)
        val excludedDates = parseDateList(rawExdate) ?: return unsupported(rawRule, rawRdate, rawExdate)
        val invalidStructuredValues = count != null && count !in 1..9999 ||
            count != null && until != null ||
            weekdays.size > 7 || weekdays.distinct().size != weekdays.size ||
            monthDays.size > 62 || monthDays.any { it == 0 || it !in -31..31 } || monthDays.distinct().size != monthDays.size ||
            months.size > 12 || months.any { it !in 1..12 } || months.distinct().size != months.size ||
            positions.size > 32 || positions.any { it == 0 || it !in -366..366 } || positions.distinct().size != positions.size ||
            positions.isNotEmpty() && weekdays.isEmpty() && monthDays.isEmpty() && months.isEmpty() ||
            additionalDates.size > MAX_DATE_VALUES || excludedDates.size > MAX_DATE_VALUES ||
            additionalDates.distinct().size != additionalDates.size || excludedDates.distinct().size != excludedDates.size ||
            additionalDates.any(excludedDates.toSet()::contains)
        if (invalidStructuredValues) return unsupported(rawRule, rawRdate, rawExdate)

        return CalendarRecurrenceView(
            supported = true,
            spec = CalendarRecurrenceSpec(
                frequency = frequency,
                interval = interval.takeIf { it != 1 },
                count = count,
                until_ms = until,
                by_weekdays = weekdays,
                by_month_days = monthDays,
                by_months = months,
                by_set_positions = positions,
                week_start = weekStart,
                additional_dates_ms = additionalDates,
                excluded_dates_ms = excludedDates
            ),
            rawRrule = rawRule,
            rawRdate = rawRdate,
            rawExdate = rawExdate
        )
    }

    fun parseDurationMillis(value: String?): Long? {
        val raw = value?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotEmpty() } ?: return null
        val weekMatch = WEEK_DURATION.matchEntire(raw)
        if (weekMatch != null) return weekMatch.groupValues[1].toLongOrNull()?.times(7L * DAY_MS)
        val dayMatch = DAY_DURATION.matchEntire(raw)
        if (dayMatch != null) return dayMatch.groupValues[1].toLongOrNull()?.times(DAY_MS)
        val timeMatch = TIME_DURATION.matchEntire(raw) ?: return null
        if (timeMatch.groupValues.drop(1).all { it.isEmpty() }) return null
        val hours = timeMatch.groupValues[1].toLongOrNull() ?: 0L
        val minutes = timeMatch.groupValues[2].toLongOrNull() ?: 0L
        val seconds = timeMatch.groupValues[3].toLongOrNull() ?: 0L
        return ((hours * 60L + minutes) * 60L + seconds) * 1000L
    }

    fun durationFor(startMs: Long, endMs: Long, allDay: Boolean): String? {
        val durationMs = endMs - startMs
        if (durationMs <= 0L) return null
        return if (allDay) {
            if (durationMs % DAY_MS != 0L) null else "P${durationMs / DAY_MS}D"
        } else {
            if (durationMs % 1000L != 0L) null else "PT${durationMs / 1000L}S"
        }
    }

    private fun encodeDateList(values: List<Long>, allDay: Boolean): String? {
        return values.sorted().takeIf { it.isNotEmpty() }?.joinToString(",") { formatDate(it, allDay) }
    }

    private fun formatDate(value: Long, allDay: Boolean): String {
        return if (allDay) {
            utcDateFormatter.format(Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC).toLocalDate())
        } else {
            utcDateTimeFormatter.format(Instant.ofEpochMilli(value))
        }
    }

    private fun parseDateList(value: String?): List<Long>? {
        if (value.isNullOrBlank()) return emptyList()
        if (value.contains(':') || value.contains(';')) return null
        return value.split(',').map { parseDateValue(it) ?: return null }
    }

    private fun parseDateValue(value: String): Long? {
        return runCatching {
            when {
                value.length == 8 -> LocalDate.parse(value, utcDateFormatter)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
                value.length == 16 && value.endsWith('Z') -> Instant.from(utcDateTimeFormatter.parse(value)).toEpochMilli()
                else -> null
            }
        }.getOrNull()
    }

    private fun parseWeekdays(value: String?): List<String>? {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(',').map { codeToDay[it.uppercase(Locale.US)] ?: return null }
    }

    private fun parseIntList(value: String?): List<Int>? {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(',').map { it.toIntOrNull() ?: return null }
    }

    private fun invalid(code: String, message: String): CalendarRecurrenceEncodeResult.Invalid {
        return CalendarRecurrenceEncodeResult.Invalid(code, message)
    }

    private fun unsupported(rrule: String?, rdate: String?, exdate: String?): CalendarRecurrenceView {
        return CalendarRecurrenceView(false, null, rrule, rdate, exdate)
    }

    private const val MAX_DATE_VALUES = 100
    private val SUPPORTED_CLAUSES = setOf(
        "FREQ", "INTERVAL", "COUNT", "UNTIL", "BYDAY", "BYMONTHDAY", "BYMONTH", "BYSETPOS", "WKST"
    )
    private val WEEK_DURATION = Regex("P(\\d+)W")
    private val DAY_DURATION = Regex("P(\\d+)D")
    private val TIME_DURATION = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?")
}
