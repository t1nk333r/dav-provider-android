package xyz.satr.davprovider.provider.calendar

import net.fortuna.ical4j.data.CalendarOutputter
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.ComponentList
import net.fortuna.ical4j.model.DateList
import net.fortuna.ical4j.model.Parameter
import net.fortuna.ical4j.model.ParameterList
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.PropertyContainer
import net.fortuna.ical4j.model.PropertyList
import net.fortuna.ical4j.model.TimeZoneRegistry
import net.fortuna.ical4j.model.TimeZoneRegistryFactory
import net.fortuna.ical4j.model.component.CalendarComponent
import net.fortuna.ical4j.model.component.VAlarm
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.component.VTimeZone
import net.fortuna.ical4j.model.parameter.TzId
import net.fortuna.ical4j.model.parameter.Value
import net.fortuna.ical4j.model.property.Action
import net.fortuna.ical4j.model.property.Created
import net.fortuna.ical4j.model.property.Description
import net.fortuna.ical4j.model.property.DtEnd
import net.fortuna.ical4j.model.property.DtStamp
import net.fortuna.ical4j.model.property.DtStart
import net.fortuna.ical4j.model.property.Duration
import net.fortuna.ical4j.model.property.ExDate
import net.fortuna.ical4j.model.property.LastModified
import net.fortuna.ical4j.model.property.Location
import net.fortuna.ical4j.model.property.ProdId
import net.fortuna.ical4j.model.property.RDate
import net.fortuna.ical4j.model.property.RRule
import net.fortuna.ical4j.model.property.RecurrenceId
import net.fortuna.ical4j.model.property.Sequence
import net.fortuna.ical4j.model.property.Status
import net.fortuna.ical4j.model.property.Summary
import net.fortuna.ical4j.model.property.Transp
import net.fortuna.ical4j.model.property.Trigger
import net.fortuna.ical4j.model.property.Uid
import net.fortuna.ical4j.model.property.Version
import java.io.StringWriter
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.Temporal

/**
 * One resource's rows turned into the bytes a `PUT` sends.
 *
 * Store-and-patch: a resource the read path fetched still carries the server's own text on its
 * master row, so an upload is that text with the properties Android can express rewritten. What the
 * rows have no column for — every `CATEGORIES`, `URL`, `GEO`, `X-`, `VTIMEZONE`, an alarm's
 * `REPEAT`, an attendee's parameters, an `RDATE;VALUE=PERIOD`, the source's choice of `DURATION`
 * over `DTEND` — is absent from the rows and therefore present in the upload, which is the whole
 * reason the text is stored. Synthesis from rows is the fallback for a resource that never had
 * bytes: an event the phone created, whose every property the rows do describe.
 *
 * Deliberately free of Android types, so the rules that decide what an upload contains can be read
 * and tested without a provider: [CalendarMapper] adapts a cursor into [ResourceRows].
 */

/** `PRODID` of every resource this app writes: it names the last writer, so it is always replaced. */
private const val APP_PRODID = "-//satr.xyz//dav-provider-android//EN"

/** `Events.SYNC_DATA3` when the server's text was withheld: nothing to patch, so no upload. */
internal const val SOURCE_OVERSIZE = "oversize"

/**
 * Per-resource cap on the stored text.
 *
 * Not SQLite's limit: an `applyBatch` rides one Binder transaction of 1 MiB and a `CursorWindow` is
 * 2 MiB, so half a megabyte per resource keeps a batch of several of them inside both. Above the cap
 * the text is dropped, the resource is held from upload, and its rows are written as before.
 */
internal const val SOURCE_CAP_BYTES = 512 * 1024

/** UTF-8 length without materialising the array: the cap above is bytes, the column is characters. */
internal fun utf8Length(text: String): Int {
    var bytes = 0
    var index = 0
    while (index < text.length) {
        val code = text[index].code
        bytes += when {
            code < 0x80 -> 1
            code < 0x800 -> 2
            code in 0xD800..0xDBFF && index + 1 < text.length && text[index + 1].code in 0xDC00..0xDFFF -> {
                index++
                4
            }

            else -> 3
        }
        index++
    }
    return bytes
}

/** One `Reminders` row: what the serialiser matches an alarm the source carries against. */
internal data class ReminderRow(val minutes: Int, val method: AlarmMethod)

/**
 * The `Events` columns a patch reads and rewrites, already in storage syntax.
 *
 * Produced both from a parsed component ([eventValuesOf]) and from a cursor row, which is what makes
 * the two comparable: the difference between them *is* the edit.
 */
internal data class EventValues(
    val dtStart: Long?,
    val dtEnd: Long?,
    val duration: String?,
    val allDay: Boolean,
    val eventTimeZone: String?,
    val eventEndTimeZone: String?,
    val title: String?,
    val description: String?,
    val location: String?,
    val rrule: String?,
    val rdate: String?,
    val exdate: String?,
    val status: EventStatus?,
    val transparency: Transparency?,
)

/** One `Events` row of a resource: its master, or one of its overrides. */
internal data class EventRow(
    val id: Long,
    /** `ORIGINAL_INSTANCE_TIME`, the occurrence an override replaces; null on a master. */
    val originalInstanceTime: Long?,
    val originalAllDay: Boolean,
    /** `DELETED=1`: the occurrence is cancelled, which is an `EXDATE` on the master. */
    val deleted: Boolean,
    val values: EventValues,
    val reminders: List<ReminderRow>,
)

