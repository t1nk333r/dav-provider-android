package xyz.satr.davprovider.provider.calendar

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.provider.CalendarContract.SyncState
import android.util.Log
import org.json.JSONObject
import xyz.satr.davprovider.core.CollectionState
import xyz.satr.davprovider.core.DavCollection
import xyz.satr.davprovider.core.ProviderMapper
import java.time.ZoneId

private const val TAG = "CalendarMapper"

/** SQLite stops accepting host parameters well before this; ids are deleted in chunks. */
private const val ID_CHUNK = 500

/**
 * Writes CalendarDAO resources into `com.android.calendar`.
 *
 * Read-only: every row is written with `CALLER_IS_SYNCADAPTER`, so the provider accepts sync-only
 * columns and never marks a row `DIRTY`.
 *
 * Two rules that the provider enforces silently are the reason this class is as literal as it is:
 * a master row of a recurring event carries `DURATION` and no `DTEND` (otherwise the provider
 * drops the end and the event expands into zero-length instances), and `_SYNC_ID` has no unique
 * index and is unique per calendar only — de-duplication is this class's job.
 */
class CalendarMapper(private val context: Context) : ProviderMapper {

    override val authority: String = CalendarContract.AUTHORITY

    private val resolver: ContentResolver get() = context.contentResolver

    /**
     * The provider deletes every row of an account that is not registered in `AccountManager`, so
     * a write against an unregistered account is a data-loss bug rather than an error.
     */
    override fun assertAccountRegistered(account: Account) {
        val registered = AccountManager.get(context)
            .getAccountsByType(account.type)
            .any { it.name == account.name }
        check(registered) {
            "Account ${account.name} (${account.type}) is not registered; refusing to write rows " +
                "the calendar provider would delete."
        }
    }

    // ---------------------------------------------------------------- collection state

    override fun readState(account: Account, collection: DavCollection): CollectionState {
        val entry = readStateJson(account)?.optJSONObject(collection.id) ?: return CollectionState()
        return CollectionState(
            ctag = entry.stringOrNull(KEY_CTAG),
            syncToken = entry.stringOrNull(KEY_SYNC_TOKEN),
            supportsSyncCollection = entry.booleanOrNull(KEY_SUPPORTS_SYNC_COLLECTION),
            capabilityCheckedAt = entry.optLong(KEY_CAPABILITY_CHECKED_AT, 0L),
            lastSuccessAt = entry.optLong(KEY_LAST_SUCCESS_AT, 0L),
        )
    }

    override fun writeState(account: Account, collection: DavCollection, state: CollectionState) {
        val root = readStateJson(account) ?: JSONObject()
        root.put(
            collection.id,
            JSONObject().apply {
                state.ctag?.let { put(KEY_CTAG, it) }
                state.syncToken?.let { put(KEY_SYNC_TOKEN, it) }
                state.supportsSyncCollection?.let { put(KEY_SUPPORTS_SYNC_COLLECTION, it) }
                put(KEY_CAPABILITY_CHECKED_AT, state.capabilityCheckedAt)
                put(KEY_LAST_SUCCESS_AT, state.lastSuccessAt)
            },
        )
        // The provider's sync-state table holds one row per account, so every Collection's state
        // is multiplexed inside its single data blob. The row is replaced, not appended to.
        val values = ContentValues().apply {
            put(SyncState.ACCOUNT_NAME, account.name)
            put(SyncState.ACCOUNT_TYPE, account.type)
            put(SyncState.DATA, root.toString())
        }
        if (resolver.insert(decorated(SyncState.CONTENT_URI, account), values) == null) {
            Log.w(TAG, "Could not store sync state for ${account.name}")
        }
    }

