package xyz.satr.davprovider.provider.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The rules `CalendarProvider2.scrubEventData` enforces by deleting values rather than by failing:
 * a recurring master that also carries DTEND loses it and expands into zero-length instances, and
 * a row with neither DTEND nor DURATION is zero-length too.
 */
class EventTimeSyntaxTest {

    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")

    @Test
    fun `recurring master carries DURATION and no DTEND`() {
        val times = eventTimes(
            shape = EventShape.RECURRING_MASTER,
            start = IcalDate("20260310T100000", "Europe/Berlin"),
            end = IcalDate("20260310T113000", "Europe/Berlin"),
            recurrenceId = null,
            explicitDuration = null,
            startZone = berlin,
            endZone = berlin,
            recurrenceIdZone = null,
        )
        assertNotNull(times)
        assertNull("a recurring row must never carry DTEND", times!!.dtEnd)
        assertEquals("P5400S", times.duration)
        assertEquals(Instant.parse("2026-03-10T09:00:00Z").toEpochMilli(), times.dtStart)
    }

    @Test
    fun `recurring master keeps the DURATION the server sent`() {
        val times = eventTimes(
            shape = EventShape.RECURRING_MASTER,
            start = IcalDate("20260310T100000", null),
            end = IcalDate("20260310T113000", null),
            recurrenceId = null,
            explicitDuration = "PT2H30M",
            startZone = berlin,
            endZone = null,
            recurrenceIdZone = null,
        )
        assertNull(times!!.dtEnd)
        assertEquals("PT2H30M", times.duration)
    }

    @Test
    fun `single event carries DTEND and no DURATION`() {
        val times = eventTimes(
            shape = EventShape.SINGLE,
            start = IcalDate("20260310T100000", "Europe/Berlin"),
            end = IcalDate("20260310T113000", "Europe/Berlin"),
            recurrenceId = null,
            // A stray DURATION on a non-recurring component must not reach the row either.
            explicitDuration = "PT3H",
            startZone = berlin,
            endZone = berlin,
            recurrenceIdZone = null,
        )
        assertNull("a non-recurring row must never carry DURATION", times!!.duration)
        assertEquals(Instant.parse("2026-03-10T10:30:00Z").toEpochMilli(), times.dtEnd)
    }

    @Test
    fun `override carries DTEND and points at the occurrence it replaces`() {
        val times = eventTimes(
            shape = EventShape.OVERRIDE,
            start = IcalDate("20260317T140000", "Europe/Berlin"),
            end = IcalDate("20260317T150000", "Europe/Berlin"),
            recurrenceId = IcalDate("20260317T100000", "Europe/Berlin"),
            explicitDuration = "PT1H",
            startZone = berlin,
            endZone = berlin,
            recurrenceIdZone = berlin,
        )
        assertNotNull(times)
        assertNull("an override must never carry DURATION", times!!.duration)
        assertEquals(Instant.parse("2026-03-17T14:00:00Z").toEpochMilli(), times.dtEnd)
        assertEquals(Instant.parse("2026-03-17T09:00:00Z").toEpochMilli(), times.originalInstanceTime)
        assertEquals(false, times.originalAllDay)
    }

    @Test
    fun `all-day rows are UTC midnight and count their duration in days`() {
        val times = eventTimes(
            shape = EventShape.RECURRING_MASTER,
            start = IcalDate("20260401", null),
            end = IcalDate("20260403", null),
            recurrenceId = null,
            explicitDuration = null,
            startZone = berlin,
            endZone = null,
            recurrenceIdZone = null,
        )
        assertTrue(times!!.allDay)
        assertEquals("UTC", times.eventTimeZone)
        assertNull(times.dtEnd)
        assertEquals("P2D", times.duration)
        assertEquals(Instant.parse("2026-04-01T00:00:00Z").toEpochMilli(), times.dtStart)

        val override = eventTimes(
            shape = EventShape.OVERRIDE,
            start = IcalDate("20260405", null),
            end = IcalDate("20260407", null),
            recurrenceId = IcalDate("20260405", null),
            explicitDuration = null,
            startZone = berlin,
            endZone = null,
            recurrenceIdZone = berlin,
        )
        assertNull(override!!.duration)
        assertEquals(Instant.parse("2026-04-07T00:00:00Z").toEpochMilli(), override.dtEnd)
        assertEquals(true, override.originalAllDay)
        assertEquals(Instant.parse("2026-04-05T00:00:00Z").toEpochMilli(), override.originalInstanceTime)
    }

    @Test
    fun `date lists use the provider syntax, not iCalendar property text`() {
        val timed = listOf(IcalDate("20260412T090000", "Europe/Berlin"), IcalDate("20260413T090000", "Europe/Berlin"))
        assertEquals("Europe/Berlin;20260412T090000,20260413T090000", recurrenceListStorage(timed, allDay = false))

        val allDay = listOf(IcalDate("20260410", "Europe/Berlin"), IcalDate("20260411", "Europe/Berlin"))
        assertEquals("20260410,20260411", recurrenceListStorage(allDay, allDay = true))
        assertNull(recurrenceListStorage(emptyList(), allDay = false))
    }

    @Test
    fun `an unusable TZID is resolved deliberately rather than left to the provider`() {
        val fallback = ZoneOffset.UTC
        val zones = IcalZoneIndex.of(emptyList())

        val known = resolveZone("Europe/Berlin", zones, fallback, 0L)
        assertEquals("Europe/Berlin", eventTimeZoneId(known.zone))

        val unknown = resolveZone("Not/AZone", zones, fallback, 0L)
        assertEquals("UTC", eventTimeZoneId(unknown.zone))
        assertNotNull("a fallback must say why it was chosen", unknown.note)
    }