/** One resource's rows, as an upload reads them. */
internal data class ResourceRows(
    /** The resource name on the master's row; null for an event the phone created. */
    val key: String?,
    /** `UID_2445`, minted and stored before this snapshot is read when the rows had none. */
    val uid: String,
    /** `SYNC_DATA2`: the resource exactly as the server sent it. */
    val source: String?,
    /** `SYNC_DATA3 == "oversize"`: the text was withheld for size, so there is nothing to patch. */
    val oversize: Boolean,
    val master: EventRow,
    val overrides: List<EventRow>,
)

internal sealed interface Serialized {
    /** [text] is the resource to `PUT`; [notes] name what it does not carry, for the run's log. */
    data class Written(val text: String, val notes: List<String>) : Serialized

    /** The rows do not represent the resource: nothing may be sent, and the user is told why. */
    data class Held(val reason: String) : Serialized
}

/**
 * The columns the read path would write for [event], so a row can be compared with its source.
 *
 * Lifted out of the batch writer unchanged: the read path and the upload have to agree on what a
 * column holds, or an upload would rewrite properties the user never touched.
 */
internal fun eventValuesOf(event: ParsedEvent, times: EventTimes): EventValues {
    val override = event.shape == EventShape.OVERRIDE
    return EventValues(
        dtStart = times.dtStart,
        dtEnd = times.dtEnd,
        duration = times.duration,
        allDay = times.allDay,
        eventTimeZone = times.eventTimeZone,
        eventEndTimeZone = times.eventEndTimeZone,
        title = event.summary,
        description = event.description,
        location = event.location,
        // Several RRULE properties are newline-separated in the column, and an override has none:
        // its recurrence is the master's.
        rrule = if (!override && event.rrules.isNotEmpty()) event.rrules.joinToString("\n") else null,
        rdate = if (override) null else recurrenceListStorage(event.rdates, times.allDay),
        exdate = if (override) null else recurrenceListStorage(event.exdates, times.allDay),
        status = event.status,
        transparency = event.transparency,
    )
}

/** The row's time columns for one component, and what resolving its zones had to report. */
internal class ResolvedTimes(val times: EventTimes?, val notes: List<String>)

/**
 * [eventTimes] with every `TZID` resolved, which is the same choice the read path makes.
 *
 * Shared so that "what the source produces" and "what the row holds" are computed by one rule; an
 * unrecognised `TZID` becomes the device zone here, and a note says when it did.
 */
internal fun timesOf(event: ParsedEvent, zones: IcalZoneIndex, deviceZone: ZoneId, now: Long): ResolvedTimes {
    val start = event.start ?: return ResolvedTimes(null, emptyList())
    val notes = ArrayList<String>()
    val startChoice = resolveZone(start.tzid, zones, deviceZone, now)
    startChoice.note?.let { notes += it }
    val endChoice = event.end?.tzid?.let { tzid ->
        resolveZone(tzid, zones, startChoice.zone, now).also { it.note?.let(notes::add) }
    }
    val recurrenceChoice = event.recurrenceId?.tzid?.let { tzid ->
        resolveZone(tzid, zones, startChoice.zone, now).also { it.note?.let(notes::add) }
    }
    val times = eventTimes(
        shape = event.shape,
        start = start,
        end = event.end,
        recurrenceId = event.recurrenceId,
        explicitDuration = event.duration,
        startZone = startChoice.zone,
        endZone = endChoice?.zone,
        recurrenceIdZone = recurrenceChoice?.zone,
    )
    return ResolvedTimes(times, notes)
}

/**
 * `Reminders.MINUTES` for one alarm, before the clamp the column needs.
 *
 * The raw offset is returned so the caller that writes the column can report the clamp it applies,
 * which is the one place a reminder's meaning changes.
 */
internal fun reminderOffsetMinutes(
    reminder: ParsedReminder,
    times: EventTimes,
    zones: IcalZoneIndex,
    deviceZone: ZoneId,
    now: Long,
): Long? {
    val durationSeconds = (times.end - times.dtStart) / 1000L
    return when {
        reminder.triggerSeconds != null ->
            relativeTriggerMinutes(reminder.triggerSeconds, reminder.relatedToEnd, durationSeconds)

        reminder.triggerAt != null -> {
            val zone = resolveZone(reminder.triggerAt.tzid, zones, deviceZone, now).zone
            val at = epochMillis(reminder.triggerAt, zone) ?: return null
            (times.dtStart - at) / 60000L
        }

        else -> null
    }
}

/** `Reminders.MINUTES` accepts no offset before the event and none beyond an `Int`. */
internal fun clampedReminderMinutes(offset: Long): Int = offset.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

/**
 * The bytes one resource's upload sends.
 *
 * [now] is the upload's clock and [deviceZone] the zone an unrecognised `TZID` is read in — the same
 * two inputs the read path used, because a patch that disagrees with the read path about what a
 * column means would rewrite properties the user never touched.
 */
