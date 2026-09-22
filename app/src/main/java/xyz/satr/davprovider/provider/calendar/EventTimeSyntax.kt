package xyz.satr.davprovider.provider.calendar

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The storage syntax [android.provider.CalendarContract] expects, in one place.
 *
 * The provider never reports a violation of it: `CalendarProvider2.scrubEventData` silently
 * removes DTEND from a row that also carries DURATION (which expands a recurring event into
 * zero-length instances) and `fixAllDayTime` rewrites the all-day duration forms. Every rule
 * below is therefore enforced before the write rather than checked after it.
 *
 * Deliberately free of Android and ical4j types — these are the rules that need unit tests.
 */

private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
private const val SECONDS_PER_DAY = 24L * 60L * 60L

private val BASIC_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMdd")
private val BASIC_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss")

/**
 * One date or date-time exactly as ical4j renders it (`getValue()`), before any timezone
 * interpretation. ical4j re-renders parsed values into the basic format, so [text] is either
 * `yyyyMMdd` (a `VALUE=DATE`) or `yyyyMMdd'T'HHmmss` with an optional trailing `Z`.
 */
internal data class IcalDate(val text: String, val tzid: String?) {
    /** `VALUE=DATE`: an eight-character basic date. */
    val isDate: Boolean get() = text.length == 8
}

/** Which of the two mutually exclusive end-time columns the row must carry. */
internal enum class EventShape { RECURRING_MASTER, SINGLE, OVERRIDE }

/**
 * The time columns of one `Events` row, already in storage syntax.
 *
 * [dtEnd] and [duration] are mutually exclusive by construction: exactly one of them is non-null,
 * which is what `scrubEventData` requires. [end] always carries the real end instant (for a
 * recurring master it is start plus the duration) because reminders are offset from it.
 */
internal data class EventTimes(
    val dtStart: Long,
    val dtEnd: Long?,
    val duration: String?,
    val allDay: Boolean,
    val eventTimeZone: String,
    val eventEndTimeZone: String?,
    val end: Long,
    val originalInstanceTime: Long?,
    val originalAllDay: Boolean?,
)

/**
 * Maps one iCalendar component onto the row's time columns.
 *
 * @param explicitDuration the `DURATION` property value, kept verbatim for timed rows.
 * @param startZone the zone [start] is interpreted in when it carries no `Z`.
 * @param endZone the zone of the `DTEND` property, when it differs from [startZone].
 * @param recurrenceIdZone the zone of `RECURRENCE-ID`, used for the original instance time.
 * @return null when the component cannot be placed in time at all; the caller skips it rather
 *   than writing a row whose columns contradict each other.
 */
internal fun eventTimes(
    shape: EventShape,
    start: IcalDate,
    end: IcalDate?,
    recurrenceId: IcalDate?,
    explicitDuration: String?,
    startZone: ZoneId,
    endZone: ZoneId?,
    recurrenceIdZone: ZoneId?,
): EventTimes? {
    val allDay = start.isDate
    // All-day rows are UTC by definition; truncating to UTC midnight is what the provider's own
    // fixAllDayTime() would do anyway, and doing it here keeps the epoch deterministic.
    val dayZone = if (allDay) ZoneOffset.UTC else startZone
    val dtStart = epochMillis(start, dayZone) ?: return null

    val explicitEnd = if (end == null) null else epochMillis(end, if (allDay) ZoneOffset.UTC else endZone ?: startZone)
    if (end != null && explicitEnd == null) return null
    // RFC 5545 §3.6.1: DTEND and DURATION are alternatives on any VEVENT, not only a recurring one.
    // A DATE start with neither lasts one day, a DATE-TIME start with neither has no duration at
    // all. Reading DURATION only for the recurring master turned a perfectly legal
    // "DTSTART + DURATION:PT1H" single event into a zero-length row, and an edit to its time then
    // wrote that zero length back to the server as PT0S.
    val durationMillis = explicitDuration
        ?.takeIf { it.isNotBlank() }
        ?.let { durationSeconds(it) }
        ?.takeIf { it > 0 }
        ?.times(1000L)
    val endInstant = explicitEnd
        ?: durationMillis?.let { dtStart + it }
        ?: if (allDay) dtStart + MILLIS_PER_DAY else dtStart

    val endTimeZone = if (!allDay && end != null && endZone != null && endZone != startZone) {
        eventTimeZoneId(endZone)
    } else {
        null
    }

    return when (shape) {
        EventShape.RECURRING_MASTER -> EventTimes(
            dtStart = dtStart,
            dtEnd = null,
            duration = explicitDuration?.takeIf { it.isNotBlank() }
                ?: durationStorageValue((endInstant - dtStart) / 1000L, allDay),
            allDay = allDay,
            eventTimeZone = eventTimeZoneId(dayZone),
            eventEndTimeZone = endTimeZone,
            end = endInstant,
            originalInstanceTime = null,
            originalAllDay = null,
        )

        EventShape.SINGLE -> EventTimes(
            dtStart = dtStart,
            dtEnd = endInstant,
            duration = null,
            allDay = allDay,
            eventTimeZone = eventTimeZoneId(dayZone),
            eventEndTimeZone = endTimeZone,
            end = endInstant,
            originalInstanceTime = null,
            originalAllDay = null,
        )

        EventShape.OVERRIDE -> {
            val original = recurrenceId ?: return null
            val originalZone = if (original.isDate) ZoneOffset.UTC else recurrenceIdZone ?: startZone
            val originalInstance = epochMillis(original, originalZone) ?: return null
            EventTimes(
                dtStart = dtStart,
                dtEnd = endInstant,
                duration = null,
                allDay = allDay,
                eventTimeZone = eventTimeZoneId(dayZone),
                eventEndTimeZone = endTimeZone,
                end = endInstant,
                originalInstanceTime = originalInstance,
                // CalendarProvider2 requires ORIGINAL_SYNC_ID *and* ORIGINAL_INSTANCE_TIME for an
                // exception; ORIGINAL_ALL_DAY tells it which occurrence the exception replaces.
                originalAllDay = original.isDate,
            )
        }
    }
}

