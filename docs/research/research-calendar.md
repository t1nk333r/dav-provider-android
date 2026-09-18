# CalDAV `VEVENT` → Android `CalendarContract`: property map and loss profile

Scope: **read-only v1** (server → device fidelity). Write-back is not assessed here.
Sources are AOSP source, the Android reference docs (generated from the same source), RFC 5545/4791, and the
reference CalDAV implementation for Android (DAVx5 / bitfireAT `synctools`) where AOSP leaves a convention
undefined. Everything non-obvious is cited; conventions that AOSP does not bless are marked as such.

---

## Verdict

`CalendarContract` can carry a server-authoritative `VEVENT` faithfully for the cases that matter — single
events, all-day events, and recurrence sets with `RRULE`/`RDATE`/`EXDATE` plus single-instance overrides —
**but only if the adapter writes AOSP's storage syntax rather than RFC 5545 syntax**:

- `RRULE` holds the **bare rule value** (`FREQ=WEEKLY;BYDAY=MO`), not a property line; multiple rules are
  newline-separated.
- `RDATE`/`EXDATE` hold `[TZID;]yyyyMMddTHHmmss[Z],...`, not the iCalendar property value.
- A **recurring master row must carry `DURATION` and must not carry `DTEND`**; a **non-recurring row and every
  override row must carry `DTEND` and must not carry `DURATION`**.
- All-day rows are `ALL_DAY=1`, `EVENT_TIMEZONE="UTC"`, times truncated to a UTC midnight boundary.

Under those rules the round-trip is real, not approximate. The losses are structural and fall into three groups:

1. **The provider models a recurrence set as one master row plus one row per `RECURRENCE-ID` override.** There is
   no representation for `RECURRENCE-ID;RANGE=THISANDFUTURE`, none that RFC 5545 still permits for `EXRULE`, and
   no dedicated column for the DAV resource itself — href/ETag/UID live in `_SYNC_ID` / `SYNC_DATA1..10` /
   `UID_2445` by adapter convention, not by platform contract.
2. **Malformed input is silently scrubbed, not rejected.** `CalendarProvider2.scrubEventData` *removes* fields
   that do not fit the row's role. A recurring event written with `DTEND` and no `DURATION` loses `DTEND` and
   expands to zero-length instances; an override written with `DURATION` loses `DURATION`. Both are accepted
   without error. V1 must enforce the syntax invariants client-side — the provider will not.
3. **Everything without a first-class column** (`CATEGORIES`, `URL`, `COLOR`, `SEQUENCE`, `X-*`,
   `CREATED`/`DTSTAMP`/`LAST-MODIFIED`) is carried only as a vendor convention in `ExtendedProperties` or
   `SYNC_DATA*`, i.e. it is device-local and invisible to the platform's calendar UI.

Time-zone fidelity depends on one thing only: `EVENT_TIMEZONE` must be a **zone ID that Android's tzdata knows**.
`android.text.format.Time.TimeCalculator.lookupZoneInfoData` falls back to `GMT` for an unrecognised ID,
silently — there is no error and no warning at the point of storage [A7]. A `VTIMEZONE` whose `TZID` is not a
system zone ID must therefore be resolved to a system `ZoneId` by the adapter *before* writing, or the event is
stored at the wrong wall time. All-day events sidestep this entirely, because the platform forces `UTC` on them.

---

## 1. Identity and sync columns

`Events` implements `BaseColumns, SyncColumns, EventsColumns, CalendarColumns` [A1]. The columns that exist for
the adapter are:

| Column | AOSP definition | v1 assignment |
|---|---|---|
| `_SYNC_ID` | "The unique ID for a row assigned by the sync source. NULL if the row has never been synced. This is used as a reference id for exceptions along with `_ID`." [A1] | master row: **DAV resource name** (last segment of the href); override rows: **NULL** |
| `_ID` | provider-assigned row id | never written by the adapter |
| `DIRTY` | "Used to indicate that local, unsynced, changes are present." [A1] | `0` (server-won write; the provider also leaves it alone for `caller_is_syncadapter=true` inserts [A2]) |
| `MUTATORS` | "Used in conjunction with `DIRTY` to indicate what packages wrote local changes." [A1] | not written; the provider manages it only for non-sync-adapter writes (`addMutator` in the insert path [A2]) |
| `SYNC_DATA1`..`SYNC_DATA10` | "available for use by sync adapters", all `TEXT` [A1] | free-form |
| `UID_2445` | "The UID for events added from the RFC 2445 iCalendar format." [A1] | **iCalendar `UID`** |
| `DELETED` | "Whether the row has been deleted but not synced to the server." [A1] | `0` |

All of `DIRTY`, `MUTATORS`, `_SYNC_ID` and `SYNC_DATA1..10` are **sync-adapter-only**: they appear in
`Events.SYNC_WRITABLE_COLUMNS`, and an insert/update carrying any of them from a non-sync-adapter caller is
rejected with `IllegalArgumentException("Only sync adapters may write to …")` [A1][A2].

### The convention (AOSP does not prescribe one)

`CalendarSyncColumns`/`SyncColumns` say *what* the slots are for, never *what to put in them*, and
`CalendarContract` has no column named for an href or an ETag. There is **no AOSP statement** that `_SYNC_ID`
is an href or that `SYNC_DATA1` is an ETag. The convention in the field, taken from the reference CalDAV
adapter for Android, is:

| Meaning | Column | Source of the convention |
|---|---|---|
| DAV resource name (href's last segment) | `Events._SYNC_ID` | `LocalEvent.fileName` reads `Events._SYNC_ID` and is passed as the resource file name; `SyncIdBuilder` writes it only on the master [R1][R2] |
| strong ETag of the resource | `Events.SYNC_DATA1` | `EventsContract.COLUMN_ETAG = CalendarContract.Events.SYNC_DATA1` [R3] |
| adapter sync flags | `SYNC_DATA2` (`COLUMN_FLAGS`) [R3] | |
| iCalendar `SEQUENCE` | `SYNC_DATA3` (`COLUMN_SEQUENCE`) [R3] | |
| RFC 6638 Schedule-Tag | `SYNC_DATA4` (`COLUMN_SCHEDULE_TAG`) [R3] | |
| iCalendar `UID` | `UID_2445` (primary), plus an `ExtendedProperties` row named `iCalUid` where Google-Calendar compatibility is needed [R3] | |

Three properties of this assignment are load-bearing:

- `_SYNC_ID` is expected to be unique **per calendar**, not per device: "Note `_sync_id` is only expected to be
  unique within a particular calendar" [A2]. There is **no unique index** on it — `createEventsTable` creates
  only `eventsCalendarIdIndex` on `calendar_id` [A3] — so de-duplication is entirely the adapter's job.
- The **ETag belongs to the resource, not to a component row** (`getetag` is a property of the calendar object
  resource, RFC 4791 §5.3.4 [F2]). With one resource holding a master plus overrides, the ETag must be stored on
  the master row only; the reference implementation writes ETag/Schedule-Tag on the master and writes `NULL` on
  every override [R2].
- Because `_SYNC_ID` is the exception linkage key (below), it must be **stable across the components of one
  resource** — which is why the href (or its last segment) is the right value and the `UID` alone is not: RFC 4791
  §4.1 permits a resource that contains only overrides, and forbids components with different UIDs in one
  resource, but the UID is *not* guaranteed unique across a collection in the way that a resource URL is a
  single row's identity here. `UID` is still stored, in `UID_2445`.

---

## 2. Calendar-level rows (`Calendars`)

Insert, per `CalendarContract.Calendars`' "Operations" block [A1]: the **mandatory** fields are

`ACCOUNT_NAME`, `ACCOUNT_TYPE`, `NAME`, `CALENDAR_DISPLAY_NAME`, `CALENDAR_COLOR`, `CALENDAR_ACCESS_LEVEL`,
`OWNER_ACCOUNT`. "Generally a good idea": `SYNC_EVENTS` = 1, `CALENDAR_TIME_ZONE`, `ALLOWED_REMINDERS`,
`ALLOWED_AVAILABILITY`, `ALLOWED_ATTENDEE_TYPES`.

Writing as a sync adapter requires **all three** of `CALLER_IS_SYNCADAPTER=true`, `ACCOUNT_NAME` and
`ACCOUNT_TYPE` as **URI query parameters** — the provider enforces this itself:
`verifyHasAccount()` throws `IllegalArgumentException("Sync adapters must specify an account and account type")`
when either parameter is missing [A2].

Columns writable **only** by a sync adapter are enumerated in `Calendars.SYNC_WRITABLE_COLUMNS` [A1]:
`ACCOUNT_NAME`, `ACCOUNT_TYPE`, `_SYNC_ID`, `DIRTY`, `MUTATORS`, `OWNER_ACCOUNT`, `MAX_REMINDERS`,
`ALLOWED_REMINDERS`, `CAN_MODIFY_TIME_ZONE`, `CAN_ORGANIZER_RESPOND`, `CAN_PARTIALLY_UPDATE`,
`CALENDAR_LOCATION`, `CALENDAR_TIME_ZONE`, `CALENDAR_ACCESS_LEVEL`, `DELETED`, `CAL_SYNC1`..`CAL_SYNC10`.
That array is what the provider enforces (`verifyNoSyncColumns`, `case CALENDARS`) [A2].

Note the documentation and the enforcement differ: the prose block in `CalendarContract.Calendars` also lists
`CALENDAR_COLOR`, `ALLOWED_AVAILABILITY` and `ALLOWED_ATTENDEE_TYPES` as sync-adapter-only [A1], but those three
are absent from `SYNC_WRITABLE_COLUMNS` and therefore are **not** rejected for a non-sync-adapter caller that
already holds `WRITE_CALENDAR` [A1][A2]. `CALENDAR_TIME_ZONE` and `CALENDAR_ACCESS_LEVEL` *are* enforced, and
both are mandatory on insert — so an ordinary app cannot create a functioning calendar for itself, only a sync
adapter can.

Caveats worth recording:

- `NAME` is documented as required but is **not enforced** by the provider; the reference implementation omits it
  entirely when creating calendars [R4]. Treat `NAME` as "required by documentation, tolerated missing in
  practice"; write it (`NAME` is also the only app-writable calendar identifier, alongside
  `CALENDAR_DISPLAY_NAME`, `VISIBLE`, `SYNC_EVENTS` [A1]).
- Collection-level identity has no sanctioned slot. The reference implementation stores its own collection row id
  in `Calendars._SYNC_ID` and keeps the DAV URL in its private database [R4][R5]. If the adapter needs the
  collection URL recoverable from the provider (v1 does, for re-discovery), it must choose a slot explicitly —
  `Cal_SYNC1..10` are the only free per-calendar slots that are sync-adapter-writable [A1].
- The reference implementation per calendar also sets a per-calendar sync-state string in `Cal_SYNC1` [R5][R6],
  which is a competing use of the same column family; pick one convention and document it in the ADR.

---

## 3. Property-by-property map

Legend: **mapped** = carried with no loss; **mapped-with-loss** = carried, with named information dropped;
**unmappable** = no column, no sanctioned carrier.

### 3.1 Identity, times, recurrence