internal fun serializeResource(rows: ResourceRows, now: Long, deviceZone: ZoneId): Serialized {
    if (rows.oversize) return Serialized.Held("the stored copy of this event was too large to keep")
    val source = rows.source
        ?: return if (rows.key != null) {
            Serialized.Held("this event has no stored copy of the server's text yet")
        } else {
            synthesize(rows, now, deviceZone)
        }

    val calendar = try {
        parseCalendar(source)
    } catch (e: Exception) {
        return Serialized.Held("the stored copy of this event is not readable as iCalendar")
    }
    val components = resourceComponents(calendar)
    if (components.droppedUids.isNotEmpty() || components.droppedComponents > 0) {
        return Serialized.Held("the stored copy holds more than the one event this item addresses")
    }
    val masterComponent = components.master ?: return Serialized.Held("the stored copy holds no event")
    if ((listOf(masterComponent) + components.overrides).any { it.getProperty<Property>(Property.ORGANIZER).isPresent }) {
        return Serialized.Held("this event is a meeting; invitations can only be edited where they were created")
    }

    // The master row continues the component the read path chose as master, and an override row the
    // component whose RECURRENCE-ID names the same occurrence — the identity rowPlan already uses.
    val masterEvent = masterComponent.toParsedEvent().let {
        if (it.recurrenceId == null) it else it.copy(shape = EventShape.SINGLE)
    }
    val masterTimes = timesOf(masterEvent, components.zones, deviceZone, now).times
        ?: return Serialized.Held("the stored event has no usable start time")
    val masterBase = eventValuesOf(masterEvent, masterTimes)
    val masterForm = masterEvent.start?.let { formOf(it) }
    val masterZoneId = masterEvent.start?.tzid
    val masterZone = resolveZone(masterZoneId, components.zones, deviceZone, now).zone

    val parts = components.overrides.mapNotNull { component ->
        val event = component.toParsedEvent()
        timesOf(event, components.zones, deviceZone, now).times?.let { OverridePart(component, event, it) }
    }
    val notes = ArrayList<String>()
    val claimed = HashSet<VEvent>()
    val cancelled = ArrayList<IcalDate>()
    val live = ArrayList<Pair<EventRow, OverridePart?>>()
    for (row in rows.overrides) {
        val part = parts.firstOrNull { it.component !in claimed && it.matches(row) }
        if (part != null) claimed += part.component
        if (row.deleted) {
            // A cancelled instance is an EXDATE on the master; the component that described it, if
            // the source had one, describes an occurrence that no longer happens.
            row.originalInstanceTime?.let {
                cancelled += instanceOf(it, row.originalAllDay, masterForm, masterZoneId, masterZone)
            }
            if (part != null) calendar.dropComponent(part.component)
            continue
        }
        live += row to part
    }

    patchComponent(
        component = masterComponent,
        row = rows.master,
        base = masterBase,
        times = masterTimes,
        spelling = spellingOf(masterComponent, masterEvent),
        isMaster = true,
        cancelled = cancelled,
        zones = components.zones,
        deviceZone = deviceZone,
        now = now,
        notes = notes,
    )
    if (masterEvent.unrepresentableDates > 0 && masterBase.exdate != rows.master.values.exdate) {
        notes += "the recurrence's period dates were not kept"
    }

    for ((row, part) in live) {
        if (row.values.dtStart == null) {
            notes += "one changed instance has no start time and was left out"
            continue
        }
        if (part == null) {
            calendar.addComponent(newOverride(masterComponent, row, masterForm, masterZoneId, components.zones, deviceZone, now))
            continue
        }
        patchComponent(
            component = part.component,
            row = row,
            base = eventValuesOf(part.event, part.times),
            times = part.times,
            spelling = spellingOf(part.component, part.event),
            isMaster = false,
            cancelled = emptyList(),
            zones = components.zones,
            deviceZone = deviceZone,
            now = now,
            notes = notes,
        )
    }

    calendar.replaceProperty(ProdId(APP_PRODID))
    appendMissingZones(calendar)
    return Serialized.Written(render(calendar), notes)
}

/** One override component, parsed, with the occurrence it replaces. */
private class OverridePart(val component: VEvent, val event: ParsedEvent, val times: EventTimes) {
    fun matches(row: EventRow): Boolean =
        times.originalInstanceTime == row.originalInstanceTime && (times.originalAllDay ?: false) == row.originalAllDay
}

/**
 * What the source spelled for the times, which a patch keeps.
 *
 * A resource that changes shape under an unrelated edit is a resource every other client sees move,
 * so the value form (`Z`, floating, `TZID=x`) and the end property (`DTEND` or `DURATION`) of the
 * source are what the patch writes back, even where the row spells the same instant differently.
 */
private class Spelling(
    val startForm: TimeForm?,
    val endForm: TimeForm?,
    /** `Property.DTEND`, `Property.DURATION`, or null when the source carried neither. */
    val endProperty: String?,
)

/** Which of RFC 5545's three `DATE-TIME` spellings a value uses. */
private enum class TimeForm { DATE, UTC, FLOATING, ZONED }

private fun spellingOf(component: VEvent, event: ParsedEvent): Spelling = Spelling(
    startForm = event.start?.let { formOf(it) },
    endForm = event.end?.let { formOf(it) },
    endProperty = when {
        component.getProperty<Property>(Property.DTEND).isPresent -> Property.DTEND
        component.getProperty<Property>(Property.DURATION).isPresent -> Property.DURATION
        else -> null
    },
)

private fun formOf(date: IcalDate): TimeForm = when {
    date.isDate -> TimeForm.DATE
    date.text.endsWith("Z", ignoreCase = true) -> TimeForm.UTC
    date.tzid.isNullOrBlank() -> TimeForm.FLOATING
    else -> TimeForm.ZONED
}

/**
 * Rewrites one component from its row, property by property, and nothing else.
 *
 * Every property the row has no column for is left exactly as the source wrote it: that is what
 * keeps the read path's loss list from becoming the upload's.
 */
