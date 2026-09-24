package app.davkeep.provider.calendar

import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.component.CalendarComponent
import net.fortuna.ical4j.model.Parameter
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.VAlarm
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.component.VTimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * What an upload sends for one resource: the server's own text with the rows' changes applied.
 *
 * Every case here is one the read path cannot recover from. A property the phone has no column for
 * is only in the source, so a patch that rebuilt the component instead of rewriting named
 * properties would delete it on the server; a recurrence the user did not touch is only spelled
 * once, in the source; and a create has no source at all, so everything it sends has to come from
 * the row. None of this is reachable from a device test without a server, and none of it needs one.
 */
class CalendarSerializeTest {

    @Test
    fun `a title change rewrites the title and nothing else`() {
        val rows = rowsOf(SOURCE)

        val written = written(rows.withTitle("Abendessen"))

        // Everything of the source survives except the three properties an edit is allowed to move.
        val before = flatten(SOURCE) - setOf(
            "VCALENDAR/PRODID=-//Nextcloud//NONSGML Calendar//EN",
            "VEVENT/SUMMARY=Lunch",
            "VEVENT/DTSTAMP=20250101T120000Z",
            "VEVENT/LAST-MODIFIED=20250101T120000Z",
        )
        val after = flatten(written)
        assertTrue("lost: ${before - after}", after.containsAll(before))
        assertTrue(after.contains("VEVENT/SUMMARY=Abendessen"))
        assertFalse("the master's old title is gone", after.contains("VEVENT/SUMMARY=Lunch"))
    }

    /** The properties Android cannot express are exactly the ones no column holds. */
    @Test
    fun `an unmapped property and every VTIMEZONE survive a title change`() {
        val written = written(rowsOf(SOURCE).withTitle("Abendessen"))

        assertTrue(flatten(written).contains("VEVENT/X-APPLE-TRAVEL-ADVISORY-BEHAVIOR=AUTOMATIC"))
        assertTrue(flatten(written).contains("VEVENT/CATEGORIES=Freunde,Arbeit"))
        val zones = parseCalendar(written).getComponents<VTimeZone>(Component.VTIMEZONE)
        assertEquals(listOf("Europe/Berlin"), zones.map { it.getProperty<Property>(Property.TZID).get().value })
        // The alarm's shape is not something the phone can restate, so it has to be the source's.
        val alarms = events(written).first().getAlarms()
        assertEquals(1, alarms.size)
        assertEquals("-PT10M", alarms.first().getProperty<Property>(Property.TRIGGER).get().value)
        assertEquals("2", alarms.first().getProperty<Property>(Property.REPEAT).get().value)
    }

    @Test
    fun `a recurring master keeps its rule and its excluded dates`() {
        val written = written(rowsOf(SOURCE).withTitle("Abendessen"))

        val master = events(written).first()
        assertEquals("FREQ=WEEKLY;BYDAY=MO", master.getProperty<Property>(Property.RRULE).get().value)
        assertEquals("20250616T100000", master.getProperty<Property>(Property.EXDATE).get().value)
        assertEquals(3, events(written).size)
    }

    /** A resource is one upload, so editing one occurrence must not disturb the others. */
    @Test
    fun `an override edited alone does not lose its siblings`() {
        val rows = rowsOf(SOURCE)
        val edited = rows.copy(
            overrides = rows.overrides.mapIndexed { index, row -> if (index == 1) row.withTitle("Kaffee") else row },
        )

        val written = written(edited)

        val events = events(written)
        assertEquals(3, events.size)
        val summaries = events.map { it.getProperty<Property>(Property.SUMMARY).map { property -> property.value }.orElse(null) }
        assertEquals(listOf("Lunch", "Lunch verschoben", "Kaffee"), summaries)
        // The sibling keeps the parameter the read path has no column for.
        val ranged = events.first { it.getProperty<Property>(Property.RECURRENCE_ID).isPresent }
        assertEquals("THISANDFUTURE", ranged.getProperty<Property>("RANGE").get().value)
    }

    /**
     * An override row the provider deleted outright leaves no tombstone, so the component it
     * claimed is left unclaimed here. It describes an occurrence the phone no longer overrides —
     * the master's own is back — so it goes, and no `EXDATE` is invented for it.
     */
    @Test
    fun `an override no row claims is dropped without an EXDATE`() {
        val rows = rowsOf(SOURCE)
        val vanished = rows.copy(overrides = rows.overrides.drop(1))

        val written = written(vanished)

        val events = events(written)
        assertEquals(2, events.size)
        assertEquals("20250616T100000", events.first().getProperty<Property>(Property.EXDATE).get().value)
    }