| iCalendar | `CalendarContract` target | Status | Note |
|---|---|---|---|
| `UID` | `Events.UID_2445` (+ `_SYNC_ID` = resource name) | mapped | column doc: "The UID for events added from the RFC 2445 iCalendar format" [A1] |
| `DTSTART` | `DTSTART` (millis), `EVENT_TIMEZONE` | mapped | provider requires non-empty `EVENT_TIMEZONE` on every row (`validateEventData`) [A2] |
| `DTSTART;VALUE=DATE` | `DTSTART` + `ALL_DAY=1` + `EVENT_TIMEZONE="UTC"` | mapped | see §4 |
| `DTEND` | `DTEND` (millis), `EVENT_END_TIMEZONE` | mapped | non-recurring rows and overrides only |
| `DURATION` | `DURATION` (text) | mapped | recurring masters only; parsed by `com.android.calendarcommon2.Duration` [A5] |
| `DTEND`/`DURATION` absent | — | mapped-with-loss | default applies: `DATE` → 1 day, `DATE-TIME` → zero length, per RFC 5545 §3.6.1 [F1]. The reference implementation preserves the distinction only by storing `DTEND == DTSTART` for a `DATE-TIME` with no end [R7] — an identity that Android cannot distinguish from a genuine zero-length event |
| `RRULE` | `Events.RRULE` | mapped | bare value, `\n`-separated for multiple rules; validated on write |
| `RDATE` | `Events.RDATE` | mapped-with-loss | `PERIOD` values are dropped by the reference implementation (`RDATE PERIOD not supported, ignoring`) [R8]; an infinite `RRULE` plus `RDATE` forces `RDATE` to be dropped (`Android can't handle infinite RRULE + RDATE`, AOSP issue 216374004) [R8]; AOSP's own converter appends `DTSTART` to the list because the provider otherwise drops the first instance (AOSP issue 171292) [R8][A6] |
| `EXDATE` | `Events.EXDATE` | mapped | same storage format as `RDATE` |
| `EXRULE` | `Events.EXRULE` | mapped-with-loss | the column exists and the provider parses it, but **RFC 5545 removed the property** ("The EXRULE property can no longer be specified in a component") [F1, Appendix A and §3.9 change list]; a conforming server will never send it |
| `RECURRENCE-ID` | `ORIGINAL_SYNC_ID`, `ORIGINAL_INSTANCE_TIME`, `ORIGINAL_ALL_DAY`, `ORIGINAL_ID` | mapped | see §5 |
| `RECURRENCE-ID;RANGE=THISANDFUTURE` | — | unmappable | no column; the provider's model is one row per single overridden instance. `RANGE` semantics (RFC 5545 §3.8.4.4) would have to be expanded by the adapter into explicit per-instance overrides or dropped |
| `SEQUENCE` | — (reference impl.: `SYNC_DATA3`) | mapped-with-loss | no `Events` column; scheduling semantics are not modelled by AOSP |
| `DTSTAMP` | — | unmappable | dropped; no column |
| `LAST-MODIFIED` | — | unmappable | dropped; the reference implementation explicitly ignores it [R9] |
| `CREATED` | — | unmappable | dropped |
| `PRODID` | — | unmappable | dropped [R9] |
| `METHOD` | — | n/a | RFC 4791 §4.1 forbids `METHOD` in a calendar object resource [F2] |

### 3.2 Descriptive and classification