private fun patchComponent(
    component: VEvent,
    row: EventRow,
    base: EventValues,
    times: EventTimes,
    spelling: Spelling,
    isMaster: Boolean,
    cancelled: List<IcalDate>,
    zones: IcalZoneIndex,
    deviceZone: ZoneId,
    now: Long,
    notes: MutableList<String>,
) {
    val values = row.values
    var sequenceChanged = false
    var touched = false

    if (values.dtStart != null && timesDiffer(values, base)) {
        writeTimes(component, values, base, spelling, isMaster, zones, deviceZone, now, notes)
        sequenceChanged = true
        touched = true
    }

    val exdate = exdateStorage(values.exdate, cancelled, values.allDay)
    if (values.rrule != base.rrule || values.rdate != base.rdate || exdate != base.exdate) {
        component.removeProperties(Property.RRULE, Property.RDATE, Property.EXDATE)
        for (line in columnLines(values.rrule)) {
            val rule = runCatching { RRule<LocalDateTime>(ParameterList(), line) }.getOrNull()
            if (rule == null) {
                notes += "a recurrence rule could not be written and was left out"
            } else {
                component.addProperties(listOf(rule))
            }
        }
        component.addProperties(dateListProperties(Property.RDATE, values.rdate, values.allDay, zones, deviceZone, now, notes))
        component.addProperties(dateListProperties(Property.EXDATE, exdate, values.allDay, zones, deviceZone, now, notes))
        sequenceChanged = true
        touched = true
    }

    if (values.title != base.title) {
        rewriteText(component, Property.SUMMARY, values.title) { Summary(it) }
        touched = true
    }
    if (values.description != base.description) {
        rewriteText(component, Property.DESCRIPTION, values.description) { Description(it) }
        touched = true
    }
    if (values.location != base.location) {
        rewriteText(component, Property.LOCATION, values.location) { Location(it) }
        touched = true
    }
    if (values.status != base.status) {
        val status = values.status
        if (status == null) component.removeProperties(Property.STATUS) else component.replaceProperty(Status(statusName(status)))
        sequenceChanged = true
        touched = true
    }
    // A missing TRANSP means OPAQUE, so a source without one stays without one: only a row that
    // really says something else than the source does rewrites the property.
    if (values.transparency != (base.transparency ?: Transparency.OPAQUE)) {
        component.replaceProperty(Transp(if (values.transparency == Transparency.TRANSPARENT) "TRANSPARENT" else "OPAQUE"))
        touched = true
    }

    val slots = alarmSlots(component, times, zones, deviceZone, now)
    if (row.reminders.reminderSet() != slots.filterNotNull().reminderSet()) {
        writeAlarms(component, slots, row.reminders, notes)
        touched = true
    }

    if (!touched) return
    // RFC 5545 §3.8.7.4: the revision counter moves for the properties another client would have to
    // re-read — the times, the recurrence and the status — and not for a renamed summary.
    if (sequenceChanged) {
        val sequence = component.getProperty<Property>(Property.SEQUENCE).map { it.value.toIntOrNull() ?: 0 }.orElse(0)
        component.replaceProperty(Sequence(sequence + 1))
    }
    component.replaceProperty(DtStamp(Instant.ofEpochMilli(now)))
    component.replaceProperty(LastModified(Instant.ofEpochMilli(now)))
}

private fun timesDiffer(values: EventValues, base: EventValues): Boolean =
    values.dtStart != base.dtStart ||
        values.dtEnd != base.dtEnd ||
        values.duration != base.duration ||
        values.allDay != base.allDay ||
        values.eventTimeZone != base.eventTimeZone ||
        values.eventEndTimeZone != base.eventEndTimeZone

/**
 * The rows' time columns as properties.
 *
 * All-day rows carry `DATE` values and an exclusive end; a recurring master carries the length as
 * `DURATION` (the provider drops `DTEND` from such a row, and an event that keeps both expands into
 * zero-length instances); everything else carries `DTEND`.
 */
private fun writeTimes(
    component: VEvent,
    values: EventValues,
    base: EventValues?,
    spelling: Spelling,
    isMaster: Boolean,
    zones: IcalZoneIndex,
    deviceZone: ZoneId,
    now: Long,
    notes: MutableList<String>,
) {
    val start = values.dtStart ?: return
    val startForm = when {
        values.allDay -> TimeForm.DATE
        // A source that was all-day and a row that is not leaves no form to keep, and neither does a
        // new event: both take the row's own zone.
        spelling.startForm == null || spelling.startForm == TimeForm.DATE -> TimeForm.ZONED
        values.eventTimeZone == base?.eventTimeZone -> spelling.startForm
        else -> TimeForm.ZONED
    }
    if (spelling.startForm == TimeForm.FLOATING && values.eventTimeZone != base?.eventTimeZone) {
        // The source's floating time was read in the device zone; if that zone has since changed,
        // the instant is what survives the patch.
        notes += "a floating start time was written as an instant in ${values.eventTimeZone ?: deviceZone.id}"
    }
    component.removeProperties(Property.DTSTART, Property.DTEND, Property.DURATION)
    component.replaceProperty(dateProperty(Property.DTSTART, start, startForm, values.eventTimeZone, zones, deviceZone, now))

    val declaredDuration = values.duration?.let { durationSeconds(it) }
    val end = values.dtEnd ?: declaredDuration?.let { start + it * 1000L }
    // A source with no end property at all describes an event that ends where it starts, and the
    // provider expresses that by leaving both columns empty; writing DTEND=DTSTART would invent one.
    if (spelling.endProperty == null && !isMaster && end != null && end == start) return

    val endProperty = when {
        isMaster && spelling.endProperty == Property.DURATION -> Property.DURATION
        spelling.endProperty == Property.DTEND -> Property.DTEND
        isMaster && values.duration != null -> Property.DURATION
        end == null -> null
        else -> Property.DTEND
    }
    when (endProperty) {
        Property.DURATION -> {
            val seconds = declaredDuration ?: end?.let { (it - start) / 1000L }
            if (seconds != null) component.replaceProperty(Duration(rfcDuration(seconds, values.allDay)))
        }

        Property.DTEND -> if (end != null) {
            val endForm = when {
                values.allDay -> TimeForm.DATE
                values.eventEndTimeZone != null -> TimeForm.ZONED
                spelling.endProperty == null -> startForm
                else -> spelling.endForm ?: startForm
            }
            component.replaceProperty(
                dateProperty(
                    Property.DTEND,
                    end,
                    endForm,
                    values.eventEndTimeZone ?: values.eventTimeZone,
                    zones,
                    deviceZone,
                    now,
                ),
            )
        }

        else -> Unit
    }
}