    private fun readStateJson(account: Account): JSONObject? {
        val projection = arrayOf(SyncState.DATA)
        val selection = "${SyncState.ACCOUNT_NAME}=? AND ${SyncState.ACCOUNT_TYPE}=?"
        resolver.query(SyncState.CONTENT_URI, projection, selection, arrayOf(account.name, account.type), null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val data = cursor.getString(0) ?: return null
                return try {
                    JSONObject(data)
                } catch (e: Exception) {
                    // Someone else's format, or a truncated blob; starting over costs one full
                    // listing and never a row.
                    Log.w(TAG, "Ignoring unreadable sync state for ${account.name}", e)
                    null
                }
            }
        return null
    }

    // ---------------------------------------------------------------- items

    /**
     * Local items of this Collection, keyed by `_SYNC_ID`.
     *
     * The key is the engine's own item key (the href's absolute path) written through verbatim, so
     * the map is directly comparable with the listing and [deleteMissing] can never mistake a live
     * item for a removed one. Only master rows carry a `_SYNC_ID`; their overrides are reached
     * through `ORIGINAL_SYNC_ID` and are not items of their own.
     */
    override fun localItems(account: Account, collection: DavCollection): Map<String, String?> {
        val calendarId = findCalendarId(account, collection) ?: return emptyMap()
        val items = LinkedHashMap<String, String?>()
        val selection = "${Events.CALENDAR_ID}=? AND ${Events._SYNC_ID} IS NOT NULL"
        resolver.query(
            eventsUri(account),
            arrayOf(Events._SYNC_ID, Events.SYNC_DATA1),
            selection,
            arrayOf(calendarId.toString()),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                items.putIfAbsent(name, cursor.getString(1))
            }
        }
        return items
    }

    override fun upsert(
        account: Account,
        collection: DavCollection,
        resources: Map<String, String>,
        etags: Map<String, String?>,
    ): Int {
        if (resources.isEmpty()) return 0
        assertAccountRegistered(account)
        val calendarId = ensureCalendar(account, collection)
        // One query for the batch's rows rather than one per resource. A batch of fifty would
        // otherwise be fifty provider round trips to ask questions this Collection already answers,
        // and every one of them runs on the sync thread.
        val claimed = rowsClaiming(account, calendarId, resources.keys)
        // One batch for the whole upsert, committed once: the provider applies the operations in the
        // order they were appended, so a master still precedes the overrides that link to it by
        // ORIGINAL_SYNC_ID, and a batch of fifty resources stops being hundreds of transactions and
        // hundreds of provider notifications.
        val batch = ArrayList<ContentProviderOperation>()
        var rows = 0
        for ((name, body) in resources) {
            val resource = try {
                parseResource(body)
            } catch (e: Exception) {
                // One unparseable resource must not cost the rest of the batch.
                Log.w(TAG, "Skipping $name: not usable as iCalendar", e)
                continue
            }
            val existing = claimed[name].orEmpty()
            rows += ResourceWriter(account, calendarId, name, resource, etags[name], existing).write(batch)
        }
        // Failure is now the batch's, not one resource's: the provider applies it in one transaction,
        // so an operation it refuses leaves no row of this batch behind, and the exception reaches the
        // engine's per-Collection catch, which records the failure and offers the Collection again on
        // the next run. Skipping the one resource that failed is what the contacts mapper gave up for
        // this too.
        if (batch.isNotEmpty()) resolver.applyBatch(authority, batch)
        return rows
    }

    /**
     * Deletes rows whose resource is absent from [keepHrefs].
     *
     * The caller must only reach this with a listing that completed: an empty [keepHrefs] is a
     * legitimate "the Collection is now empty" and would otherwise wipe every event of the
     * calendar. An exception row is attributed to the resource named by its `ORIGINAL_SYNC_ID`;
     * a row with neither identity is deleted, since nothing can ever claim it again.
     */
    override fun deleteMissing(account: Account, collection: DavCollection, keepHrefs: Set<String>): Int {
        val calendarId = findCalendarId(account, collection) ?: return 0
        val doomed = ArrayList<Long>()
        val selection = "${Events.CALENDAR_ID}=?"
        resolver.query(
            eventsUri(account),
            arrayOf(Events._ID, Events._SYNC_ID, Events.ORIGINAL_SYNC_ID),
            selection,
            arrayOf(calendarId.toString()),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val name = cursor.getString(1) ?: cursor.getString(2)
                if (name == null || name !in keepHrefs) doomed.add(id)
            }
        }
        return deleteIds(account, doomed)
    }

    /**
     * Read-only backstop: a row an editor re-dirtied is cleared so the next run's server state
     * wins. `MUTATORS` goes with it — it names the packages that last touched the row.
     */
    override fun clearDirty(account: Account, collection: DavCollection) {
        val calendarId = findCalendarId(account, collection) ?: return
        val values = ContentValues().apply {
            put(Events.DIRTY, 0)
            putNull(Events.MUTATORS)
        }
        resolver.update(
            eventsUri(account),
            values,
            "${Events.CALENDAR_ID}=? AND ${Events.DIRTY}=1",
            arrayOf(calendarId.toString()),
        )
    }

    // ---------------------------------------------------------------- calendars row

    /**
     * The `Calendars` row a Collection's events hang off.
     *
     * `NAME` carries the Collection id, which is what makes the row findable again; the rest is
     * refreshed on every sync so a renamed or recoloured Collection follows the server.
     */
    private fun ensureCalendar(account: Account, collection: DavCollection): Long {
        val existing = findCalendarId(account, collection)
        val values = calendarValues(account, collection)
        if (existing != null) {
            resolver.update(
                decorated(CalendarContract.Calendars.CONTENT_URI, account),
                values,
                "${Calendars._ID}=?",
                arrayOf(existing.toString()),
            )
            return existing
        }
        val uri = resolver.insert(decorated(CalendarContract.Calendars.CONTENT_URI, account), values)
        val id = uri?.lastPathSegment?.toLongOrNull()
        checkNotNull(id) { "The calendar provider refused the Calendar row for ${collection.id}" }
        return id
    }

    /**
     * The rows claiming any of [names], keyed by the resource each one claims.
     *
     * Both identities at once: a resource's rows are its master, which carries `_SYNC_ID`, and its
     * overrides, which name the resource through `ORIGINAL_SYNC_ID` instead. A malformed row can
     * claim two names — it is listed under each, exactly as the per-resource query would have
     * returned it.
     */
    private fun rowsClaiming(
        account: Account,
        calendarId: Long,
        names: Collection<String>,
    ): Map<String, List<ExistingRow>> {
        if (names.isEmpty()) return emptyMap()
        val placeholders = names.joinToString(",") { "?" }
        val selection = "${Events.CALENDAR_ID}=? AND (${Events._SYNC_ID} IN ($placeholders) " +
            "OR ${Events.ORIGINAL_SYNC_ID} IN ($placeholders))"
        val args = (listOf(calendarId.toString()) + names + names).toTypedArray()
        val rows = ArrayList<ExistingRow>()
        resolver.query(
            eventsUri(account),
            arrayOf(Events._ID, Events._SYNC_ID, Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME, Events.ORIGINAL_ALL_DAY),
            selection,
            args,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                rows.add(
                    ExistingRow(
                        id = cursor.getLong(0),
                        syncId = cursor.getString(1),
                        originalSyncId = cursor.getString(2),
                        originalInstanceTime = if (cursor.isNull(3)) null else cursor.getLong(3),
                        originalAllDay = if (cursor.isNull(4)) false else cursor.getInt(4) != 0,
                    ),
                )
            }
        }
        return rowsByClaim(rows, names)
    }

    private fun findCalendarId(account: Account, collection: DavCollection): Long? {
        val selection = "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.NAME}=?"
        val args = arrayOf(account.name, account.type, collection.id)
        resolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(Calendars._ID), selection, args, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getLong(0)
            }
        return null
    }

    private fun calendarValues(account: Account, collection: DavCollection): ContentValues = ContentValues().apply {
        put(Calendars.ACCOUNT_NAME, account.name)
        put(Calendars.ACCOUNT_TYPE, account.type)
        put(Calendars.NAME, collection.id)
        put(Calendars.CALENDAR_DISPLAY_NAME, collection.displayName?.takeIf { it.isNotBlank() } ?: collection.id)
        put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_READ)
        put(Calendars.SYNC_EVENTS, 1)
        put(Calendars.VISIBLE, 1)
        put(Calendars.OWNER_ACCOUNT, account.name)
        // Nothing about the Collection names a zone; UTC is at least a zone the platform has, and
        // every all-day row this mapper writes is UTC anyway.
        put(Calendars.CALENDAR_TIME_ZONE, "UTC")
        // Written only when the Collection names one. A null Integer under this key is not "no
        // colour" to the provider, it is a value it cannot read: the row is stored with a null in a
        // column it unboxes on the way in, the insert throws, and the whole Collection fails as
        // though the server had answered badly. Leaving the key out is what falls back.
        collection.color?.let { put(Calendars.CALENDAR_COLOR, it) }
    }

    // ---------------------------------------------------------------- writes

    /** Appends one resource's operations to a batch: its master first, then its overrides. */
    private inner class ResourceWriter(
        private val account: Account,
        private val calendarId: Long,
        private val name: String,
        private val resource: ParsedResource,
        private val etag: String?,
        /** The rows claiming this resource, read once for the whole batch by [rowsClaiming]. */
        private val existing: List<ExistingRow>,
    ) {
        private val nowMillis = System.currentTimeMillis()
        private val fallbackZone: ZoneId = ZoneId.systemDefault()

        /**
         * Appends this resource's operations to [batch] and returns how many event rows it writes.
         *
         * The master is appended first on purpose: the provider links an override to it by looking
         * up ORIGINAL_SYNC_ID = this row's _SYNC_ID, and backfills ORIGINAL_ID for exceptions that
         * were already stored. An override written first stays unlinked.
         */
        fun write(batch: MutableList<ContentProviderOperation>): Int {
            if (resource.droppedUids.isNotEmpty()) {
                Log.w(TAG, "$name holds more than one UID; not written: ${resource.droppedUids.size} extra group(s)")
            }
            if (resource.droppedComponents > 0) {
                Log.w(TAG, "$name holds ${resource.droppedComponents} component(s) of the same UID that a single href cannot address")
            }
            if (resource.master == null) {
                Log.w(TAG, "$name holds no VEVENT")
                return 0
            }
            if (resource.master.unrepresentableDates > 0) {
                Log.w(TAG, "$name: ${resource.master.unrepresentableDates} RDATE/EXDATE entry(ies) had no storable form")
            }

            var written = 0

            val master = resource.master
            val masterTimes = timesOf(master) ?: run {
                Log.w(TAG, "Skipping $name: its master has no usable start time")
                return 0
            }
            // Every exception that can be placed in time, in the resource's own order. Which row each
            // of them continues is decided below, over the rows this batch read for the resource.
            val overrides = ArrayList<Pair<ParsedEvent, EventTimes>>()
            for (override in resource.overrides) {
                val times = timesOf(override) ?: run {
                    Log.w(TAG, "Skipping one exception of $name: unusable start time")
                    continue
                }
                overrides += override to times
            }
            val plan = rowPlan(
                existing = existing,
                name = name,
                occurrences = overrides.map { (_, times) ->
                    Occurrence(instanceTime = times.originalInstanceTime, allDay = times.originalAllDay == true)
                },
            )

            val masterRef: RowRef
            if (plan.masterId != null) {
                batch += eventUpdate(plan.masterId, eventValues(master, masterTimes))
                masterRef = RowRef.existing(plan.masterId)
            } else {
                val at = batch.size
                batch += eventInsert(eventValues(master, masterTimes))
                masterRef = RowRef.pending(at)
            }
            written++
            writeRelated(batch, masterRef, master, masterTimes)

            for ((at, component) in overrides.withIndex()) {
                val (event, times) = component
                val values = eventValues(event, times)
                val rowId = plan.overrideIds[at]
                val ref: RowRef
                if (rowId != null) {
                    batch += eventUpdate(rowId, values)
                    ref = RowRef.existing(rowId)
                } else {
                    val index = batch.size
                    batch += eventInsert(values)
                    ref = RowRef.pending(index)
                }
                written++
                writeRelated(batch, ref, event, times)
            }

            // Anything else claiming this resource — a duplicated master, an exception the server
            // has since dropped — is gone. `_SYNC_ID` has no unique index, so this is the only
            // thing keeping the resource name unique per calendar.
            appendDeletes(batch, account, plan.doomed)
            return written
        }

        private fun timesOf(event: ParsedEvent): EventTimes? {
            val start = event.start ?: return null
            val startChoice = resolveZone(start.tzid, resource.zones, fallbackZone, nowMillis)
            startChoice.note?.let { Log.i(TAG, "$name: $it") }
            val endChoice = event.end?.tzid?.let { tzid ->
                resolveZone(tzid, resource.zones, startChoice.zone, nowMillis).also { choice ->
                    choice.note?.let { Log.i(TAG, "$name: $it") }
                }
            }
            val recurrenceChoice = event.recurrenceId?.tzid?.let { tzid ->
                resolveZone(tzid, resource.zones, startChoice.zone, nowMillis).also { choice ->
                    choice.note?.let { Log.i(TAG, "$name: $it") }
                }
            }
            return eventTimes(
                shape = event.shape,
                start = start,
                end = event.end,
                recurrenceId = event.recurrenceId,
                explicitDuration = event.duration,
                startZone = startChoice.zone,
                endZone = endChoice?.zone,
                recurrenceIdZone = recurrenceChoice?.zone,
            )
        }

        private fun eventValues(event: ParsedEvent, times: EventTimes): ContentValues {
            val override = event.shape == EventShape.OVERRIDE
            // Multiple RRULE properties are newline-separated in the column.
            val rrule: String? = if (!override && event.rrules.isNotEmpty()) event.rrules.joinToString("\n") else null
            val rdate: String? = if (override) null else recurrenceListStorage(event.rdates, times.allDay)
            val exdate: String? = if (override) null else recurrenceListStorage(event.exdates, times.allDay)
            return ContentValues().apply {
                put(Events.CALENDAR_ID, calendarId)
                if (override) {
                    // An override is not a resource of its own: the name belongs to the master, and
                    // `_SYNC_ID` stays NULL. ORIGINAL_INSTANCE_TIME is what identifies which
                    // occurrence it replaces.
                    putNull(Events._SYNC_ID)
                    put(Events.ORIGINAL_SYNC_ID, name)
                    put(Events.ORIGINAL_INSTANCE_TIME, times.originalInstanceTime)
                    put(Events.ORIGINAL_ALL_DAY, if (times.originalAllDay == true) 1 else 0)
                } else {
                    put(Events._SYNC_ID, name)
                    putNull(Events.ORIGINAL_SYNC_ID)
                    putNull(Events.ORIGINAL_INSTANCE_TIME)
                    putNull(Events.ORIGINAL_ALL_DAY)
                }
                put(Events.UID_2445, event.uid)
                // The ETag belongs to the resource, so every component of it carries the same one.
                put(Events.SYNC_DATA1, etag)
                put(Events.DTSTART, times.dtStart)
                put(Events.DTEND, times.dtEnd)
                put(Events.DURATION, times.duration)
                put(Events.ALL_DAY, if (times.allDay) 1 else 0)
                put(Events.EVENT_TIMEZONE, times.eventTimeZone)
                put(Events.EVENT_END_TIMEZONE, times.eventEndTimeZone)
                put(Events.TITLE, event.summary)
                put(Events.DESCRIPTION, event.description)
                put(Events.EVENT_LOCATION, event.location)
                put(Events.RRULE, rrule)
                put(Events.RDATE, rdate)
                put(Events.EXDATE, exdate)
                put(Events.STATUS, event.status?.let { statusValue(it) })
                put(Events.AVAILABILITY, availabilityValue(event.transparency))
                // Read-only: the row is server state, never a local edit.
                put(Events.DIRTY, 0)
            }
        }

        /**
         * Re-applied rather than merged: the server's attendee list and alarms are the state.
         *
         * The deletes are for a row that already exists: they clear both lists by id. A row this
         * batch creates has nothing under it yet and no id for a selection to name, so its lists are
         * only written — every insert names its event through [withRowRef], which is a row id for a
         * row that exists and a back reference to the insert of one that does not.
         */
        private fun writeRelated(
            batch: MutableList<ContentProviderOperation>,
            eventRef: RowRef,
            event: ParsedEvent,
            times: EventTimes,
        ) {
            val remindersUri = decorated(Reminders.CONTENT_URI, account)
            val attendeesUri = decorated(Attendees.CONTENT_URI, account)
            eventRef.id?.let { eventId ->
                batch += ContentProviderOperation.newDelete(remindersUri)
                    .withSelection("${Reminders.EVENT_ID}=?", arrayOf(eventId.toString()))
                    .build()
                batch += ContentProviderOperation.newDelete(attendeesUri)
                    .withSelection("${Attendees.EVENT_ID}=?", arrayOf(eventId.toString()))
                    .build()
            }
            val durationSeconds = (times.end - times.dtStart) / 1000L
            for (reminder in event.reminders) {
                val minutes = reminderMinutes(reminder, times, durationSeconds) ?: continue
                val values = ContentValues().apply {
                    put(Reminders.MINUTES, minutes)
                    put(Reminders.METHOD, if (reminder.method == AlarmMethod.EMAIL) Reminders.METHOD_EMAIL else Reminders.METHOD_ALERT)
                }
                batch += ContentProviderOperation.newInsert(remindersUri)
                    .withValues(values)
                    .withRowRef(Reminders.EVENT_ID, eventRef)
                    .build()
            }
            for (attendee in event.attendees) {
                val values = ContentValues().apply {
                    put(Attendees.ATTENDEE_EMAIL, attendee.email)
                    put(Attendees.ATTENDEE_NAME, attendee.name)
                    put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ATTENDEE)
                    put(Attendees.ATTENDEE_TYPE, attendeeType(attendee))
                    put(Attendees.ATTENDEE_STATUS, attendeeStatus(attendee.partstat))
                }
                batch += ContentProviderOperation.newInsert(attendeesUri)
                    .withValues(values)
                    .withRowRef(Attendees.EVENT_ID, eventRef)
                    .build()
            }
        }

        private fun reminderMinutes(reminder: ParsedReminder, times: EventTimes, durationSeconds: Long): Int? {
            val raw = when {
                reminder.triggerSeconds != null ->
                    relativeTriggerMinutes(reminder.triggerSeconds, reminder.relatedToEnd, durationSeconds)

                reminder.triggerAt != null -> {
                    val zone = resolveZone(reminder.triggerAt.tzid, resource.zones, fallbackZone, nowMillis).zone
                    val at = epochMillis(reminder.triggerAt, zone) ?: return null
                    (times.dtStart - at) / 60000L
                }

                else -> return null
            }
            if (raw < 0) {
                Log.i(TAG, "$name: a reminder at or after the event start was clamped to 0 minutes")
            }
            return raw.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        }

        private fun eventInsert(values: ContentValues): ContentProviderOperation =
            ContentProviderOperation.newInsert(eventsUri(account)).withValues(values).build()

        private fun eventUpdate(id: Long, values: ContentValues): ContentProviderOperation =
            ContentProviderOperation.newUpdate(eventsUri(account))
                .withSelection("${Events._ID}=?", arrayOf(id.toString()))
                .withValues(values)
                .build()
    }

    /**
     * The value for [column]: a row id, or a reference to the result of an earlier operation in the
     * same batch — the only way to write a child of a row the batch has not created yet.
     */
    private fun ContentProviderOperation.Builder.withRowRef(column: String, ref: RowRef): ContentProviderOperation.Builder {
        val id = ref.id
        if (id != null) return withValue(column, id)
        val index = ref.batchIndex
        checkNotNull(index) { "row reference has neither an id nor a batch index" }
        return withValueBackReference(column, index)
    }

    /**
     * A row the batch is about to create, or one that already exists — exactly one of the two,
     * because which of them it is decides whether a value is a row id or a back reference.
     */
    private class RowRef private constructor(val id: Long?, val batchIndex: Int?) {
        companion object {
            fun existing(id: Long): RowRef = RowRef(id, null)
            fun pending(batchIndex: Int): RowRef = RowRef(null, batchIndex)
        }
    }

    internal data class ExistingRow(
        val id: Long,
        val syncId: String?,
        val originalSyncId: String?,
        val originalInstanceTime: Long?,
        val originalAllDay: Boolean,
    )

    /**
     * Deletes rows by id as its own transaction.
     *
     * Only [deleteMissing] reaches this: it runs outside any batch and has to remove what the server
     * no longer lists. The write path appends the same deletes with [appendDeletes] instead, so a
     * resource's row and the rows it no longer claims go in one commit.
     */
    private fun deleteIds(account: Account, ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        var deleted = 0
        for (chunk in ids.chunked(ID_CHUNK)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val args = chunk.map { it.toString() }.toTypedArray()
            deleted += resolver.delete(eventsUri(account), "${Events._ID} IN ($placeholders)", args)
        }
        return deleted
    }

    /** The deletes of [deleteIds], appended to a batch instead of run. */
    private fun appendDeletes(batch: MutableList<ContentProviderOperation>, account: Account, ids: List<Long>) {
        for (chunk in ids.chunked(ID_CHUNK)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val args = chunk.map { it.toString() }.toTypedArray()
            batch += ContentProviderOperation.newDelete(eventsUri(account))
                .withSelection("${Events._ID} IN ($placeholders)", args)
                .build()
        }
    }

    // ---------------------------------------------------------------- URIs

    /**
     * Every write carries the sync-adapter flag and the account the rows belong to; the provider
     * rejects a sync-adapter write that does not name both.
     */
    private fun decorated(base: Uri, account: Account): Uri = base.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, account.name)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, account.type)
        .build()

    private fun eventsUri(account: Account): Uri = decorated(Events.CONTENT_URI, account)

    private companion object {
        const val KEY_CTAG = "ctag"
        const val KEY_SYNC_TOKEN = "syncToken"
        const val KEY_SUPPORTS_SYNC_COLLECTION = "supportsSyncCollection"
        const val KEY_CAPABILITY_CHECKED_AT = "capabilityCheckedAt"
        const val KEY_LAST_SUCCESS_AT = "lastSuccessAt"
    }
}

