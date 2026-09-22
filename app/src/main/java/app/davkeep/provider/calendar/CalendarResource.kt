package app.davkeep.provider.calendar

import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.Parameter
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.TimeZoneRegistry
import net.fortuna.ical4j.model.component.VAlarm
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.component.VTimeZone
import net.fortuna.ical4j.util.CompatibilityHints
import java.io.StringReader
import java.time.DateTimeException
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Parses one DAV resource into the rows it will become.
 *
 * One `.ics` may hold a master `VEVENT` plus `RECURRENCE-ID` overrides of the same `UID`. Every
 * row of the resource is stored under the same `_SYNC_ID` — the href the engine passes in, verbatim
 * — and the provider links an override to its master by that value, so the master is written first.
 */

/** `Reminders.METHOD` values that an iCalendar `ACTION` can produce. */
internal enum class AlarmMethod { ALERT, EMAIL }

/** `Events.STATUS`. */
internal enum class EventStatus { TENTATIVE, CONFIRMED, CANCELLED }

/** `Events.AVAILABILITY`, driven by `TRANSP`. */
internal enum class Transparency { OPAQUE, TRANSPARENT }

internal data class ParsedReminder(
    /** Relative trigger in seconds (negative = before); null when [triggerAt] is set. */
    val triggerSeconds: Long?,
    /** Absolute `TRIGGER;VALUE=DATE-TIME`; null when [triggerSeconds] is set. */
    val triggerAt: IcalDate?,
    val relatedToEnd: Boolean,
    val method: AlarmMethod,
)

internal data class ParsedAttendee(
    val email: String,
    val name: String?,
    val role: String?,
    val cutype: String?,
    val partstat: String?,
)

internal data class ParsedEvent(
    val uid: String?,
    val shape: EventShape,
    val recurrenceId: IcalDate?,
    val start: IcalDate?,
    val end: IcalDate?,
    val duration: String?,
    val rrules: List<String>,
    val rdates: List<IcalDate>,
    val exdates: List<IcalDate>,
    /** `RDATE`/`EXDATE` entries the `Events` column cannot express (`VALUE=PERIOD`). */
    val unrepresentableDates: Int,
    val summary: String?,
    val description: String?,
    val location: String?,
    val status: EventStatus?,
    val transparency: Transparency?,
    val reminders: List<ParsedReminder>,
    val attendees: List<ParsedAttendee>,
)

internal data class ParsedResource(
    val master: ParsedEvent?,
    val overrides: List<ParsedEvent>,
    val zones: IcalZoneIndex,
    /** `UID`s other than the primary one, which a single href cannot address. */
    val droppedUids: List<String>,
    /** Components of the primary `UID` that had nowhere to go (a second master). */
    val droppedComponents: Int,
)

/**
 * VTIMEZONE definitions of one resource, indexed by `TZID`.
 *
 * Used only as a last resort: a server may name a zone the platform's tzdata does not have, and
 * the definition it ships is then the only description of what that `TZID` means.
 */
internal class IcalZoneIndex private constructor(
    private val zones: Map<String, net.fortuna.ical4j.model.TimeZone>,
) {
    fun offsetZone(tzid: String, atMillis: Long): ZoneOffset? {
        val zone = zones[tzid] ?: return null
        val offset = runCatching { zone.getOffset(atMillis) }.getOrNull() ?: return null
        return runCatching { ZoneOffset.ofTotalSeconds(offset) }.getOrNull()
    }

    companion object {
        fun of(components: List<VTimeZone>): IcalZoneIndex {
            val zones = LinkedHashMap<String, net.fortuna.ical4j.model.TimeZone>()
            for (component in components) {
                val tzid = component.getProperty<Property>(Property.TZID).map { it.value }.orElse(null) ?: continue
                val zone = runCatching { net.fortuna.ical4j.model.TimeZone(component) }.getOrNull() ?: continue
                zones[tzid] = zone
            }
            return IcalZoneIndex(zones)
        }
    }
}

/** A resolved zone plus why it was chosen, so the caller can log a deliberate fallback. */
internal data class ZoneChoice(val zone: ZoneId, val note: String?)

/**
 * Resolves a `TZID` to a zone the platform knows.
 *
 * An id the platform's tzdata does not recognise silently becomes GMT in the `EVENT_TIMEZONE`
 * column, so an unresolvable `TZID` must be decided here and never left to the provider. The last
 * fallback is the device zone rather than GMT: for an unrecognised id the calendar is describing
 * local wall-clock time, and the device zone is the closest honest reading of it.
 */
internal fun resolveZone(tzid: String?, zones: IcalZoneIndex, fallback: ZoneId, atMillis: Long): ZoneChoice {
    if (tzid.isNullOrBlank()) return ZoneChoice(fallback, "no TZID; read as the device zone")

    systemZone(tzid)?.let { return ZoneChoice(it, null) }

    normalizeTzid(tzid)?.let { normalized ->
        systemZone(normalized)?.let { return ZoneChoice(it, "TZID $tzid resolved as $normalized") }
    }

    val aliased = runCatching { TimeZoneRegistry.getGlobalZoneId(tzid) }.getOrNull()
    if (aliased != null) return ZoneChoice(aliased, "TZID $tzid resolved from the iCalendar alias table")

    zones.offsetZone(tzid, atMillis)?.let {
        return ZoneChoice(it, "TZID $tzid is not a platform zone; using the offset its VTIMEZONE defines")
    }

    return ZoneChoice(fallback, "unrecognised TZID $tzid; falling back to ${eventTimeZoneId(fallback)}")
}