/** `PT<n>S`, or `P<n>D` for a whole number of days: never the AOSP `P<n>S` the column holds. */
private fun rfcDuration(seconds: Long, allDay: Boolean): String {
    val positive = seconds.coerceAtLeast(0L)
    return if (allDay && positive % SECONDS_PER_DAY == 0L) "P${positive / SECONDS_PER_DAY}D" else "PT${positive}S"
}

private const val SECONDS_PER_DAY = 24L * 60L * 60L

/** Sets a text property from [value], or removes it: an emptied column is how a property goes away. */
private inline fun rewriteText(component: VEvent, name: String, value: String?, build: (String) -> Property) {
    if (value.isNullOrBlank()) component.removeProperties(name) else component.replaceProperty(build(value))
}

private fun statusName(status: EventStatus): String = when (status) {
    EventStatus.TENTATIVE -> "TENTATIVE"
    EventStatus.CONFIRMED -> "CONFIRMED"
    EventStatus.CANCELLED -> "CANCELLED"
}

private fun columnLines(column: String?): List<String> =
    column.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * The reminders as a comparable multiset.
 *
 * Sorted by what the matching uses — the minutes and the method — and never by the row id: the two
 * sides are the source's alarms in component order and the provider's rows in insertion order, and
 * neither order means anything to the comparison.
 */
private fun List<ReminderRow>.reminderSet(): List<Pair<Int, AlarmMethod>> =
    map { it.minutes to it.method }.sortedWith(compareBy({ it.first }, { it.second.name }))

/**
 * The resource's date-list property for one storage column value.
 *
 * A column line is an optional `TZID;` and a comma-separated list, the inverse of
 * [recurrenceListStorage]. ical4j resolves a list's `TZID` while it writes it, so a line naming a
 * zone the platform does not have is written as instants instead — the alternative, dropping the
 * parameter, reinterprets every entry in whatever zone the reading client happens to be in.
 */
private fun dateListProperties(
    name: String,
    storage: String?,
    allDay: Boolean,
    zones: IcalZoneIndex,
    deviceZone: ZoneId,
    now: Long,
    notes: MutableList<String>,
): List<Property> {
    if (storage.isNullOrBlank()) return emptyList()
    val properties = ArrayList<Property>()
    for (line in columnLines(storage)) {
        val separator = line.indexOf(';')
        val tzid = if (separator < 0) null else line.substring(0, separator)
        val entries = (if (separator < 0) line else line.substring(separator + 1))
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val dates = ArrayList<LocalDate>()
        val utc = ArrayList<OffsetDateTime>()
        val local = ArrayList<LocalDateTime>()
        var zoned = false
        for (entry in entries) {
            val at: Long?
            when {
                entry.length == 8 -> {
                    at = epochMillis(IcalDate(entry, null), ZoneOffset.UTC)
                    if (at != null) dates += Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC).toLocalDate()
                }

                entry.endsWith("Z", ignoreCase = true) -> {
                    at = epochMillis(IcalDate(entry, null), ZoneOffset.UTC)
                    if (at != null) utc += Instant.ofEpochMilli(at).atOffset(ZoneOffset.UTC)
                }

                tzid != null && TimeZoneRegistry.getGlobalZoneId(tzid) == null -> {
                    // The zone is not one the platform can render; the instant is what survives.
                    notes += "a recurrence date in $tzid was written as an instant"
                    val zone = resolveZone(tzid, zones, deviceZone, now).zone
                    at = epochMillis(IcalDate(entry, null), zone)
                    if (at != null) utc += Instant.ofEpochMilli(at).atOffset(ZoneOffset.UTC)
                }

                else -> {
                    val zone = resolveZone(tzid, zones, deviceZone, now).zone
                    zoned = tzid != null
                    at = epochMillis(IcalDate(entry, null), zone)
                    if (at != null) local += Instant.ofEpochMilli(at).atZone(zone).toLocalDateTime()
                }
            }
        }
        if (dates.isNotEmpty()) properties += dateList(name, ParameterList(listOf(Value.DATE)), DateList(dates))
        if (utc.isNotEmpty()) properties += dateList(name, ParameterList(), DateList(utc))
        if (local.isNotEmpty()) {
            val parameters = if (zoned && tzid != null) ParameterList(listOf(TzId(tzid))) else ParameterList()
            properties += dateList(name, parameters, DateList(local))
        }
        if (dates.isEmpty() && utc.isEmpty() && local.isEmpty()) {
            notes += "a recurrence date could not be written and was left out"
        }
    }
    return properties
}