| iCalendar | Target | Status | Note |
|---|---|---|---|
| `SUMMARY` | `TITLE` | mapped | |
| `DESCRIPTION` | `DESCRIPTION` | mapped | |
| `LOCATION` | `EVENT_LOCATION` | mapped | |
| `ORGANIZER` | `Events.ORGANIZER` (+ `HAS_ATTENDEE_DATA`) | mapped-with-loss | only the bare email survives; `mailto:` is stripped, other URI schemes and every parameter except `EMAIL` are dropped. "Android only supports email addresses as organizer" [R10] |
| `ATTENDEE` | `Attendees` rows | mapped-with-loss | see §7 |
| `STATUS` | `Events.STATUS` | mapped | `CONFIRMED`→1, `CANCELLED`→2, `TENTATIVE`→0; an absent or unknown value maps to `TENTATIVE` in the reference implementation and to `NULL` when absent [R11] |
| `TRANSP` | `Events.AVAILABILITY` | mapped | `TRANSPARENT`→`AVAILABILITY_FREE`(1), `OPAQUE` (the RFC default) and anything else→`AVAILABILITY_BUSY`(0) [R12] |
| `CLASS` | `Events.ACCESS_LEVEL` | mapped-with-loss | `PUBLIC`→3, `PRIVATE`→2, `CONFIDENTIAL`→1, absent→`ACCESS_DEFAULT`(0); an unrecognised (e.g. `X-`-prefixed) class becomes `ACCESS_PRIVATE` and its original value is retained in `ExtendedProperties` [R13] |
| `CATEGORIES` | `ExtendedProperties`, name `categories` | mapped-with-loss | value is a `\`-separated list (the AOSP Exchange adapter's format); any `\` inside a category name is silently dropped; not visible to the platform UI [R3][R14] |
| `COLOR` | `Events.EVENT_COLOR_KEY` | mapped-with-loss | only usable if a matching row exists in the `Colors` table for that account and `COLOR_TYPE=event`; otherwise the colour is dropped [R15] |
| `URL` | `ExtendedProperties`, name `…/vnd.ical4android.url` | mapped-with-loss | vendor convention only [R3][R16] |
| `GEO`/`GEOLOCATION`, `PRIORITY`, `RESOURCES`, `CONTACT`, `COMMENT`, `ATTACH`, `RELATED-TO`, `REQUEST-STATUS` | — | unmappable | no column in any table; preserved only if the adapter chooses to serialise them as extended properties |
| `X-*` and any other unknown property | `ExtendedProperties`, name `…/vnd.ical4android.unknown-property` | mapped-with-loss | vendor convention: JSON `[name, value, params?]`, one row per property, properties over 25 000 octets dropped [R9][R17] |

`ExtendedProperties` **is the sanctioned home**: "This is a generic set of name/value pairs for use by sync
adapters to add extra information to events", three writable columns (`EVENT_ID`, `NAME`, `VALUE`), and "The
provider takes no action with items in this table except to delete them when their related events are deleted"
[A1]. It is **sync-adapter-only to write**: the provider rejects any non-sync-adapter write to the
`extendedproperties` URI [A2]. The class carries an explicit caveat in AOSP itself — "TODO: fill out this class
when we actually start utilizing extendedproperties in the calendar application" [A1] — i.e. the platform does not
read these rows; they exist for the adapter (and for non-AOSP calendar apps that opt in).

### 3.3 Alarms

| iCalendar | Target | Status | Note |
|---|---|---|---|
| `VALARM` + `TRIGGER` relative to `START`, expressed in minutes | `Reminders` row (`MINUTES`) | mapped | all three of `EVENT_ID`, `MINUTES`, `METHOD` must be present on insert [A1] |
| `ACTION:DISPLAY`/`AUDIO` | `Reminders.METHOD_ALERT`(1) | mapped | |
| `ACTION:EMAIL` | `Reminders.METHOD_EMAIL`(2) | mapped-with-loss | the device "will only process `METHOD_DEFAULT` and `METHOD_ALERT` reminders (the other types are simply stored…)" — the reminder is stored but never fires [A1]; `ATTENDEE` on the alarm is dropped [R18] |
| other/absent `ACTION` | `Reminders.METHOD_DEFAULT`(0) | mapped-with-loss | stored, never fires on device [A1] |
| `TRIGGER` absolute `DATE-TIME`, or relative to `END` | — | unmappable | reference implementation refuses `RELATED=END` and falls back to `MINUTES_DEFAULT`(-1) [R18]; a `Reminders` row is minutes-before-*start* or nothing |
| `REPEAT`/`DURATION` on the alarm, `ACKNOWLEDGED`, snooze semantics | — | unmappable | no columns |
| per-event reminder count | `Calendars.MAX_REMINDERS` | mapped | "The number of reminders per event is specified in `Calendars.MAX_REMINDERS` which is set by the Sync Adapter that owns the given calendar" [A1]; sync-adapter-only column [A1] |

### 3.4 Derived / provider-owned (must not be written)

| Table | Why |
|---|---|
| `Instances` | generated by the provider from `DTSTART`/`DURATION`/`RRULE`/`RDATE`; insert, update and delete all throw `UnsupportedOperationException` ("Inserting into instances not supported", etc.) [A2] |
| `EventsRawTimes` | `@hide` table holding the RFC 2445 form of start/end/original-instance/last-date; written by the provider from the row values [A1][A2] |
| `LAST_DATE` | computed by the provider (`updateLastDate`) from the recurrence rule [A2] |
| `HAS_ALARM`, `HAS_EXTENDED_PROPERTIES`, `SELF_ATTENDEE_STATUS` | provider-maintained; `HAS_ALARM` is explicitly stripped from caller values on insert and update [A2] |

---

## 4. All-day events and time zones

**All-day.** The contract is one sentence: "If `allDay` is set to 1 `eventTimezone` must be `"UTC"` and the time
must correspond to a midnight boundary" [A1]. `DTSTART;VALUE=DATE` therefore maps to `ALL_DAY=1`, millis of
*UTC midnight of that date*, `EVENT_TIMEZONE="UTC"`.

The provider does not enforce this — it **corrects it** (`fixAllDayTime`): if `ALL_DAY=1`, the hours/minutes/
seconds of `DTSTART` and `DTEND` are truncated to zero in the UTC zone, and a `DURATION` of the form `P<n>S` is
rewritten to `P<ceil(n/86400)>D` [A2]. Two consequences:

- A date sent as local midnight (`TZID=Europe/Vienna:20260101T000000`) silently becomes the *previous or next*
  day's UTC midnight depending on offset sign. Resolve `VALUE=DATE` to UTC midnight explicitly.
- **`DURATION` in RFC 5545 `PT<n>S` form crashes the provider.** `fixAllDayTime` tests
  `duration.charAt(0)=='P' && duration.charAt(len-1)=='S'` and then does
  `Integer.parseInt(duration.substring(1, len-1))`; for `"PT0S"` that is `parseInt("T0")` →
  `NumberFormatException`, uncaught, inside the provider [A2][R19]. Because it fires only for `ALL_DAY=1`, the
  correct rule for all-day durations is to write the day form (`P1D`, `P3D`) — which is also what RFC 5545
  requires for a `DATE` `DTSTART` ("MUST be specified as a `dur-day` or `dur-week` value", §3.6.1 [F1]).
- `DTEND` remains **exclusive** in both worlds: RFC 5545 §3.6.1 ("The `DTEND` property … specifies the
  non-inclusive end of the event") [F1] and `CalendarContract` stores the millis unchanged. A one-day event
  `DTSTART;VALUE=DATE:20260101` with no `DTEND` has implied duration 1 day [F1], so the row must be written with
  `DTEND` = 2026-01-02T00:00Z, i.e. **do not store the inclusive last day**.

**Timed events.** `EVENT_TIMEZONE` takes the IANA zone ID (`Europe/Vienna`), `"UTC"` for `Z`-suffixed values, and
the **system default zone ID for floating time** — the reference implementation resolves a floating date-time to
`ZoneId.systemDefault().id` at map time [R20]. `EVENT_END_TIMEZONE` is written from the end value's zone and
stored verbatim; in the open-source provider it appears only in the schema, the `view_events` column list and the
generic read/write plumbing — no computation reads it, and instance expansion computes the end from `DTSTART` +
`DURATION`/`DTEND` millis [A1][A2][A3][A4]. Treat `EVENT_END_TIMEZONE` as write-for-round-trip, not as a value
the platform uses. For all-day rows it is written as `"UTC"` like `EVENT_TIMEZONE`.

**`VTIMEZONE` that Android does not know.** Only the `TZID` string is stored; `VTIMEZONE` itself has no column.
`android.text.format.Time` — the class the provider itself uses for all of this — looks the ID up in
`ZoneInfoDb` and, on failure, substitutes `"GMT"`:

```java
private static ZoneInfoData lookupZoneInfoData(String timezoneId) {
    ZoneInfoData zoneInfoData = ZoneInfoDb.getInstance().makeZoneInfoData(timezoneId);
    if (zoneInfoData == null) {
        zoneInfoData = ZoneInfoDb.getInstance().makeZoneInfoData("GMT");
    }
```
[A7]. `java.util.TimeZone.getTimeZone(String)` has the same contract in words and code: "We never return null; on
failure we return the equivalent of `GMT`" [A8]. So an unrecognised `TZID` produces **no error at all**: the
event is silently interpreted as GMT, which for a European calendar is a 1–2 hour shift, and recurrence
expansion uses the same wrong zone. The adapter must map every `TZID` to a system `ZoneId` (or to a fixed
offset) before writing, and must decide policy for the unmappable case instead of passing the string through.

---

## 5. Recurrence and exceptions

### 5.1 Storage rules (implementable)

- **Recurring master row**: `RRULE` and/or `RDATE` non-empty, `DURATION` non-empty, `DTEND` empty, `EXDATE`
  allowed, `ORIGINAL_*` all empty. `CalendarContract`: "Exceptions are not allowed to recur. If `rrule` or
  `rdate` is not empty, `original_id` and `original_sync_id` must be empty" [A1]. The provider's
  `scrubEventData` enforces the shape by **deletion**: for a row with `RRULE`/`RDATE` it removes `DTEND`,
  `ORIGINAL_SYNC_ID` and `ORIGINAL_INSTANCE_TIME` if any of them is present [A2].
- **Override row** (a `VEVENT` with `RECURRENCE-ID`): `DTEND` non-empty, `DURATION` empty, `RRULE`/`RDATE`/
  `EXRULE`/`EXDATE` empty, `ORIGINAL_SYNC_ID` + `ORIGINAL_INSTANCE_TIME` set, `ORIGINAL_ALL_DAY` set from the
  **master's** `DTSTART` value type. Same scrubbing: for an `ORIGINAL_*` row with `DURATION` present, the
  provider removes `DURATION` [A2].
- **Regular row**: `DTEND` non-empty, `DURATION` empty.
- The provider accepts **exactly one** of `DTEND`/`DURATION` on a non-scrubbed path and rejects both together:
  `validateEventData` throws `"DTEND and DURATION cannot both be null"` and `"Cannot have both DTEND and
  DURATION"` [A2]. `scrubEventData` runs instead of `validateEventData` for `caller_is_syncadapter=true`
  inserts [A2] — which is exactly why malformed sync-adapter input is silently degraded rather than refused. If
  a recurring master ends up with neither `DTEND` nor `DURATION`, instance expansion logs
  "Repeating event has no duration -- should not happen" and falls back to zero length (`+P0S`) [A4].
- `RRULE` **is** validated on write: every newline-separated rule is parsed with `EventRecurrence`; a failure
  raises `IllegalArgumentException("Invalid recurrence rule: …")` [A2]. Because unknown rule parts throw
  `InvalidFormatException("Couldn't find parser for …")` [A6], RFC 7529 extensions (`RSCALE`, `SKIP`) are a
  **hard failure**, not silent loss. `RRULE` must be the bare value with no `RRULE:` prefix — AOSP's own
  component→values converter passes `property.getValue()` straight through [A9]. `RDATE`/`EXDATE` are *not*
  validated on write; they are parsed later, during instance expansion, by `RecurrenceSet` [A6].
- `ORIGINAL_INSTANCE_TIME` is the millis of the `RECURRENCE-ID` value normalised to the same value type as the
  master `DTSTART`: an all-day master requires a `DATE` `RECURRENCE-ID`, a timed master a `DATE-TIME` one
  (RFC 5545 §3.8.4.4 requires them to match anyway [F1]); the reference implementation also repairs the mismatch
  when a server sends the other type [R21].
- `ORIGINAL_ALL_DAY` = whether the **master's** `DTSTART` is a `DATE` [R21].
- The linkage keys are `_SYNC_ID` (master) and `ORIGINAL_SYNC_ID` (override). `ORIGINAL_ID` is the resolved
  `Events._ID` and is a convenience for joining; **setting only `ORIGINAL_ID` is not sufficient** for an insert
  [R22].

### 5.2 Required ordering between master and overrides

The provider supports **either** insertion order, by two mechanisms, but the adapter must not rely on them
casually:

1. *Overrides first.* `insertInTransaction` resolves `ORIGINAL_ID` from `ORIGINAL_SYNC_ID` + `CALENDAR_ID` when
   the master already exists; if it is not found, the row is inserted with `ORIGINAL_ID` unset. When the master
   is later inserted with a non-empty `_SYNC_ID` and a recurrence rule, `backfillExceptionOriginalIds` runs
   `UPDATE events SET original_id=? WHERE original_sync_id=? AND calendar_id=?` — explicitly documented as
   "The server might send exceptions before the event they refer to" [A2].
2. *Master first, `_SYNC_ID` set later.* The database trigger `original_sync_update` (`UPDATE OF _sync_id ON
   Events`) writes the master's new `_SYNC_ID` into the `ORIGINAL_SYNC_ID` of every child whose `ORIGINAL_ID`
   points at it [A3].

The requirement is therefore not an ordering *rule* but an ordering *choice plus one invariant*:

> **Invariant (mandatory):** every override row must carry `ORIGINAL_SYNC_ID` = the master row's `_SYNC_ID`, and
> the master row must carry that `_SYNC_ID` (and a recurrence rule) in the same sync pass.
>
> **Recommendation:** write the master row first and its overrides after it, in one `applyBatch` in that order.
> This is what the reference implementation does (`addEventAndExceptions`: main event first, then exceptions, one
> batch) [R22], and it makes `ORIGINAL_ID` resolve immediately rather than depending on a later backfill.

Two further constraints on the same write:

- `backfillExceptionOriginalIds` only fires for a master with a **non-empty `_SYNC_ID`** and a non-empty
  `RRULE`/`RDATE` [A2] — an override whose master has no `_SYNC_ID` is orphaned forever.
- Deleting the master does **not** delete the overrides: the `events_cleanup_delete` trigger removes instances,
  raw times, attendees, reminders, calendar alerts and extended properties of the deleted event only [A3], and
  the reference implementation issues an explicit second delete for `ORIGINAL_ID = <master>` [R22].
- `ORIGINAL_SYNC_ID` matching is scoped by `CALENDAR_ID` [A2][A3]; the `_SYNC_ID` convention must therefore be
  unique per calendar, as noted in §1.

---

## 6. Multi-component resources

RFC 4791 §4.1 [F2] is explicit and has three parts that determine the decomposition:

- "Calendar components with the same `UID` property value, in a given calendar collection, MUST be contained in
  the same calendar object resource. This ensures that all components in a recurrence 'set' are contained in the
  same calendar object resource."
- "It is possible for a calendar object resource to just contain components that represent 'overridden'
  instances … without also including the 'master' recurring component."
- A resource may contain only **one** component type (plus `VTIMEZONE`s).

**Correct decomposition.** For each `.ics` resource:

1. Parse all `VEVENT`s. Partition into `main` (those with no `RECURRENCE-ID`) and `exceptions` (those with one).
   This is exactly the reference implementation's `AssociatedComponents`, which also validates that all
   components share one UID and that no main component carries a `RECURRENCE-ID` [R23].
2. `_SYNC_ID` = the resource name, written on the master row only; ETag/Schedule-Tag on the master row only [R2].
3. Master row: `RRULE`/`RDATE`/`EXDATE` (+ `DTEND`-vs-`DURATION` per §5) [R8][R7].
4. One row **per exception component**, each with `ORIGINAL_SYNC_ID` = the resource name,
   `ORIGINAL_INSTANCE_TIME` from its `RECURRENCE-ID`, `ORIGINAL_ALL_DAY` from the master's `DTSTART` type, and
   with `RRULE`/`RDATE`/`EXRULE`/`EXDATE` **removed** (the provider would scrub them anyway; the reference
   implementation strips them defensively) [R2][R22].
5. Attendees, reminders, extended properties attach per row (`EVENT_ID`) — an override opens *its own* sub-rows
   [A2][R2].
6. Resource with **only overrides** (legal per §4.1): there is no master row. The provider can still associate
   the overrides if their `ORIGINAL_SYNC_ID` is the resource name — but `backfillExceptionOriginalIds` needs a
   master row with that `_SYNC_ID` to ever set `ORIGINAL_ID` [A2], so the rows exist with `ORIGINAL_SYNC_ID` set
   and `ORIGINAL_ID` NULL, and no `Events` query by `ORIGINAL_ID` will find them. V1 should either accept that
   (rows are visible as standalone events, linkage dangling) or synthesise nothing and log it — this is a real
   v1 limitation, not a mapping bug.

**What breaks under a naive one-resource-one-row mapping.** Everything about the resource's recurrence:

- The master's `RECURRENCE-ID`-less `VEVENT` and each override share one `UID`; a single row can only express one
  of them, so the other components' data is dropped.
- If the *last* component in file order wins, an override overwrites the master: the row then has
  `DTEND`-shaped times and no `RRULE`, so the recurrence disappears and only one instance survives.
- If two resources with the same `UID` in different collections are collapsed into one row, `UID_2445` is no
  longer unique per calendar; `_SYNC_ID` (resource name) is the only key that keeps them apart [A2].
- Conversely, treating each component as an independent event *without* `ORIGINAL_SYNC_ID` leaves the overrides
  as unrelated rows that the provider never links, and (for a deleted-instance override) never applies as
  cancellations.

---

## 7. Attendees, actions and per-row sub-tables

`Attendees` rows are writable by apps and sync adapters alike; "There are six writable fields and all of them
except `ATTENDEE_NAME` must be included when inserting a new attendee": `EVENT_ID`, `ATTENDEE_NAME`,
`ATTENDEE_EMAIL`, `ATTENDEE_RELATIONSHIP`, `ATTENDEE_TYPE`, `ATTENDEE_STATUS` (+ `ATTENDEE_IDENTITY`,
`ATTENDEE_ID_NAMESPACE` for non-email identities) [A1].

Mapping used by the reference implementation, which is the only documented mapping found:

| iCalendar | `Attendees` column | Note |
|---|---|---|
| `ATTENDEE:mailto:x@y` | `ATTENDEE_EMAIL` (scheme stripped) | non-`mailto:` URIs go to `ATTENDEE_IDENTITY` + `ATTENDEE_ID_NAMESPACE`, with the `EMAIL` parameter (if any) as `ATTENDEE_EMAIL` [R24] |
| `CN` parameter | `ATTENDEE_NAME` | |
| `ROLE`, `CUTYPE`, `RSVP` | `ATTENDEE_TYPE`, `ATTENDEE_RELATIONSHIP` | "type/relation mapping is complex" (`AttendeeMappings.iCalendarToAndroid`) [R24] |
| `PARTSTAT=ACCEPTED/DECLINED/TENTATIVE` | `ATTENDEE_STATUS` 1/2/4 | |
| `PARTSTAT=DELEGATED` | `ATTENDEE_STATUS_NONE`(0) | lossy |
| absent `PARTSTAT` (`NEEDS-ACTION`) | `ATTENDEE_STATUS_INVITED`(3) | |
| `DELEGATED-TO`, `DELEGATED-FROM`, `MEMBER`, `DIR`, `X-*` parameters | — | unmappable, dropped [R24] |
| `ORGANIZER` present | `Events.HAS_ATTENDEE_DATA=1`, `Events.ORGANIZER`=email | without it, `HAS_ATTENDEE_DATA=0` and `ORGANIZER` defaults to the calendar owner [R10] |

`SELF_ATTENDEE_STATUS` on the event is "a copy of the attendee status for the owner of this event … so that we
can efficiently filter out events that are declined" [A1]; the provider maintains it from the `Attendees` rows
(`updateEventAttendeeStatus`) [A2] — do not set it directly, and do not set `HAS_ATTENDEE_DATA` inconsistently
with the sub-rows.

`Reminders`: see §3.3. `Attendees`/`Reminders`/`ExtendedProperties` are keyed by `EVENT_ID`, so **they are
per-row**: an override's attendees must be written against the override's `_ID`, and the provider will *not*
inherit the master's [A1][R2].

---

## 8. Permissions and provider behaviour

- The provider declares `android:readPermission="android.permission.READ_CALENDAR"` and
  `android:writePermission="android.permission.WRITE_CALENDAR"` [A10]; per-operation enforcement happens in
  `ContentProvider.Transport` (`enforceReadPermission`/`enforceWritePermission`) [A11]. The reference CalDAV
  adapter declares both permissions in its manifest [R25]. `caller_is_syncadapter=true` is a **URI parameter that
  the provider merely reads** — `SQLiteContentProvider.getIsCallerSyncAdapter` returns
  `QueryParameterUtils.readBooleanQueryParameter(uri, CalendarContract.CALLER_IS_SYNCADAPTER, false)` with no
  verification that the caller is a registered sync adapter for the account [A12]. The security boundary is the
  `WRITE_CALENDAR` permission plus, for sync-adapter writes, the requirement that `ACCOUNT_NAME`/`ACCOUNT_TYPE`
  be present in the URI [A2]. (Inference: passing the flag is therefore *not* a capability escalation for an app
  that already holds `WRITE_CALENDAR`; it only selects the sync-adapter code path and its column allowances.)
- The adapter writes as **one account**: "Sync adapters have write access to more columns but are restricted to a
  single account at a time", and the account must be given in the URI [A1][A2]. The account must exist in
  `AccountManager`.
- **`Calendars` must exist before `Events`** in every practical sense, though the provider raises no error.
  `Events.CALENDAR_ID` is `INTEGER NOT NULL` [A3] and `insertInTransaction` rejects a null with
  `"New events must specify a calendar id"` [A2]. There is **no foreign key**: `createEventsTable` declares no
  `REFERENCES` and creates only `eventsCalendarIdIndex` [A3]. But the app-facing `Events` URI is served from
  `view_events`, which is `FROM Events JOIN Calendars ON Events.calendar_id = Calendars._id` [A3][A2], and the
  attendee/reminder queries carry the same join [A2] — so a row with a dangling `calendar_id` is **invisible to
  every consumer** while still occupying the table. Additionally the provider fills a missing `ORGANIZER` from the
  calendar's `OWNER_ACCOUNT` [A2], which is null for a missing calendar. Practically: insert the `Calendars` row
  first, and treat its `_ID` as a precondition.
- **Calendars are reaped for accounts that no longer exist**, exactly as contacts rows are. The provider registers
  an `AccountManager` accounts-updated listener at startup and runs `removeStaleAccounts()`, which deletes every
  `Calendars` (and `Colors`) row whose `(account_name, account_type)` is not in the current account list —
  explicitly exempting `ACCOUNT_TYPE_LOCAL` [A2]. `CalendarContract` documents the same rule at the column:
  "If account_type is not `ACCOUNT_TYPE_LOCAL` then the name and type must match an account on the device or the
  calendar will be deleted" [A1]. Consequence for this project: the DAV account **must** be a real
  `AccountManager` account (which matches the existing ADR decision that the app's account and the platform
  account are one object), or calendars — and with them events, via the `calendar_cleanup` trigger [A3] — are
  deleted on the next account change.

---

## 9. What read-only v1 will silently not carry

Ordered by how likely it is to be noticed:

1. `RECURRENCE-ID;RANGE=THISANDFUTURE` semantics — v1 maps the single instance only; the "and all following"
   effect is lost unless the adapter expands it into explicit overrides.
2. Overrides in a resource with **no master component** (§6.6): the rows are inserted but `ORIGINAL_ID` stays
   NULL, so no consumer ever sees them as part of a recurrence set.
3. `RDATE` with `PERIOD` values; `RDATE` whenever an infinite `RRULE` is also present [R8].
4. `EXRULE` (legal only under the obsoleted RFC 2445).
5. `DTSTAMP`, `CREATED`, `LAST-MODIFIED`, `PRODID`, `SEQUENCE` (the last only via a vendor sync column), and
   `METHOD` (illegal in CalDAV anyway).
6. Alarm semantics beyond "N minutes before `DTSTART`": `RELATED=END`, absolute triggers, `REPEAT`/`DURATION`,
   email reminders' recipients and delivery, `ACKNOWLEDGED`.
7. Attendee parameters `DELEGATED-TO`/`DELEGATED-FROM`/`MEMBER`/`RSVP`/`CUTYPE` (partially mapped at best),
   and any attendee identity that is not an email address (stored as identity/namespace, not surfaced by the
   platform UI).
8. `CATEGORIES`, `URL`, `COLOR` and every `X-*` property: stored only in `ExtendedProperties`, hence invisible
   to the platform calendar UI and to any other app that does not know the convention.
9. `GEO`, `PRIORITY`, `RESOURCES`, `CONTACT`, `COMMENT`, `ATTACH`, `RELATED-TO`, `REQUEST-STATUS`: dropped
   unless the adapter chooses to serialise them as extended properties.
10. `VTIMEZONE` definitions themselves (only the `TZID` string is kept), and therefore any historical or
    non-IANA offset rule — the platform applies its own tzdata to the stored zone ID forever.
11. The exact time of an "instant" event: an event with `DTSTART` and no `DTEND`/`DURATION` is indistinguishable
    from a zero-length event once stored (both are `DTEND == DTSTART`) [R7].
12. The DAV resource's own URL and ETag as first-class data: recoverable only from the adapter's convention
    (`_SYNC_ID`, `SYNC_DATA1`) and not exposed by any platform API.

---

## 10. Unresolved — and what would settle each

- **`EVENT_END_TIMEZONE` consumption.** The column exists, is documented, and is stored, but no AOSP provider
  code path reads it (instance expansion uses `DTSTART` + `DURATION`/`DTEND` millis) [A2][A4]. Settled by: an
  on-device test writing `DTEND` in a zone different from `DTSTART` and observing instance rows/UI, or by the
  AOSP Calendar app's own source.
- **`UID_2445` read-back.** No AOSP code in the open-source provider or `calendarcommon2` reads `UID_2445`
  [A1][A2][A6]. Whether the platform Calendar app or Google's proprietary provider surfaces it is not
  determinable from AOSP. Settled by: device test, or proprietary source.
- **`SYNC_DATA1..10` assignments.** AOSP deliberately leaves them opaque [A1]; the assignment in §1 is the
  reference implementation's [R3]. Nothing in AOSP blesses it. Settled by: accepting it as a project
  convention (recommended — interop with DAVx5-populated devices is the only benefit at stake).
- **Exact Android version scope.** All citations are `refs/heads/main`; the behaviours that matter here
  (`scrubEventData`, `fixAllDayTime`, `backfillExceptionOriginalIds`, `removeStaleAccounts`, the
  `PT<n>S` crash) have existed for many releases but were not diffed across `minSdk`..`main`. Settled by:
  diffing the same functions on the oldest supported branch (`android-8.0.0_r1` etc.).
- **Provider behaviour on OEM builds.** AOSP source says nothing about Samsung/Xiaomi providers. The
  `caller_is_syncadapter` parameter and the scrubbing rules are exactly the kind of thing OEM forks alter.
  Settled by: device tests on target OEMs, which cannot be replaced by source reading.

---

## Sources

Primary (AOSP / IETF):

- [A1] `CalendarContract.java` — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/CalendarContract.java
  (`SyncColumns`, `CalendarSyncColumns`, `Calendars`, `Events`, `Attendees`, `Reminders`, `ExtendedProperties`,
  table documentation and writable-column lists)
- [A2] `CalendarProvider2.java` — https://android.googlesource.com/platform/packages/providers/CalendarProvider/+/refs/heads/main/src/com/android/providers/calendar/CalendarProvider2.java
  (`verifyTransactionAllowed`, `verifyHasAccount`, `verifyNoSyncColumns`, `insertInTransaction`,
  `scrubEventData`, `validateEventData`, `fixAllDayTime`, `backfillExceptionOriginalIds`, `getOwner`,
  `validateRecurrenceRule`, `updateLastDate`, `removeStaleAccounts`)
- [A3] `CalendarDatabaseHelper.java` — https://android.googlesource.com/platform/packages/providers/CalendarProvider/+/refs/heads/main/src/com/android/providers/calendar/CalendarDatabaseHelper.java
  (`createEventsTable`, `VIEW_EVENTS`/`view_events`, `original_sync_update` trigger, `events_cleanup_delete`,
  `calendar_cleanup`)
- [A4] `CalendarInstancesHelper.java` — https://android.googlesource.com/platform/packages/providers/CalendarProvider/+/refs/heads/main/src/com/android/providers/calendar/CalendarInstancesHelper.java
- [A5] `Duration.java` — https://android.googlesource.com/platform/frameworks/opt/calendar/+/refs/heads/main/src/com/android/calendarcommon2/Duration.java
- [A6] `RecurrenceSet.java`, `EventRecurrence.java`, `RecurrenceProcessor.java` — https://android.googlesource.com/platform/frameworks/opt/calendar/+/refs/heads/main/src/com/android/calendarcommon2/
- [A7] `Time.java` (`TimeCalculator.lookupZoneInfoData`) — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/text/format/Time.java
- [A8] `java.util.TimeZone` (libcore, `getTimeZone(String)`) — https://android.googlesource.com/platform/libcore/+/refs/heads/main/ojluni/src/main/java/java/util/TimeZone.java
- [A9] `RecurrenceSet.populateContentValues` / `computeDuration` / `extractDates` / `flattenProperties` [A6]
- [A10] `AndroidManifest.xml` (CalendarProvider) — https://android.googlesource.com/platform/packages/providers/CalendarProvider/+/refs/heads/main/AndroidManifest.xml
- [A11] `ContentProvider.java` (`enforceReadPermission`/`enforceWritePermission` per operation) — https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/ContentProvider.java
- [A12] `SQLiteContentProvider.java` (`getIsCallerSyncAdapter`) — https://android.googlesource.com/platform/packages/providers/CalendarProvider/+/refs/heads/main/src/com/android/providers/calendar/SQLiteContentProvider.java

Standards:

- [F1] RFC 5545 (iCalendar), §3.6.1 `VEVENT`, §3.8.2.2 `DTEND`, §3.8.4.4 `RECURRENCE-ID`, §3.8.5.1 `EXDATE`,
  §3.8.5.3 `RDATE`, Appendix A / §3.9 (`EXRULE` removed) — https://www.rfc-editor.org/rfc/rfc5545.txt
- [F2] RFC 4791 (CalDAV), §4.1 calendar object resources, §5.3.4 `getetag` — https://www.rfc-editor.org/rfc/rfc4791.txt

Reference implementation (conventions; not AOSP-blessed):

- [R1] `LocalEvent.kt` — https://github.com/bitfireAT/davx5-ose/blob/main/core/src/main/kotlin/at/bitfire/davdroid/resource/local/LocalEvent.kt
- [R2] `SyncIdBuilder.kt`, `ETagBuilder.kt`, `UidBuilder.kt`, `OriginalInstanceTimeBuilder.kt`, `cleanMainEvent`/`cleanException` — https://github.com/bitfireAT/synctools/tree/main/lib/src/main/kotlin/at/bitfire/synctools/mapping/calendar/builder and https://github.com/bitfireAT/synctools/blob/main/lib/src/main/kotlin/at/bitfire/synctools/storage/calendar/AndroidRecurringCalendar.kt
- [R3] `EventsContract.kt` — https://github.com/bitfireAT/synctools/blob/main/lib/src/main/kotlin/at/bitfire/synctools/storage/calendar/EventsContract.kt
- [R4] `LocalCalendarStore.java`/`.kt` (`valuesFromCollectionInfo`, `create`) — https://github.com/bitfireAT/davx5-ose/blob/main/core/src/main/kotlin/at/bitfire/davdroid/resource/local/LocalCalendarStore.kt
- [R5] `LocalCalendar.kt` — https://github.com/bitfireAT/davx5-ose/blob/main/core/src/main/kotlin/at/bitfire/davdroid/resource/local/LocalCalendar.kt
- [R6] `AndroidCalendarProvider.kt` (`COLUMN_CALENDAR_SYNC_STATE = Calendars.CAL_SYNC1`) — https://github.com/bitfireAT/synctools/blob/main/lib/src/main/kotlin/at/bitfire/synctools/storage/calendar/AndroidCalendarProvider.kt
- [R7] `EndTimeBuilder.kt` / `DurationBuilder.kt` — https://github.com/bitfireAT/synctools/tree/main/lib/src/main/kotlin/at/bitfire/synctools/mapping/calendar/builder
- [R8] `RecurrenceFieldsBuilder.kt` (RDATE PERIOD dropped, infinite-RRULE+RDATE dropped, DTSTART prepended) [R7 directory]
- [R9] `UnknownPropertiesBuilder.kt` and its `KNOWN_PROPERTY_NAMES` list [R7 directory]
- [R10] `OrganizerBuilder.kt` [R7 directory]
- [R11] `StatusBuilder.kt` [R7 directory]
- [R12] `AvailabilityBuilder.kt` [R7 directory]
- [R13] `AccessLevelBuilder.kt` [R7 directory]
- [R14] `CategoriesBuilder.kt` [R7 directory]
- [R15] `ColorBuilder.kt` [R7 directory]
- [R16] `UrlBuilder.kt` [R7 directory]
- [R17] `UnknownProperty.kt` (`CONTENT_ITEM_TYPE`, `MAX_UNKNOWN_PROPERTY_SIZE = 25000`) — https://github.com/bitfireAT/synctools/blob/main/lib/src/main/kotlin/at/bitfire/ical4android/UnknownProperty.kt
- [R18] `RemindersBuilder.kt` [R7 directory]
- [R19] bitfireAT/synctools issue 144 — "All-day events with zero duration are mapped to `DURATION:PT0S` which crashes calendar provider" (reports `NumberFormatException: For input string: "T0"` at `CalendarProvider2.fixAllDayTime`) — https://github.com/bitfireAT/synctools/issues/144
- [R20] `AndroidTimeUtils.androidTimezoneId()` — https://github.com/bitfireAT/synctools/blob/main/lib/src/main/kotlin/at/bitfire/synctools/util/AndroidTimeUtils.kt
- [R21] `OriginalInstanceTimeBuilder.kt` [R7 directory]
- [R22] `AndroidRecurringCalendar.kt` (`addEventAndExceptions`, `deleteEventAndExceptions`) — https://github.com/bitfireAT/synctools/blob/main/lib/src/main/kotlin/at/bitfire/synctools/storage/calendar/AndroidRecurringCalendar.kt
- [R23] `AssociatedComponents.kt` — https://github.com/bitfireAT/synctools/blob/main/lib/src/main/kotlin/at/bitfire/synctools/icalendar/AssociatedComponents.kt
- [R24] `AttendeesBuilder.kt` and `AttendeeMappings.kt` [R7 directory]
- [R25] `synctools/src/main/AndroidManifest.xml` inside davx5-ose (declares `READ_CALENDAR`, `WRITE_CALENDAR`) — https://github.com/bitfireAT/davx5-ose/blob/main/synctools/src/main/AndroidManifest.xml