    /** The provider drops `DTEND` from a recurring row, so the source's spelling is what is written. */
    @Test
    fun `a time change on a DURATION master keeps DURATION and bumps the sequence`() {
        val rows = rowsOf(SOURCE)
        val moved = rows.copy(
            master = rows.master.copy(
                values = rows.master.values.copy(dtStart = rows.master.values.dtStart!! + 3_600_000L),
            ),
        )

        val master = events(written(moved)).first()

        assertNull("DTEND must not appear on a recurring row", master.getProperty<Property>(Property.DTEND).orElse(null))
        assertEquals("PT1H", master.getProperty<Property>(Property.DURATION).get().value)
        assertEquals("20250602T110000", master.getProperty<Property>(Property.DTSTART).get().value)
        assertEquals("4", master.getProperty<Property>(Property.SEQUENCE).get().value)
    }

    /** A source that names its zone keeps naming it, whatever the row holds. */
    @Test
    fun `the source's value form is kept when the row's zone did not change`() {
        val zoned = events(written(rowsOf(SOURCE).withTitle("Abendessen"))).first()
        assertEquals("Europe/Berlin", zoned.getProperty<Property>(Property.DTSTART).get().getParameter<Parameter>(Parameter.TZID).get().value)
        assertEquals("20250602T100000", zoned.getProperty<Property>(Property.DTSTART).get().value)

        val utc = events(written(rowsOf(UTC_SOURCE).withTitle("Abendessen"))).first()
        assertTrue(utc.getProperty<Property>(Property.DTSTART).get().value.endsWith("Z"))
    }

    /** An instance the phone cancelled is an `EXDATE` on the master, not a component of its own. */
    @Test
    fun `a cancelled instance becomes an EXDATE and its component goes`() {
        val rows = rowsOf(SOURCE)
        val cancelled = rows.copy(
            overrides = rows.overrides.mapIndexed { index, row -> if (index == 0) row.copy(deleted = true) else row },
        )

        val written = written(cancelled)

        val events = events(written)
        assertEquals(2, events.size)
        // The order inside a list property is not part of its meaning: both instances are excluded.
        val exdate = events.first().getProperty<Property>(Property.EXDATE).get().value
        assertEquals(listOf("20250609T100000", "20250616T100000"), exdate.split(',').sorted())
    }

    /** A locally created occurrence has no source component to patch, so one is built from the row. */
    @Test
    fun `a local exception is created from the master and carries a RECURRENCE-ID`() {
        val rows = rowsOf(SOURCE)
        val instance = rows.overrides[1].originalInstanceTime!! + 7L * 24L * 60L * 60L * 1000L
        val added = rows.copy(
            overrides = rows.overrides + rows.master.asOverride(id = 9, instance = instance, title = "Spaet"),
        )

        val written = written(added)

        val created = events(written).single { candidate ->
            val recurrenceId = candidate.toParsedEvent().recurrenceId
            recurrenceId != null && epochMillis(recurrenceId, ZONE) == instance
        }
        assertEquals("Spaet", created.getProperty<Property>(Property.SUMMARY).get().value)
        // RFC 5545 requires the identifier's type to match the master's `DTSTART`, so it is a zoned
        // date-time in the master's own zone.
        assertEquals(
            "Europe/Berlin",
            created.getProperty<Property>(Property.RECURRENCE_ID).get().getParameter<Parameter>(Parameter.TZID).get().value,
        )
        // An override that is part of a series must not carry the series' rule.
        assertNull(created.getProperty<Property>(Property.RRULE).orElse(null))
    }