private fun <T : Temporal> dateList(name: String, parameters: ParameterList, dates: DateList<T>): Property =
    when (name) {
        Property.RDATE -> RDate(parameters, dates)
        else -> ExDate(parameters, dates)
    }

/**
 * One entry per alarm in component order: the reminder its trigger means, or null for an alarm that
 * yields none.
 *
 * `AUDIO` and `PROCEDURE` alarms, an unparseable trigger and an absolute trigger that cannot be
 * placed all land on null, and a null slot is an alarm the patch never touches — the only way an
 * alarm's shape survives an edit to something else.
 */
private fun alarmSlots(
    component: VEvent,
    times: EventTimes,
    zones: IcalZoneIndex,
    deviceZone: ZoneId,
    now: Long,
): List<ReminderRow?> = component.getAlarms().map { alarm ->
    val reminder = alarm.toParsedReminder() ?: return@map null
    val offset = reminderOffsetMinutes(reminder, times, zones, deviceZone, now) ?: return@map null
    ReminderRow(clampedReminderMinutes(offset), reminder.method)
}

/**
 * Replaces the alarms the rows no longer agree with.
 *
 * A matched alarm keeps every property it has — `REPEAT`, `DURATION`, `RELATED=END`, an absolute
 * trigger, `ATTENDEE` — which is what an untouched reminder deserves. Only an alarm whose reminder
 * row is gone is dropped, and an added row becomes a plain `DISPLAY`/`EMAIL` alarm: this is where an
 * alarm's shape can be lost, and a note says so.
 */
private fun writeAlarms(component: VEvent, slots: List<ReminderRow?>, reminders: List<ReminderRow>, notes: MutableList<String>) {
    val left = reminders.toMutableList()
    var alarms = component.getComponentList()
    for ((index, alarm) in component.getAlarms().withIndex()) {
        val slot = slots.getOrNull(index) ?: continue
        val match = left.indexOfFirst { it.minutes == slot.minutes && it.method == slot.method }
        if (match >= 0) left.removeAt(match) else alarms = alarms.remove(alarm)
    }
    for (reminder in left) {
        alarms = alarms.add(alarmOf(reminder, component.stringProperty(Property.SUMMARY)))
        notes += "a reminder was rebuilt as a plain alarm"
    }
    component.setComponentList(alarms)
}

/**
 * ical4j 4's property and component lists are immutable — `add` returns a new list — so a change is
 * a new list set back on the container. These spell that once instead of at every call site.
 */
private fun PropertyContainer.replaceProperty(property: Property) {
    setPropertyList(getPropertyList().replace(property))
}

private fun PropertyContainer.addProperties(properties: List<Property>) {
    if (properties.isEmpty()) return
    setPropertyList(getPropertyList().addAll(properties))
}

private fun PropertyContainer.removeProperties(vararg names: String) {
    setPropertyList(getPropertyList().removeAll(*names))
}

private fun Calendar.addComponent(component: CalendarComponent) {
    setComponentList(getComponentList().add(component))
}

private fun Calendar.dropComponent(component: CalendarComponent) {
    setComponentList(getComponentList().remove(component))
}

/** `TRIGGER:-PT<minutes>M`, the only spelling `Reminders.MINUTES` can produce. */
private fun alarmOf(reminder: ReminderRow, title: String?): VAlarm {
    val properties = ArrayList<Property>()
    properties += Action(if (reminder.method == AlarmMethod.EMAIL) "EMAIL" else "DISPLAY")
    properties += Trigger(ParameterList(), if (reminder.minutes > 0) "-PT${reminder.minutes}M" else "PT0M")
    if (!title.isNullOrBlank()) properties += Description(title)
    return VAlarm(PropertyList(properties))
}

/**
 * A new `VEVENT` for a locally created exception.
 *
 * Built from the master's properties minus the ones that describe the series: `RRULE`, `RDATE`,
 * `EXDATE` and `EXRULE` are the master's alone and a second copy makes every client disagree about
 * the series. `DTSTART`/`DTEND`/`DURATION` and the alarms belong to the instance rather than to the
 * copy, and the rest — every `X-`, the `UID` — is copied because that is what any client's override
 * carries. The row then overwrites what it holds: a provider exception row describes the whole
 * occurrence, not a delta from the master.
 */
private fun newOverride(
    master: VEvent,
    row: EventRow,
    masterForm: TimeForm?,
    masterZone: String?,
    zones: IcalZoneIndex,
    deviceZone: ZoneId,
    now: Long,
): VEvent {
    val copied = ArrayList<Property>()
    for (property in master.getProperties<Property>()) {
        if (property.name in SERIES_PROPERTIES) continue
        copied += property.copy()
    }
    val override = VEvent(PropertyList(copied))
    override.replaceProperty(instanceProperty(row, masterForm, masterZone, zones, deviceZone, now))
    writeTimes(override, row.values, null, Spelling(null, null, null), false, zones, deviceZone, now, ArrayList())
    rewriteText(override, Property.SUMMARY, row.values.title) { Summary(it) }
    rewriteText(override, Property.DESCRIPTION, row.values.description) { Description(it) }
    rewriteText(override, Property.LOCATION, row.values.location) { Location(it) }
    val status = row.values.status
    if (status == null) override.removeProperties(Property.STATUS) else override.replaceProperty(Status(statusName(status)))
    override.removeProperties(Property.TRANSP)
    if (row.values.transparency == Transparency.TRANSPARENT) override.replaceProperty(Transp("TRANSPARENT"))
    override.replaceProperty(Sequence(0))
    override.replaceProperty(DtStamp(Instant.ofEpochMilli(now)))
    override.replaceProperty(LastModified(Instant.ofEpochMilli(now)))
    writeAlarms(override, emptyList(), row.reminders, ArrayList())
    return override
}