private fun statusValue(status: EventStatus): Int = when (status) {
    EventStatus.TENTATIVE -> Events.STATUS_TENTATIVE
    EventStatus.CONFIRMED -> Events.STATUS_CONFIRMED
    EventStatus.CANCELLED -> Events.STATUS_CANCELED
}

/** A missing `TRANSP` means `OPAQUE` per RFC 5545, which is "busy". */
private fun availabilityValue(transparency: Transparency?): Int = when (transparency) {
    Transparency.TRANSPARENT -> Events.AVAILABILITY_FREE
    else -> Events.AVAILABILITY_BUSY
}

private fun attendeeType(attendee: ParsedAttendee): Int = when {
    attendee.cutype == "ROOM" || attendee.cutype == "RESOURCE" -> Attendees.TYPE_RESOURCE
    attendee.role == "OPT-PARTICIPANT" -> Attendees.TYPE_OPTIONAL
    attendee.role == "NON-PARTICIPANT" -> Attendees.TYPE_NONE
    else -> Attendees.TYPE_REQUIRED
}

/** `NEEDS-ACTION` is the RFC 5545 default, which the provider calls "invited". */
private fun attendeeStatus(partstat: String?): Int = when (partstat) {
    "ACCEPTED" -> Attendees.ATTENDEE_STATUS_ACCEPTED
    "DECLINED" -> Attendees.ATTENDEE_STATUS_DECLINED
    "TENTATIVE" -> Attendees.ATTENDEE_STATUS_TENTATIVE
    else -> Attendees.ATTENDEE_STATUS_INVITED
}

private fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

private fun JSONObject.booleanOrNull(key: String): Boolean? =
    if (!has(key) || isNull(key)) null else optBoolean(key)

/**
 * The rows claiming one of [names], keyed by the name each claims, each list in row order.
 *
 * The grouping that makes one query per batch enough: [CalendarMapper.rowsClaiming] reads every row
 * the batch could touch in a single pass, and this puts each where its writer will look for it.
 *
 * Both identities at once, because a resource's rows are its master, which carries `_SYNC_ID`, and
 * its overrides, which name the resource through `ORIGINAL_SYNC_ID` instead. A malformed row can
 * claim two names — it appears under each, exactly as a per-resource query would have returned it —
 * and a row claiming neither is left out, which is what keeps a row matched only by a shared
 * `ORIGINAL_SYNC_ID` from being handed to a writer it does not belong to.
 */
internal fun rowsByClaim(
    rows: List<CalendarMapper.ExistingRow>,
    names: Collection<String>,
): Map<String, List<CalendarMapper.ExistingRow>> {
    val claimed = HashMap<String, MutableList<CalendarMapper.ExistingRow>>()
    for (row in rows) {
        row.syncId?.takeIf { it in names }?.let { claimed.getOrPut(it) { ArrayList() }.add(row) }
        row.originalSyncId?.takeIf { it in names }?.let { claimed.getOrPut(it) { ArrayList() }.add(row) }
    }
    return claimed.mapValues { (_, claiming) -> claiming.sortedBy { it.id } }
}