    /** An event the phone created: the rows are the whole resource, so everything has to be there. */
    @Test
    fun `a create with no stored source produces an event with the minted UID`() {
        val start = Instant.parse("2026-10-03T07:00:00Z").toEpochMilli()
        val rows = ResourceRows(
            key = null,
            uid = "4d6b0f9e-6d1f-4d5b-9a5e-2a2b8b3c1d20",
            source = null,
            oversize = false,
            master = EventRow(
                id = 1,
                originalInstanceTime = null,
                originalAllDay = false,
                deleted = false,
                values = EventValues(
                    dtStart = start,
                    dtEnd = start + 3_600_000L,
                    duration = null,
                    allDay = false,
                    eventTimeZone = "Europe/Berlin",
                    eventEndTimeZone = null,
                    title = "Kaffee",
                    description = "mit Milch",
                    location = "Küche",
                    rrule = null,
                    rdate = null,
                    exdate = null,
                    status = null,
                    transparency = null,
                ),
                reminders = listOf(ReminderRow(10, AlarmMethod.ALERT)),
            ),
            overrides = emptyList(),
        )

        val written = written(rows)

        val master = events(written).single()
        assertEquals("4d6b0f9e-6d1f-4d5b-9a5e-2a2b8b3c1d20", master.getProperty<Property>(Property.UID).get().value)
        assertEquals("Kaffee", master.getProperty<Property>(Property.SUMMARY).get().value)
        assertEquals("20261003T090000", master.getProperty<Property>(Property.DTSTART).get().value)
        assertNotNull(master.getProperty<Property>(Property.DTEND).orElse(null))
        assertEquals(1, master.getAlarms().size)
        assertEquals(1, parseCalendar(written).getComponents<VEvent>(Component.VEVENT).size)
    }

    /** Nothing of the phone's is sent for a resource whose rows do not represent it. */
    @Test
    fun `a resource with no stored copy, an oversized one and a meeting are all held`() {
        val rows = rowsOf(SOURCE)
        assertTrue(serializeResource(rows.copy(source = null), NOW, ZONE) is Serialized.Held)
        assertTrue(serializeResource(rows.copy(oversize = true), NOW, ZONE) is Serialized.Held)
        assertTrue(serializeResource(rowsOf(MEETING), NOW, ZONE) is Serialized.Held)
    }

    // ---------------------------------------------------------------- fixtures

    /** The rows the read path would write for [source]: the state an untouched upload sees. */
    private fun rowsOf(source: String, key: String? = NAME, uid: String = UID): ResourceRows {
        val components = resourceComponents(parseCalendar(source))
        val master = components.master ?: error("$source has no master")
        val masterEvent = master.toParsedEvent()
        var nextId = 2L
        return ResourceRows(
            key = key,
            uid = uid,
            source = source,
            oversize = false,
            master = rowOf(1, master, masterEvent, components.zones, deleted = false),
            overrides = components.overrides.map { override ->
                rowOf(nextId++, override, override.toParsedEvent(), components.zones, deleted = false)
            },
        )
    }

    private fun rowOf(id: Long, component: VEvent, event: ParsedEvent, zones: IcalZoneIndex, deleted: Boolean): EventRow {
        val times = timesOf(event, zones, ZONE, NOW).times ?: error("$component has no usable start")
        // The reminders the read path would have written for this component's alarms, which is what
        // makes an untouched alarm survive the patch: an empty list would mean "the user removed it".
        val reminders = component.getAlarms().mapNotNull { alarm ->
            val parsed = alarm.toParsedReminder() ?: return@mapNotNull null
            val offset = reminderOffsetMinutes(parsed, times, zones, ZONE, NOW) ?: return@mapNotNull null
            ReminderRow(clampedReminderMinutes(offset), parsed.method)
        }
        return EventRow(
            id = id,
            originalInstanceTime = times.originalInstanceTime,
            originalAllDay = times.originalAllDay ?: false,
            deleted = deleted,
            values = eventValuesOf(event, times),
            reminders = reminders,
        )
    }

    private fun written(rows: ResourceRows): String {
        val serialized = serializeResource(rows, NOW, ZONE)
        return (serialized as? Serialized.Written)?.text ?: error("held: $serialized")
    }

    private fun ResourceRows.withTitle(title: String): ResourceRows =
        copy(master = master.copy(values = master.values.copy(title = title)))

    private fun EventRow.withTitle(title: String): EventRow = copy(values = values.copy(title = title))

    /** The master's own row, re-shaped into an occurrence the resource has no component for. */
    private fun EventRow.asOverride(id: Long, instance: Long, title: String): EventRow = copy(
        id = id,
        originalInstanceTime = instance,
        originalAllDay = false,
        values = values.copy(title = title),
    )

    private fun events(text: String): List<VEvent> = parseCalendar(text).getComponents<VEvent>(Component.VEVENT)