private val SERIES_PROPERTIES = setOf(
    Property.RRULE,
    Property.RDATE,
    Property.EXDATE,
    Property.EXRULE,
    Property.DTSTART,
    Property.DTEND,
    Property.DURATION,
    Property.DTSTAMP,
    Property.LAST_MODIFIED,
)

/** `RECURRENCE-ID` in the master `DTSTART`'s value form: RFC 5545 requires the types to match. */
private fun instanceProperty(
    row: EventRow,
    masterForm: TimeForm?,
    masterZone: String?,
    zones: IcalZoneIndex,
    deviceZone: ZoneId,
    now: Long,
): Property {
    val form = when {
        row.originalAllDay -> TimeForm.DATE
        masterForm == null || masterForm == TimeForm.DATE -> TimeForm.UTC
        else -> masterForm
    }
    return dateProperty(
        Property.RECURRENCE_ID,
        row.originalInstanceTime ?: 0L,
        form,
        if (form == TimeForm.ZONED) masterZone else null,
        zones,
        deviceZone,
        now,
    )
}

/**
 * One occurrence as the master's own `DTSTART` spells it, for the `EXDATE` a cancellation becomes.
 *
 * [zone] is the master's start zone as [resolveZone] read it, and a `TZID` ical4j cannot render
 * falls back to an instant: the storage line then says `Z`, which is the same fallback
 * [dateListProperties] applies, so the wall clock is not shifted twice.
 */
private fun instanceOf(
    instant: Long,
    allDay: Boolean,
    masterForm: TimeForm?,
    masterZone: String?,
    zone: ZoneId,
): IcalDate = when {
    allDay || masterForm == TimeForm.DATE -> IcalDate(basicDate(instant), null)
    masterForm == TimeForm.ZONED && masterZone != null && TimeZoneRegistry.getGlobalZoneId(masterZone) != null ->
        IcalDate(basicDateTime(instant, zone), masterZone)

    masterForm == TimeForm.FLOATING -> IcalDate(basicDateTime(instant, zone), null)
    else -> IcalDate(basicDateTime(instant, ZoneOffset.UTC) + "Z", null)
}