/** An exception's identity as the provider stores it: the occurrence it replaces. */
internal data class Occurrence(val instanceTime: Long?, val allDay: Boolean)

/**
 * Which existing row each component of one resource continues, and which rows nothing claims.
 *
 * Read-only over rows a query already returned, because the rules it holds are the ones that decide
 * whether a resource keeps its rows or is written again from scratch — and the only ones in the
 * write path that can be answered without a provider.
 *
 * The master is the first row carrying the resource's `_SYNC_ID`. `_SYNC_ID` has no unique index, so
 * a row can claim the same resource twice; everything past the first is doomed rather than written
 * over twice. An exception matches on three things together — the resource, the occurrence it
 * replaces, and that occurrence's all-day flag — because `ORIGINAL_SYNC_ID` alone is shared by every
 * exception of the resource and by nothing else. A row one component has claimed is never claimed
 * again, which is what keeps a reused row out of [doomed].
 */
internal data class RowPlan(
    /** The row the master continues, or null when the master has to be inserted. */
    val masterId: Long?,
    /** One entry per exception, in order: the row it continues, or null when it has to be inserted. */
    val overrideIds: List<Long?>,
    /** The rows claiming the resource that no component continues; they are deleted. */
    val doomed: List<Long>,
)

internal fun rowPlan(
    existing: List<CalendarMapper.ExistingRow>,
    name: String,
    occurrences: List<Occurrence>,
): RowPlan {
    val claimed = HashSet<Long>()
    val masterId = existing.firstOrNull { it.syncId == name }?.also { claimed.add(it.id) }?.id
    val overrideIds = occurrences.map { occurrence ->
        existing.firstOrNull {
            it.id !in claimed &&
                it.originalSyncId == name &&
                it.originalInstanceTime == occurrence.instanceTime &&
                it.originalAllDay == occurrence.allDay
        }?.also { claimed.add(it.id) }?.id
    }
    return RowPlan(
        masterId = masterId,
        overrideIds = overrideIds,
        doomed = existing.filter { it.id !in claimed }.map { it.id },
    )
}