    /** Every property of every component as one comparable line, parameters included. */
    private fun flatten(text: String): Set<String> {
        val calendar: Calendar = parseCalendar(text)
        val lines = HashSet<String>()
        for (property in calendar.getProperties<Property>()) {
            lines += "VCALENDAR/${property.name}=${property.value}${property.parameterList}"
        }
        for (component in calendar.getComponents<CalendarComponent>()) {
            for (property in component.getProperties<Property>()) {
                lines += "${component.name}/${property.name}=${property.value}${property.parameterList}"
            }
            for (alarm in (component as? VEvent)?.getAlarms().orEmpty()) {
                for (property in alarm.getProperties<Property>()) {
                    lines += "${component.name}/VALARM/${property.name}=${property.value}${property.parameterList}"
                }
            }
        }
        return lines
    }

    private companion object {
        const val NAME = "/dav/abc-123.ics"
        const val UID = "abc-123@example.org"
        val ZONE: ZoneId = ZoneId.of("Europe/Berlin")
        val NOW: Long = Instant.parse("2026-09-22T10:00:00Z").toEpochMilli()

        /** A recurring master with two overrides, a vendor `X-` property and a shaped alarm. */
        val SOURCE = listOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Nextcloud//NONSGML Calendar//EN",
            "BEGIN:VTIMEZONE",
            "TZID:Europe/Berlin",
            "BEGIN:STANDARD",
            "DTSTART:19701025T030000",
            "TZOFFSETFROM:+0200",
            "TZOFFSETTO:+0100",
            "TZNAME:CET",
            "END:STANDARD",
            "END:VTIMEZONE",
            "BEGIN:VEVENT",
            "UID:$UID",
            "DTSTAMP:20250101T120000Z",
            "LAST-MODIFIED:20250101T120000Z",
            "SEQUENCE:3",
            "DTSTART;TZID=Europe/Berlin:20250602T100000",
            "DURATION:PT1H",
            "SUMMARY:Lunch",
            "X-APPLE-TRAVEL-ADVISORY-BEHAVIOR:AUTOMATIC",
            "CATEGORIES:Freunde,Arbeit",
            "EXDATE;TZID=Europe/Berlin:20250616T100000",
            "RRULE:FREQ=WEEKLY;BYDAY=MO",
            "BEGIN:VALARM",
            "ACTION:DISPLAY",
            "TRIGGER;RELATED=END:-PT10M",
            "DESCRIPTION:Erinnerung",
            "REPEAT:2",
            "DURATION:PT5M",
            "END:VALARM",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "UID:$UID",
            "DTSTAMP:20250101T120000Z",
            "RECURRENCE-ID;TZID=Europe/Berlin:20250609T100000",
            "RANGE:THISANDFUTURE",
            "DTSTART;TZID=Europe/Berlin:20250609T113000",
            "DTEND;TZID=Europe/Berlin:20250609T123000",
            "SUMMARY:Lunch verschoben",
            "END:VEVENT",
            "BEGIN:VEVENT",
            "UID:$UID",
            "DTSTAMP:20250101T120000Z",
            "RECURRENCE-ID;TZID=Europe/Berlin:20250623T100000",
            "DTSTART;TZID=Europe/Berlin:20250623T113000",
            "DTEND;TZID=Europe/Berlin:20250623T123000",
            "SUMMARY:Lunch spaet",
            "END:VEVENT",
            "END:VCALENDAR",
        ).joinToString("\r\n", postfix = "\r\n")

        /** The same event with a `Z` start, to prove the form is the source's and not the row's. */
        val UTC_SOURCE = listOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Nextcloud//NONSGML Calendar//EN",
            "BEGIN:VEVENT",
            "UID:$UID",
            "DTSTAMP:20250101T120000Z",
            "DTSTART:20250602T080000Z",
            "DTEND:20250602T090000Z",
            "SUMMARY:Lunch",
            "END:VEVENT",
            "END:VCALENDAR",
        ).joinToString("\r\n", postfix = "\r\n")

        /** A meeting: the app cannot tell whether the user is the organiser, so it sends nothing. */
        val MEETING = listOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//Nextcloud//NONSGML Calendar//EN",
            "BEGIN:VEVENT",
            "UID:$UID",
            "DTSTAMP:20250101T120000Z",
            "DTSTART:20250602T080000Z",
            "DTEND:20250602T090000Z",
            "SUMMARY:Jour fixe",
            "ORGANIZER;CN=Ada:mailto:ada@example.org",
            "END:VEVENT",
            "END:VCALENDAR",
        ).joinToString("\r\n", postfix = "\r\n")
    }
}