private fun basicDate(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
    .toLocalDate().format(DATE_FORMAT)

private fun basicDateTime(millis: Long, zone: ZoneId): String = Instant.ofEpochMilli(millis).atZone(zone)
    .toLocalDateTime().format(DATE_TIME_FORMAT)

private val DATE_FORMAT = DateTimeFormatter.ofPattern("uuuuMMdd")
private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss")

/** The event's `EXDATE` column with the instances this upload cancels added to it. */
private fun exdateStorage(exdate: String?, cancelled: List<IcalDate>, allDay: Boolean): String? {
    if (cancelled.isEmpty()) return exdate
    val entries = ArrayList<IcalDate>()
    for (line in columnLines(exdate)) {
        val separator = line.indexOf(';')
        val tzid = if (separator < 0) null else line.substring(0, separator)
        val list = if (separator < 0) line else line.substring(separator + 1)
        for (entry in list.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            entries += IcalDate(entry, if (allDay) null else tzid)
        }
    }
    entries += cancelled
    return recurrenceListStorage(entries, allDay)
}

/** The date property for one instant: the value is built from the instant, never parsed from text. */
private fun dateProperty(
    name: String,
    millis: Long,
    form: TimeForm,
    zoneId: String?,
    zones: IcalZoneIndex,
    deviceZone: ZoneId,
    now: Long,
): Property {
    val zone = resolveZone(zoneId, zones, deviceZone, now).zone
    return when (form) {
        TimeForm.DATE -> dateNamed(name, ParameterList(listOf(Value.DATE)), dateAt(millis))
        TimeForm.UTC -> dateNamed(name, ParameterList(), offsetAt(millis))
        // A floating value is read in the device zone, so it is written back in it.
        TimeForm.FLOATING -> dateNamed(name, ParameterList(), localAt(millis, deviceZone))
        // A row whose zone is UTC is written in the UTC form, not as `TZID=UTC` with a `Z` value:
        // RFC 5545 §3.3.5 allows a TZID only on a local value, and the two together are a
        // contradiction a strict server is entitled to reject. Android stores "UTC" in
        // EVENT_TIMEZONE for every all-day row and for anything created without a zone, so this is
        // the common case rather than an edge one.
        TimeForm.ZONED ->
            if (isUtc(zone)) dateNamed(name, ParameterList(), offsetAt(millis))
            else dateNamed(name, ParameterList(listOf(TzId(zoneId ?: eventTimeZoneId(zone)))), localAt(millis, zone))
    }
}

/** Every spelling of UTC Android and iCalendar between them produce. */
private fun isUtc(zone: ZoneId): Boolean = zone.normalized() == ZoneOffset.UTC

private fun dateAt(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

private fun offsetAt(millis: Long): OffsetDateTime = Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC)

private fun localAt(millis: Long, zone: ZoneId): LocalDateTime =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime()

private fun dateNamed(name: String, parameters: ParameterList, value: Temporal): Property = when (value) {
    is LocalDate -> when (name) {
        Property.DTSTART -> DtStart(parameters, value)
        Property.DTEND -> DtEnd(parameters, value)
        else -> RecurrenceId(parameters, value)
    }

    is OffsetDateTime -> when (name) {
        Property.DTSTART -> DtStart(parameters, value)
        Property.DTEND -> DtEnd(parameters, value)
        else -> RecurrenceId(parameters, value)
    }

    is LocalDateTime -> when (name) {
        Property.DTSTART -> DtStart(parameters, value)
        Property.DTEND -> DtEnd(parameters, value)
        else -> RecurrenceId(parameters, value)
    }

    else -> error("no iCalendar property carries a $value")
}

/** A resource with no stored text: every property comes from the rows, so every one has to be there. */
private fun synthesize(rows: ResourceRows, now: Long, deviceZone: ZoneId): Serialized {
    val values = rows.master.values
    if (values.dtStart == null) return Serialized.Held("this event has no start time")
    val notes = ArrayList<String>()
    val zones = IcalZoneIndex.of(emptyList())

    val master = VEvent(PropertyList())
    master.replaceProperty(Uid(rows.uid))
    master.replaceProperty(Created(Instant.ofEpochMilli(now)))
    master.replaceProperty(DtStamp(Instant.ofEpochMilli(now)))
    master.replaceProperty(LastModified(Instant.ofEpochMilli(now)))
    master.replaceProperty(Sequence(0))
    rewriteText(master, Property.SUMMARY, values.title) { Summary(it) }
    rewriteText(master, Property.DESCRIPTION, values.description) { Description(it) }
    rewriteText(master, Property.LOCATION, values.location) { Location(it) }
    values.status?.let { master.replaceProperty(Status(statusName(it))) }
    if (values.transparency == Transparency.TRANSPARENT) master.replaceProperty(Transp("TRANSPARENT"))

    val masterForm = if (values.allDay) TimeForm.DATE else TimeForm.ZONED
    writeTimes(master, values, null, Spelling(null, null, null), isMaster = true, zones, deviceZone, now, notes)
    for (line in columnLines(values.rrule)) {
        val rule = runCatching { RRule<LocalDateTime>(ParameterList(), line) }.getOrNull()
        if (rule == null) notes += "a recurrence rule could not be written and was left out" else master.addProperties(listOf(rule))
    }
    val cancelled = rows.overrides.filter { it.deleted }.mapNotNull { row ->
        row.originalInstanceTime?.let {
            instanceOf(
                it,
                row.originalAllDay,
                masterForm,
                values.eventTimeZone,
                resolveZone(values.eventTimeZone, zones, deviceZone, now).zone,
            )
        }
    }
    master.addProperties(dateListProperties(Property.RDATE, values.rdate, values.allDay, zones, deviceZone, now, notes))
    master.addProperties(dateListProperties(Property.EXDATE, exdateStorage(values.exdate, cancelled, values.allDay), values.allDay, zones, deviceZone, now, notes))
    writeAlarms(master, emptyList(), rows.master.reminders, notes)

    val calendar = Calendar(
        PropertyList(listOf(Version(ParameterList(), "2.0"), ProdId(APP_PRODID))),
        ComponentList<CalendarComponent>(listOf(master)),
    )
    for (row in rows.overrides) {
        if (row.deleted || row.values.dtStart == null) continue
        calendar.addComponent(newOverride(master, row, masterForm, values.eventTimeZone, zones, deviceZone, now))
    }
    appendMissingZones(calendar)
    return Serialized.Written(render(calendar), notes)
}

/**
 * A `TZID` a patched property names that the resource does not define gets the definition from
 * ical4j's registry: RFC 5545 requires the definition to travel with the property that names it, and
 * a `DTSTART;TZID=x` a client cannot resolve is an event it cannot place in time. A definition the
 * source already carries is left exactly as it is, referenced or not.
 */
private fun appendMissingZones(calendar: Calendar) {
    val defined = calendar.getComponents<VTimeZone>(Component.VTIMEZONE)
        .mapNotNull { it.getProperty<Property>(Property.TZID).map { property -> property.value }.orElse(null) }
        .toMutableSet()
    for (component in calendar.getComponents<VEvent>(Component.VEVENT)) {
        for (property in component.getProperties<Property>()) {
            val tzid = property.getParameter<Parameter>(Parameter.TZID).map { it.value }.orElse(null) ?: continue
            if (!defined.add(tzid)) continue
            val zone = registry.getTimeZone(tzid) ?: continue
            calendar.addComponent(zone.getVTimeZone())
        }
    }
}

/** ical4j's own zone data, which the read path already consults for a `TZID` it cannot resolve. */
private val registry: TimeZoneRegistry by lazy { TimeZoneRegistryFactory.getInstance().createRegistry() }

/**
 * Line folding and property order are the renderer's, not the source's — the server is sent a
 * semantically identical resource, which is all `If-Match` needs. Unvalidated output, because a
 * source may name a zone this platform's registry does not have and ical4j would then refuse to
 * write bytes the server itself produced.
 */
private fun render(calendar: Calendar): String {
    val writer = StringWriter()
    CalendarOutputter(false).output(calendar, writer)
    return writer.toString()
}