/** A zone the platform resolves and `java.util.TimeZone` can read back from the column. */
private fun systemZone(tzid: String): ZoneId? {
    val zone = try {
        ZoneId.of(tzid)
    } catch (_: DateTimeException) {
        return null
    }
    return when {
        zone is ZoneOffset -> zone
        zone.id.startsWith("GMT") || zone.id.startsWith("UTC") -> zone
        ZoneId.getAvailableZoneIds().contains(zone.id) -> zone
        else -> null
    }
}

/** Strips the vendor prefixes servers put in front of an otherwise normal zone id. */
private fun normalizeTzid(tzid: String): String? {
    var id = tzid.trim().removePrefix("/")
    for (prefix in VENDOR_PREFIXES) {
        if (id.startsWith(prefix)) id = id.substring(prefix.length)
    }
    return id.takeIf { it.isNotEmpty() && it != tzid }
}

private val VENDOR_PREFIXES = listOf(
    "freeassociation.sourceforge.net/",
    "mozilla.org/20050126_1/",
    "citadel.org/",
)

internal fun parseResource(ics: String): ParsedResource = parseResource(parseCalendar(ics))

internal fun parseCalendar(ics: String): Calendar {
    enableRelaxedHints()
    return CalendarBuilder().build(StringReader(ics))
}

/**
 * The components of one resource, beside the zones they name.
 *
 * The split is shared with the write path, which patches the component a row came from: it must
 * choose the master by the same rule the read path used, or a patch would address a component the
 * row does not describe.
 */
internal class ResourceComponents(
    /** The component a master row continues: the first without `RECURRENCE-ID`, else the first. */
    val master: VEvent?,
    /** The components carrying a `RECURRENCE-ID`, in resource order; empty without a master. */
    val overrides: List<VEvent>,
    val zones: IcalZoneIndex,
    /** `UID`s other than the primary one, which a single href cannot address. */
    val droppedUids: List<String>,
    /** Components of the primary `UID` that a single href cannot address. */
    val droppedComponents: Int,
)

internal fun resourceComponents(calendar: Calendar): ResourceComponents {
    val zones = IcalZoneIndex.of(calendar.getComponents<VTimeZone>(Component.VTIMEZONE))
    val byUid = calendar.getComponents<VEvent>(Component.VEVENT).groupBy { event ->
        event.getUid().map { it.value }.orElse(null)
    }

    // The resource is addressed by one href, so only one UID can own its rows: the group that has
    // a master, or the first one when every component is an exception.
    val primary = byUid.entries.firstOrNull { (_, events) ->
        events.any { it.dateProperty(Property.RECURRENCE_ID) == null }
    } ?: byUid.entries.firstOrNull()
        ?: return ResourceComponents(null, emptyList(), zones, emptyList(), 0)

    val declared = primary.value.firstOrNull { it.dateProperty(Property.RECURRENCE_ID) == null }
    // An exception whose master is missing cannot be linked to an occurrence; storing it as an
    // event of its own keeps it visible, which beats an orphan row the calendar app never shows.
    val master = declared ?: primary.value.first()
    val overrides = if (declared == null) {
        emptyList()
    } else {
        primary.value.filter { it.dateProperty(Property.RECURRENCE_ID) != null }
    }

    return ResourceComponents(
        master = master,
        overrides = overrides,
        zones = zones,
        droppedUids = byUid.keys.filterNotNull().filter { it != primary.key },
        droppedComponents = primary.value.size - (1 + overrides.size),
    )
}

internal fun parseResource(calendar: Calendar): ParsedResource {
    val components = resourceComponents(calendar)
    val component = components.master ?: return ParsedResource(null, emptyList(), components.zones, emptyList(), 0)
    val master = component.toParsedEvent()
    return ParsedResource(
        master = if (master.recurrenceId == null) master else master.copy(shape = EventShape.SINGLE),
        overrides = components.overrides.map { it.toParsedEvent() },
        zones = components.zones,
        droppedUids = components.droppedUids,
        droppedComponents = components.droppedComponents,
    )
}