    @Test
    fun `a parsed resource keeps the bare RRULE and decomposes its overrides`() {
        val resource = parseResource(MASTER_WITH_OVERRIDE)

        val master = resource.master
        assertNotNull(master)
        assertEquals(EventShape.RECURRING_MASTER, master!!.shape)
        assertEquals(listOf("FREQ=WEEKLY;BYDAY=TU"), master.rrules)
        assertEquals("Europe/Berlin;20260412T090000", recurrenceListStorage(master.rdates, allDay = false))
        assertEquals("20260402", recurrenceListStorage(master.exdates, allDay = false))
        assertEquals(1, master.reminders.size)
        assertEquals(-900L, master.reminders.first().triggerSeconds)
        assertEquals(1, master.attendees.size)

        assertEquals(1, resource.overrides.size)
        val override = resource.overrides.first()
        assertEquals(EventShape.OVERRIDE, override.shape)
        assertEquals("20260317T100000", override.recurrenceId?.text)
    }

    @Test
    fun `the UID that owns the resource is the one with a master`() {
        val resource = parseResource(TWO_UIDS)

        assertEquals("second", resource.master?.uid)
        assertEquals(EventShape.RECURRING_MASTER, resource.master?.shape)
        assertEquals(1, resource.overrides.size)
        assertEquals("second", resource.overrides.first().uid)
        assertEquals(listOf("first"), resource.droppedUids)
    }

    private companion object {
        val MASTER_WITH_OVERRIDE = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Example//EN
            BEGIN:VEVENT
            UID:example-1
            DTSTAMP:20260101T000000Z
            DTSTART;TZID=Europe/Berlin:20260310T100000
            DTEND;TZID=Europe/Berlin:20260310T113000
            RRULE:FREQ=WEEKLY;BYDAY=TU
            RDATE;TZID=Europe/Berlin:20260412T090000
            EXDATE;VALUE=DATE:20260402
            SUMMARY:Weekly sync
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            END:VALARM
            ATTENDEE;CN=Alice;PARTSTAT=ACCEPTED:mailto:alice@example.invalid
            END:VEVENT
            BEGIN:VEVENT
            UID:example-1
            DTSTAMP:20260101T000000Z
            RECURRENCE-ID;TZID=Europe/Berlin:20260317T100000
            DTSTART;TZID=Europe/Berlin:20260317T140000
            DTEND;TZID=Europe/Berlin:20260317T150000
            SUMMARY:Moved
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")

        val TWO_UIDS = """
            BEGIN:VCALENDAR
            VERSION:2.0
            BEGIN:VEVENT
            UID:first
            DTSTAMP:20260101T000000Z
            RECURRENCE-ID:20260317T100000Z
            DTSTART:20260317T140000Z
            DTEND:20260317T150000Z
            END:VEVENT
            BEGIN:VEVENT
            UID:second
            DTSTAMP:20260101T000000Z
            DTSTART:20260401T100000Z
            DTEND:20260401T110000Z
            RRULE:FREQ=DAILY
            END:VEVENT
            BEGIN:VEVENT
            UID:second
            DTSTAMP:20260101T000000Z
            RECURRENCE-ID:20260402T100000Z
            DTSTART:20260402T120000Z
            DTEND:20260402T130000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent().replace("\n", "\r\n")
    }

    @Test
    fun `a single event stated as DURATION is not zero-length`() {
        // RFC 5545 lets any VEVENT state its length either way. Read as DTEND-only, a legal
        // "DTSTART + DURATION:PT1H" became a zero-length row on the phone, and an edit to its time
        // then wrote that zero length back to the server.
        val times = eventTimes(
            shape = EventShape.SINGLE,
            start = IcalDate("20260310T100000", "Europe/Berlin"),
            end = null,
            recurrenceId = null,
            explicitDuration = "PT1H",
            startZone = berlin,
            endZone = null,
            recurrenceIdZone = null,
        )
        assertNotNull(times)
        assertNull("a single row must never carry DURATION", times!!.duration)
        assertEquals(Instant.parse("2026-03-10T10:00:00Z").toEpochMilli(), times.dtEnd)
    }

    @Test
    fun `an override stated as DURATION keeps its length`() {
        val times = eventTimes(
            shape = EventShape.OVERRIDE,
            start = IcalDate("20260317T110000", "Europe/Berlin"),
            end = null,
            recurrenceId = IcalDate("20260317T100000", "Europe/Berlin"),
            explicitDuration = "PT30M",
            startZone = berlin,
            endZone = null,
            recurrenceIdZone = berlin,
        )
        assertNotNull(times)
        assertNull(times!!.duration)
        assertEquals(Instant.parse("2026-03-17T10:30:00Z").toEpochMilli(), times.dtEnd)
    }

    @Test
    fun `DTEND wins when a component states both`() {
        val times = eventTimes(
            shape = EventShape.SINGLE,
            start = IcalDate("20260310T100000", "Europe/Berlin"),
            end = IcalDate("20260310T101500", "Europe/Berlin"),
            recurrenceId = null,
            explicitDuration = "PT5H",
            startZone = berlin,
            endZone = berlin,
            recurrenceIdZone = null,
        )
        assertEquals(Instant.parse("2026-03-10T09:15:00Z").toEpochMilli(), times!!.dtEnd)
    }
}