/** Epoch millis for one iCalendar date or date-time; null when the text is not basic format. */
internal fun epochMillis(date: IcalDate, zone: ZoneId): Long? {
    val text = date.text.trim()
    if (date.isDate) {
        return runCatching { LocalDate.parse(text, BASIC_DATE) }.getOrNull()
            ?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
    }
    val utc = text.endsWith("Z", ignoreCase = true)
    val local = runCatching {
        LocalDateTime.parse(if (utc) text.dropLast(1) else text, BASIC_DATE_TIME)
    }.getOrNull() ?: return null
    return local.atZone(if (utc) ZoneOffset.UTC else zone).toInstant().toEpochMilli()
}

/**
 * The `DURATION` column value for a recurring row.
 *
 * Mirrors AOSP's own conversion in `RecurrenceSet.computeDuration`: whole days for an all-day row,
 * seconds otherwise. The days form is not cosmetic — `fixAllDayTime` parses the digits of a
 * seconds form with `Integer.parseInt` and would throw on a signed or `T`-prefixed value.
 */
internal fun durationStorageValue(seconds: Long, allDay: Boolean): String {
    val positive = seconds.coerceAtLeast(0L)
    return if (allDay && positive % SECONDS_PER_DAY == 0L) {
        "P${positive / SECONDS_PER_DAY}D"
    } else {
        "P${positive}S"
    }
}

/**
 * The `EVENT_TIMEZONE` column value for a resolved zone.
 *
 * `java.util.TimeZone.getTimeZone`, which the calendar stack uses to read the column back, only
 * recognises region ids and `GMT`-prefixed offset ids; a bare `+02:00` would silently become GMT.
 */
internal fun eventTimeZoneId(zone: ZoneId): String = when {
    zone == ZoneOffset.UTC -> "UTC"
    zone is ZoneOffset -> "GMT${zone.id}"
    else -> zone.id
}

/**
 * The `RDATE`/`EXDATE` column value for a list of dates.
 *
 * The provider parses these columns itself: an optional `TZID;` prefix followed by a
 * comma-separated list of basic dates or date-times, never the iCalendar property text
 * (`RecurrenceSet.parseRecurrenceDates`). Several properties with different TZIDs become several
 * lines. All-day rows drop the prefix: `Time.parse` would otherwise interpret the dates in that
 * zone and disagree with the row's UTC-midnight DTSTART.
 */
internal fun recurrenceListStorage(dates: List<IcalDate>, allDay: Boolean): String? {
    if (dates.isEmpty()) return null
    if (allDay) return dates.joinToString(",") { it.text }
    return dates.groupBy { it.tzid }
        .map { (tzid, group) ->
            val list = group.joinToString(",") { it.text }
            if (tzid.isNullOrBlank()) list else "$tzid;$list"
        }
        .joinToString("\n")
}

/**
 * Seconds for an iCalendar `DURATION` value (`-PT15M`, `P1D`, `PT1H30M`), or null when the text is
 * not a duration. The sign is preserved: a `TRIGGER` may be negative, and `Reminders.MINUTES` is a
 * signed offset from `DTSTART`.
 */
internal fun durationSeconds(text: String): Long? {
    var rest = text.trim()
    if (rest.isEmpty()) return null
    var sign = 1L
    when (rest.first()) {
        '-' -> { sign = -1L; rest = rest.substring(1) }
        '+' -> rest = rest.substring(1)
        else -> Unit
    }
    if (!rest.startsWith("P") && !rest.startsWith("p")) return null
    rest = rest.substring(1).replace("T", "").replace("t", "")
    var total = 0L
    var index = 0
    while (index < rest.length) {
        val digitsStart = index
        while (index < rest.length && rest[index].isDigit()) index++
        if (index == digitsStart || index >= rest.length) return null
        val count = rest.substring(digitsStart, index).toLongOrNull() ?: return null
        total += when (rest[index]) {
            'W', 'w' -> count * 7L * SECONDS_PER_DAY
            'D', 'd' -> count * SECONDS_PER_DAY
            'H', 'h' -> count * 3600L
            'M', 'm' -> count * 60L
            'S', 's' -> count
            else -> return null
        }
        index++
    }
    return sign * total
}

/**
 * `Reminders.MINUTES` for a `TRIGGER` expressed relative to the event, or null when the offset
 * cannot be represented.
 *
 * @param triggerSeconds the trigger value; negative means before the related end point.
 * @param relatedToEnd `TRIGGER;RELATED=END`.
 * @param durationSeconds the event's own length, needed to move an end-relative offset onto the
 *   start-relative column.
 */
internal fun relativeTriggerMinutes(
    triggerSeconds: Long,
    relatedToEnd: Boolean,
    durationSeconds: Long,
): Long {
    val fromStart = if (relatedToEnd) durationSeconds + triggerSeconds else triggerSeconds
    return -fromStart / 60L
}