internal fun VEvent.toParsedEvent(): ParsedEvent {
    val recurrenceId = dateProperty(Property.RECURRENCE_ID)
    val rrules = stringProperties(Property.RRULE)
    val rdates = dateListProperty(Property.RDATE)
    val exdates = dateListProperty(Property.EXDATE)
    return ParsedEvent(
        uid = getUid().map { it.value }.orElse(null),
        shape = when {
            recurrenceId != null -> EventShape.OVERRIDE
            rrules.isNotEmpty() || rdates.values.isNotEmpty() -> EventShape.RECURRING_MASTER
            else -> EventShape.SINGLE
        },
        recurrenceId = recurrenceId,
        start = dateProperty(Property.DTSTART),
        end = dateProperty(Property.DTEND),
        duration = stringProperty(Property.DURATION),
        rrules = rrules,
        rdates = rdates.values,
        exdates = exdates.values,
        unrepresentableDates = rdates.dropped + exdates.dropped,
        summary = stringProperty(Property.SUMMARY),
        description = stringProperty(Property.DESCRIPTION),
        location = stringProperty(Property.LOCATION),
        status = when (stringProperty(Property.STATUS)?.uppercase()) {
            "TENTATIVE" -> EventStatus.TENTATIVE
            "CONFIRMED" -> EventStatus.CONFIRMED
            "CANCELLED", "CANCELED" -> EventStatus.CANCELLED
            else -> null
        },
        transparency = when (stringProperty(Property.TRANSP)?.uppercase()) {
            "OPAQUE" -> Transparency.OPAQUE
            "TRANSPARENT" -> Transparency.TRANSPARENT
            else -> null
        },
        reminders = getAlarms().mapNotNull { it.toParsedReminder() },
        attendees = getProperties<Property>(Property.ATTENDEE).mapNotNull { it.toParsedAttendee() },
    )
}

internal fun VAlarm.toParsedReminder(): ParsedReminder? {
    val method = when (stringProperty("ACTION")?.uppercase()) {
        "DISPLAY" -> AlarmMethod.ALERT
        "EMAIL" -> AlarmMethod.EMAIL
        // AUDIO and PROCEDURE alarms have no `Reminders.METHOD`; storing them as alerts would
        // invent a notification the server never asked for.
        else -> return null
    }
    val trigger = getProperty<Property>(Property.TRIGGER).orElse(null) ?: return null
    val raw = propertyText(trigger) ?: return null
    val relatedToEnd = trigger.getParameter<Parameter>(Parameter.RELATED)
        .map { it.value.equals("END", ignoreCase = true) }
        .orElse(false)
    // A trigger is either a date-time (first character is a digit) or an RFC 5545 duration.
    return if (raw.first().isDigit()) {
        ParsedReminder(null, IcalDate(raw, trigger.parameter(Parameter.TZID)), relatedToEnd, method)
    } else {
        ParsedReminder(durationSeconds(raw) ?: return null, null, relatedToEnd, method)
    }
}

private fun Property.toParsedAttendee(): ParsedAttendee? {
    val raw = propertyText(this) ?: return null
    val email = raw.trim().removePrefix("mailto:").removePrefix("MAILTO:").trim()
    if (email.isEmpty()) return null
    return ParsedAttendee(
        email = email,
        name = parameter(Parameter.CN),
        role = parameter(Parameter.ROLE)?.uppercase(),
        cutype = parameter(Parameter.CUTYPE)?.uppercase(),
        partstat = parameter(Parameter.PARTSTAT)?.uppercase(),
    )
}

// ---------------------------------------------------------------- ical4j accessors

/** A property value read defensively: an unsupported TZID can throw while a value is rendered. */
private fun propertyText(property: Property): String? =
    runCatching { property.value }.getOrNull()?.takeIf { it.isNotBlank() }

private fun Property.parameter(name: String): String? =
    getParameter<Parameter>(name).map { it.value }.orElse(null)?.takeIf { it.isNotBlank() }

internal fun Component.stringProperty(name: String): String? =
    getProperty<Property>(name).map { propertyText(it) }.orElse(null)

private fun Component.stringProperties(name: String): List<String> =
    getProperties<Property>(name).mapNotNull { propertyText(it) }

/** A single-valued date property, with the zone it names. */
internal fun Component.dateProperty(name: String): IcalDate? {
    val property = getProperty<Property>(name).orElse(null) ?: return null
    val value = propertyText(property) ?: return null
    return IcalDate(value.trim(), property.parameter(Parameter.TZID))
}

/** A date-list property, flattened: `RDATE`/`EXDATE` lists are comma-separated. */
private fun Component.dateListProperty(name: String): DateList {
    val values = ArrayList<IcalDate>()
    var dropped = 0
    for (property in getProperties<Property>(name)) {
        val value = propertyText(property) ?: continue
        val tzid = property.parameter(Parameter.TZID)
        for (part in value.split(',')) {
            val text = part.trim()
            if (text.isEmpty()) continue
            // `VALUE=PERIOD` (`start/end`) has no form in the column, and a value the provider
            // cannot parse makes it discard the event's whole recurrence set, not just this entry.
            if (text.contains('/')) {
                dropped++
                continue
            }
            values.add(IcalDate(text, tzid))
        }
    }
    return DateList(values, dropped)
}

private data class DateList(val values: List<IcalDate>, val dropped: Int)

private var hintsEnabled = false

/**
 * A `TZID` that resolves to no zone is tolerated as a floating value instead of throwing while a
 * property is read; the zone is then chosen by [resolveZone] rather than by the parser.
 */
private fun enableRelaxedHints() {
    if (hintsEnabled) return
    CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_VALIDATION, true)
    CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_PARSING, true)
    hintsEnabled = true
}
