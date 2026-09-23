package app.davkeep.provider.calendar

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.OperationApplicationException
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.provider.CalendarContract.SyncState
import android.util.Log
import org.json.JSONObject
import app.davkeep.core.ChangeKind
import app.davkeep.core.CollectionState
import app.davkeep.core.DavCollection
import app.davkeep.core.LocalChange
import app.davkeep.core.ProviderMapper
import app.davkeep.core.UploadBody
import java.time.ZoneId
import java.util.UUID

private const val TAG = "CalendarMapper"

/** SQLite stops accepting host parameters well before this; ids are deleted in chunks. */
private const val ID_CHUNK = 500

/**
 * `Events.DIRTY` while this app holds the resource's bytes in an upload, written before they are
 * read.
 *
 * The calendar provider keeps no version column, so the only thing that can tell a row which moved
 * under a request from one which did not is what an editor writes: every editor path in
 * `CalendarProvider2` puts the literal 1 into `DIRTY` and none of them reads the column first, so a
 * row that is no longer 2 is a row an editor touched after this app armed it. The value costs
 * nothing elsewhere — every reader here asks whether the flag is non-zero, and the provider reacts
 * to a sync adapter's flag only when that flag is 0.
 */
private const val IN_FLIGHT = 2

/**
 * Writes CalendarDAO resources into `com.android.calendar`, and reads a locally edited one back out
 * as the bytes an upload sends.
 *
 * Every write goes through a `CALLER_IS_SYNCADAPTER` URI, so the provider accepts sync-only columns
 * and does not mark a row `DIRTY` for what this app itself writes. A row's flag is cleared in
 * exactly one place — [markUploaded], after the server answered — and a mapper here that wrote zero
 * onto a row nobody uploaded would throw the edit away.
 *
 * Two rules that the provider enforces silently are the reason this class is as literal as it is:
 * a master row of a recurring event carries `DURATION` and no `DTEND` (otherwise the provider
 * drops the end and the event expands into zero-length instances), and `_SYNC_ID` has no unique
 * index and is unique per calendar only — de-duplication is this class's job.
 *
 * The bytes themselves are [CalendarSerialize]'s, which is free of Android types; this class only
 * reads a cursor into the snapshot it takes and writes the server's answer back onto the rows.
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
     *
     * A tombstone is not an item: the user deleted it, and a listing that still names its href must
     * not talk this mapper into writing the server's copy back over the deletion.
     *
     * A row whose stored text is missing — every row an earlier build wrote — is returned with a
     * null ETag, so the engine refetches it once and the text is there from then on. A resource that
     * was *too large* to keep is the exception: it keeps its ETag, because refetching it every run
     * would never make it patchable.
     */
    override fun localItems(account: Account, collection: DavCollection): Map<String, String?> {
        val calendarId = findCalendarId(account, collection) ?: return emptyMap()
        val items = LinkedHashMap<String, String?>()
        val selection = "${Events.CALENDAR_ID}=? AND ${Events._SYNC_ID} IS NOT NULL AND ${Events.DELETED}=0"
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
        // Asked of the column's nullness rather than of the column: a projection that carried every
        // resource's text through a CursorWindow would be tens of megabytes of a large calendar.
        val withoutText = "${Events.CALENDAR_ID}=? AND ${Events._SYNC_ID} IS NOT NULL AND ${Events.DELETED}=0 " +
            "AND ${Events.SYNC_DATA2} IS NULL AND (${Events.SYNC_DATA3} IS NULL OR ${Events.SYNC_DATA3}<>?)"
        resolver.query(
            eventsUri(account),
            arrayOf(Events._SYNC_ID),
            withoutText,
            arrayOf(calendarId.toString(), SOURCE_OVERSIZE),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0) ?: continue
                if (items.containsKey(name)) items[name] = null
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
        class Built(val ops: ArrayList<ContentProviderOperation>, val rows: Int, val kept: Int)
        // One group's operations, built against one reading of the rows they claim. Built as a unit
        // because a batch the provider refuses has to be built a second time from the same names.
        fun build(names: List<String>, claiming: Claimed): Built {
            val ops = ArrayList<ContentProviderOperation>()
            var written = 0
            var blocked = 0
            for (name in names) {
                val body = resources.getValue(name)
                if (name in claiming.blocked) {
                    blocked++
                    continue
                }
                val resource = try {
                    parseResource(body)
                } catch (e: Exception) {
                    // One unparseable resource must not cost the rest of the batch.
                    Log.w(TAG, "Skipping $name: not usable as iCalendar", e)
                    continue
                }
                val existing = claiming.rows[name].orEmpty()
                written += ResourceWriter(account, calendarId, name, body, resource, etags[name], existing)
                    .write(ops)
            }
            return Built(ops, written, blocked)
        }
        // Resources are grouped whenever the text already queued would pass the byte budget: the
        // provider applies a batch in the order it was appended — a master still precedes the
        // overrides that link to it by ORIGINAL_SYNC_ID — and a resource's rows, related rows and
        // source still commit together, because a group boundary is always a resource boundary.
        // Without the budget a batch of fifty large resources would be one Binder transaction of
        // tens of megabytes.
        var kept = 0
        val groups = ArrayList<List<String>>()
        var group = ArrayList<String>()
        var queuedBytes = 0
        for ((name, body) in resources) {
            if (name in claimed.blocked) {
                kept++
                continue
            }
            val bytes = utf8Length(body)
            if (queuedBytes + bytes > SOURCE_CAP_BYTES && group.isNotEmpty()) {
                groups += group
                group = ArrayList()
                queuedBytes = 0
            }
            group += name
            queuedBytes += bytes
        }
        if (group.isNotEmpty()) groups += group

        var rows = 0
        for (names in groups) {
            var built = build(names, claimed)
            if (built.ops.isNotEmpty()) {
                try {
                    resolver.applyBatch(authority, built.ops)
                } catch (e: OperationApplicationException) {
                    // An editor changed one of these rows between the query above and this commit,
                    // and the guard on the operation that claimed otherwise refused it. The batch is
                    // one transaction, so nothing of it is on disk; building it again against the
                    // rows as they are now is what keeps one keystroke from costing the other forty
                    // nine resources, and the row that moved is blocked this time and keeps its
                    // edit. A second refusal reaches the engine's per-Collection catch, which
                    // records the failure and offers the Collection again on the next run.
                    Log.i(TAG, "${collection.id}: a row moved under a batch of ${names.size}; writing it again")
                    built = build(names, rowsClaiming(account, calendarId, names))
                    if (built.ops.isNotEmpty()) resolver.applyBatch(authority, built.ops)
                }
            }
            rows += built.rows
            kept += built.kept
        }
        if (kept > 0) {
            Log.i(TAG, "${collection.id}: $kept resource(s) hold a local edit and were not written over")
        }
        return rows
    }

    /**
     * Deletes rows whose resource is absent from [keepHrefs].
     *
     * The caller must only reach this with a listing that completed: an empty [keepHrefs] is a
     * legitimate "the Collection is now empty" and would otherwise wipe every event of the
     * calendar. An exception row is attributed to the resource named by its `ORIGINAL_SYNC_ID`;
     * a row with neither identity is deleted, since nothing can ever claim it again — and a row
     * that is dirty or a tombstone is never deleted at all, which [doomedRows] decides.
     */
    override fun deleteMissing(account: Account, collection: DavCollection, keepHrefs: Set<String>): Int {
        val calendarId = findCalendarId(account, collection) ?: return 0
        val rows = ArrayList<ExistingRow>()
        val selection = "${Events.CALENDAR_ID}=?"
        resolver.query(
            eventsUri(account),
            arrayOf(Events._ID, Events._SYNC_ID, Events.ORIGINAL_SYNC_ID, Events.DIRTY, Events.DELETED),
            selection,
            arrayOf(calendarId.toString()),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                rows += ExistingRow(
                    id = cursor.getLong(0),
                    syncId = cursor.getString(1),
                    originalSyncId = cursor.getString(2),
                    dirty = cursor.getInt(3) != 0,
                    deleted = cursor.getInt(4) != 0,
                )
            }
        }
        return deleteIds(account, doomedRows(rows, keepHrefs))
    }

    // ---------------------------------------------------------------- upload

    /**
     * The `Calendars` row, refreshed for every Collection of every run.
     *
     * The access level is how a read-only Collection is expressed to the stock Calendar app, which
     * greys out editing and stops offering the calendar in "create event" — and a Collection whose
     * CTag never moves would otherwise never learn that the user changed the setting.
     */
    override fun ensureCollection(account: Account, collection: DavCollection) {
        assertAccountRegistered(account)
        ensureCalendar(account, collection)
    }

    /**
     * The rows of this Collection that await upload.
     *
     * Every row is read, not only the dirty ones: an edited override dirties its own row and the
     * master is what gets queued for it, so the master's own flags are the wrong question to ask.
     */
    override fun pendingChanges(account: Account, collection: DavCollection): List<LocalChange> {
        val calendarId = findCalendarId(account, collection) ?: return emptyList()
        val rows = ArrayList<QueueRow>()
        val selection = "${Events.CALENDAR_ID}=?"
        resolver.query(
            eventsUri(account),
            arrayOf(
                Events._ID,
                Events._SYNC_ID,
                Events.ORIGINAL_SYNC_ID,
                Events.ORIGINAL_ID,
                Events.DIRTY,
                Events.DELETED,
                Events.SYNC_DATA1,
                Events.UID_2445,
            ),
            selection,
            arrayOf(calendarId.toString()),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                rows += QueueRow(
                    id = cursor.getLong(0),
                    syncId = cursor.getString(1),
                    originalSyncId = cursor.getString(2),
                    originalId = if (cursor.isNull(3)) null else cursor.getLong(3),
                    dirty = cursor.getInt(4) != 0,
                    deleted = cursor.getInt(5) != 0,
                    etag = cursor.getString(6),
                    uid = cursor.getString(7),
                )
            }
        }
        return pendingPlan(rows)
    }

    /**
     * The resource's text, or null when it cannot be produced.
     *
     * A deletion returns an empty body rather than null: null means "these bytes cannot be produced"
     * and counts as pending, while a `DELETE` needs no bytes at all.
     *
     * The resource's rows are marked [IN_FLIGHT] before they are read, which is what later lets an
     * edit that arrived while the request was out be told apart from the one being sent.
     */
    override fun serialize(account: Account, collection: DavCollection, change: LocalChange): UploadBody? {
        if (change.kind == ChangeKind.DELETE) return UploadBody("", change.uid.orEmpty())
        assertAccountRegistered(account)
        val calendarId = findCalendarId(account, collection) ?: return null
        // A create mints once and keeps the name; an update must never mint, because a resource's
        // UID is the server's and a second one in the body is a second event.
        val uid = when {
            change.uid != null -> change.uid
            change.kind == ChangeKind.CREATE -> mintUid(account, change.rowId) ?: return null
            else -> {
                Log.w(TAG, "Event ${change.rowId} has no UID; not uploading it")
                return null
            }
        }
        // Armed before the bytes are read, so that an edit which lands while the request is in
        // flight is visible afterwards as a row that is no longer [IN_FLIGHT]. Every live row of
        // the resource is armed and not only the dirty ones: an edit to a row that was clean when
        // the body was read is just as unsent as one to a row that was not.
        val armed = resolver.update(
            eventsUri(account),
            ContentValues().apply { put(Events.DIRTY, IN_FLIGHT) },
            "${resourceSelection(calendarId, change)} AND ${Events.DELETED}=0",
            resourceArgs(calendarId, change),
        )
        if (armed == 0) {
            // Nothing of the resource is live any more: the row was deleted or became a tombstone
            // between the queue being read and here, and there are no bytes to produce for it.
            Log.i(TAG, "Event ${change.rowId} has no live row left; not uploading it")
            return null
        }
        val rows = readResourceRows(account, calendarId, change, uid) ?: return null
        val name = change.key ?: "a new event"
        return when (val serialized = serializeResource(rows, System.currentTimeMillis(), ZoneId.systemDefault())) {
            is Serialized.Written -> {
                serialized.notes.forEach { Log.i(TAG, "$name: $it") }
                UploadBody(serialized.text, uid)
            }

            is Serialized.Held -> {
                Log.i(TAG, "Not uploading $name: ${serialized.reason}")
                null
            }
        }
    }

    /**
     * Stores what the server accepted: the master's identity and ETag first, then the flags.
     *
     * The identity write is unguarded and on its own, because it is true whether or not the row
     * moved — the server does hold these bytes under this name and ETag, and the next `If-Match`
     * and the next patch base are read back off the row. A master that became a tombstone while the
     * request was in flight needs the new ETag for exactly the `DELETE` it sends next run.
     *
     * The flags are cleared only where the [IN_FLIGHT] sentinel [serialize] armed the resource with
     * is still there. Every editor path in the provider writes the literal 1, so a row that is no
     * longer armed is one an editor touched during the request, and it keeps its flag and its edit;
     * an override the editor *inserted* in flight was never armed and is never cleared either.
     * Writing `DIRTY=0` through a sync-adapter URI is also what makes the provider null `MUTATORS`,
     * so the value written there is literally 0 and never the sentinel.
     */
    override fun markUploaded(
        account: Account,
        collection: DavCollection,
        change: LocalChange,
        key: String,
        uid: String,
        etag: String?,
        body: String,
    ): Boolean {
        if (change.kind == ChangeKind.DELETE) return false
        assertAccountRegistered(account)
        val calendarId = findCalendarId(account, collection) ?: return false
        val identity = ContentValues().apply {
            put(Events._SYNC_ID, key)
            put(Events.UID_2445, uid)
            put(Events.SYNC_DATA1, etag)
            putSource(this, body)
        }
        resolver.update(
            eventsUri(account),
            identity,
            "${Events._ID}=?",
            arrayOf(change.rowId.toString()),
        )
        val master = ContentValues().apply {
            put(Events.DIRTY, 0)
            putNull(Events.MUTATORS)
        }
        val overrides = ContentValues().apply {
            put(Events.ORIGINAL_SYNC_ID, key)
            put(Events.SYNC_DATA1, etag)
            putNull(Events.SYNC_DATA2)
            putNull(Events.SYNC_DATA3)
            put(Events.DIRTY, 0)
            putNull(Events.MUTATORS)
        }
        resolver.applyBatch(
            authority,
            arrayListOf(
                ContentProviderOperation.newUpdate(eventsUri(account))
                    .withSelection(
                        "${Events._ID}=? AND ${Events.DIRTY}=$IN_FLIGHT AND ${Events.DELETED}=0",
                        arrayOf(change.rowId.toString()),
                    )
                    .withValues(master)
                    .build(),
                ContentProviderOperation.newUpdate(eventsUri(account))
                    // The master is excluded by row id, not by hoping the selection misses it.
                    // [resourceSelection] matches the whole resource — master included — and these
                    // values are the overrides': they null the stored source and set
                    // ORIGINAL_SYNC_ID. Applied to the master they erase the base every later patch
                    // starts from and make it look like an override to [pendingPlan], which then
                    // never queues it again. It survived testing only because the same run's
                    // listing usually re-fetched the resource and wrote the rows back.
                    .withSelection(
                        "${resourceSelection(calendarId, change)} AND ${Events._ID}!=? " +
                            "AND ${Events.DIRTY}=$IN_FLIGHT AND ${Events.DELETED}=0",
                        resourceArgs(calendarId, change) + change.rowId.toString(),
                    )
                    .withValues(overrides)
                    .build(),
            ),
        )
        val pending = stillPending(account, calendarId, change)
        if (pending) {
            Log.i(TAG, "Row ${change.rowId} moved while its upload was in flight; it stays pending")
        }
        return !pending
    }

    /**
     * Deletes a tombstone the server has accepted, and everything hanging off it.
     *
     * The provider deletes an event's reminders and attendees with the row, but a master's
     * exceptions are its own rows: `ORIGINAL_ID` names the master and is what the provider links them
     * by, and `ORIGINAL_SYNC_ID` is the identity this app writes, so both are matched — a delete of
     * the master alone would leave its overrides behind as events nothing describes.
     */
    override fun purgeDeleted(account: Account, collection: DavCollection, change: LocalChange) {
        assertAccountRegistered(account)
        val calendarId = findCalendarId(account, collection) ?: return
        resolver.delete(
            eventsUri(account),
            resourceSelection(calendarId, change),
            resourceArgs(calendarId, change),
        )
    }

    /**
     * Gives up a local edit: the server's version wins, so the row stops claiming to be current and
     * the same run's listing fetches the server's copy onto it — a null ETag never matches a listing.
     *
     * A [LocalChange.key] is adopted onto the master, which is what a create the server answered
     * `412` for needs: the name is taken, so it is no longer a create.
     *
     * What proves the row is the one the answer is about differs with [sent], because the two
     * callers stand in different places. A change that was sent was armed by [serialize], so an
     * edit made while the server was saying no shows as a row that is no longer [IN_FLIGHT] and is
     * left exactly as the editor left it: it is newer than the refusal and meets the same answer
     * next run. A change that was refused was never sent and never armed, and there was no window
     * to arm against — this batch is one provider transaction, so its selections are evaluated with
     * no editor write able to land between them and the clear, and any dirty row of the resource is
     * the edit the refusal is about. A tombstone is armed in neither case: it is invisible to the
     * editor and nothing can change it.
     *
     * @return false when any row of the resource still holds an unsent edit, in which case the
     * conflict has not been resolved and the caller must keep the resource out of this run's fetch.
     */
    override fun revertLocalChange(
        account: Account,
        collection: DavCollection,
        change: LocalChange,
        sent: Boolean,
    ): Boolean {
        assertAccountRegistered(account)
        val calendarId = findCalendarId(account, collection) ?: return false
        val guard = if (sent && change.kind != ChangeKind.DELETE) {
            "${Events.DIRTY}=$IN_FLIGHT"
        } else {
            "${Events.DIRTY}<>0"
        }
        val values = ContentValues().apply {
            change.key?.let { put(Events._SYNC_ID, it) }
            put(Events.DELETED, 0)
            put(Events.DIRTY, 0)
            putNull(Events.MUTATORS)
            putNull(Events.SYNC_DATA1)
        }
        val overrides = ContentValues().apply {
            change.key?.let { put(Events.ORIGINAL_SYNC_ID, it) }
            put(Events.DELETED, 0)
            put(Events.DIRTY, 0)
            putNull(Events.MUTATORS)
            putNull(Events.SYNC_DATA1)
        }
        resolver.applyBatch(
            authority,
            arrayListOf(
                ContentProviderOperation.newUpdate(eventsUri(account))
                    .withSelection("${Events._ID}=? AND $guard", arrayOf(change.rowId.toString()))
                    .withValues(values)
                    .build(),
                // The master is matched by [resourceSelection] too, and the operation above has
                // just cleared its flag, so the guard is also what keeps the overrides' values —
                // ORIGINAL_SYNC_ID among them — off the row that carries the resource's name.
                ContentProviderOperation.newUpdate(eventsUri(account))
                    .withSelection(
                        "${resourceSelection(calendarId, change)} AND $guard",
                        resourceArgs(calendarId, change),
                    )
                    .withValues(overrides)
                    .build(),
            ),
        )
        return !stillPending(account, calendarId, change)
    }

    /**
     * Whether any row of the resource still holds an edit no run has sent.
     *
     * Asked after the answer has been written, and it is the answer's own return value: a row an
     * editor touched while the request was in flight was not cleared, nor was an override it
     * inserted, which the arm in [serialize] never saw, and either leaves the resource for the next
     * run. A query the provider could not answer counts as pending, which costs one more upload and
     * never an edit.
     */
    private fun stillPending(account: Account, calendarId: Long, change: LocalChange): Boolean {
        val selection = "${resourceSelection(calendarId, change)} AND " +
            "(${Events.DIRTY}<>0 OR ${Events.DELETED}=1)"
        resolver.query(
            eventsUri(account),
            arrayOf(Events._ID),
            selection,
            resourceArgs(calendarId, change),
            null,
        )?.use { cursor -> return cursor.count > 0 }
        return true
    }

    /** The master row plus every row claiming the resource, by either identity the provider links by. */
    private fun resourceSelection(calendarId: Long, change: LocalChange): String {
        val key = change.key
        return "${Events.CALENDAR_ID}=? AND (${Events._ID}=? OR ${Events.ORIGINAL_ID}=?" +
            (if (key == null) "" else " OR ${Events.ORIGINAL_SYNC_ID}=?") + ")"
    }

    private fun resourceArgs(calendarId: Long, change: LocalChange): Array<String> {
        val args = arrayListOf(calendarId.toString(), change.rowId.toString(), change.rowId.toString())
        change.key?.let { args += it }
        return args.toTypedArray()
    }

    /**
     * A UID for an event the phone created, stored before it is ever sent.
     *
     * The one realistic answer that says "this name is taken" is a previous `PUT` the server
     * committed and whose answer was lost, and a retry under a fresh name would duplicate the event.
     * So the name is minted once, here, and every later attempt reads it back from the row.
     */
    private fun mintUid(account: Account, rowId: Long): String? {
        val uid = UUID.randomUUID().toString()
        val stored = resolver.update(
            eventsUri(account),
            ContentValues().apply { put(Events.UID_2445, uid) },
            "${Events._ID}=?",
            arrayOf(rowId.toString()),
        )
        if (stored == 0) {
            Log.w(TAG, "Could not store a UID on event $rowId")
            return null
        }
        return uid
    }

    /**
     * One resource's rows, master first, with the server's text from the master row.
     *
     * Two queries, both scoped to the resource the lifecycle has already found pending. The text is
     * read here and nowhere else: a Collection-wide query that projected `SYNC_DATA2` would drag
     * every resource's bytes through a CursorWindow.
     */
    private fun readResourceRows(
        account: Account,
        calendarId: Long,
        change: LocalChange,
        uid: String,
    ): ResourceRows? {
        val id = change.rowId.toString()
        val selection = "${Events.CALENDAR_ID}=? AND (${Events._ID}=? OR ${Events.ORIGINAL_ID}=?" +
            (if (change.key == null) "" else " OR ${Events.ORIGINAL_SYNC_ID}=?") + ")"
        val args = arrayListOf(calendarId.toString(), id, id)
        change.key?.let { args += it }
        val rows = ArrayList<EventRow>()
        var source: String? = null
        var oversize = false
        resolver.query(eventsUri(account), RESOURCE_PROJECTION, selection, args.toTypedArray(), null)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val rowId = cursor.getLong(cursor.getColumnIndexOrThrow(Events._ID))
                    if (rowId == change.rowId) {
                        source = cursor.getString(cursor.getColumnIndexOrThrow(Events.SYNC_DATA2))
                        oversize = cursor.getString(cursor.getColumnIndexOrThrow(Events.SYNC_DATA3)) == SOURCE_OVERSIZE
                    }
                    rows += EventRow(
                        id = rowId,
                        originalInstanceTime = cursor.longOrNull(Events.ORIGINAL_INSTANCE_TIME),
                        originalAllDay = cursor.flag(Events.ORIGINAL_ALL_DAY),
                        deleted = cursor.flag(Events.DELETED),
                        values = EventValues(
                            dtStart = cursor.longOrNull(Events.DTSTART),
                            dtEnd = cursor.longOrNull(Events.DTEND),
                            duration = cursor.getString(cursor.getColumnIndexOrThrow(Events.DURATION)),
                            allDay = cursor.flag(Events.ALL_DAY),
                            eventTimeZone = cursor.getString(cursor.getColumnIndexOrThrow(Events.EVENT_TIMEZONE)),
                            eventEndTimeZone = cursor.getString(cursor.getColumnIndexOrThrow(Events.EVENT_END_TIMEZONE)),
                            title = cursor.getString(cursor.getColumnIndexOrThrow(Events.TITLE)),
                            description = cursor.getString(cursor.getColumnIndexOrThrow(Events.DESCRIPTION)),
                            location = cursor.getString(cursor.getColumnIndexOrThrow(Events.EVENT_LOCATION)),
                            rrule = cursor.getString(cursor.getColumnIndexOrThrow(Events.RRULE)),
                            rdate = cursor.getString(cursor.getColumnIndexOrThrow(Events.RDATE)),
                            exdate = cursor.getString(cursor.getColumnIndexOrThrow(Events.EXDATE)),
                            status = statusOf(cursor.intOrNull(Events.STATUS)),
                            transparency = if (cursor.intOrNull(Events.AVAILABILITY) == Events.AVAILABILITY_FREE) {
                                Transparency.TRANSPARENT
                            } else {
                                Transparency.OPAQUE
                            },
                        ),
                        reminders = emptyList(),
                    )
                }
            }
        val master = rows.firstOrNull { it.id == change.rowId } ?: return null
        val reminders = readReminders(account, rows.map { it.id })
        return ResourceRows(
            key = change.key,
            uid = uid,
            source = source,
            oversize = oversize,
            master = master.copy(reminders = reminders[master.id].orEmpty()),
            overrides = rows.filter { it.id != change.rowId }.sortedBy { it.id }
                .map { it.copy(reminders = reminders[it.id].orEmpty()) },
        )
    }

    /** The reminders of a handful of rows, keyed by event id; the reminder list is part of the resource. */
    private fun readReminders(account: Account, eventIds: List<Long>): Map<Long, List<ReminderRow>> {
        if (eventIds.isEmpty()) return emptyMap()
        val placeholders = eventIds.joinToString(",") { "?" }
        val reminders = HashMap<Long, MutableList<ReminderRow>>()
        resolver.query(
            decorated(Reminders.CONTENT_URI, account),
            arrayOf(Reminders.EVENT_ID, Reminders.MINUTES, Reminders.METHOD),
            "${Reminders.EVENT_ID} IN ($placeholders) AND ${Reminders.MINUTES} IS NOT NULL",
            eventIds.map { it.toString() }.toTypedArray(),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val eventId = cursor.getLong(0)
                val method = if (!cursor.isNull(2) && cursor.getInt(2) == Reminders.METHOD_EMAIL) {
                    AlarmMethod.EMAIL
                } else {
                    // `METHOD_DEFAULT`, which the stock app writes, is a `DISPLAY` alarm.
                    AlarmMethod.ALERT
                }
                reminders.getOrPut(eventId) { ArrayList() } += ReminderRow(cursor.getInt(1), method)
            }
        }
        return reminders
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
     * The rows a batch's resources claim, and the resources this batch must not touch at all.
     *
     * Both identities at once: a resource's rows are its master, which carries `_SYNC_ID`, and its
     * overrides, which name the resource through `ORIGINAL_SYNC_ID` instead. A malformed row can
     * claim two names — it is listed under each, exactly as the per-resource query would have
     * returned it.
     *
     * One query answers two questions, because they must be answered together. [Claimed.rows] are
     * the rows a writer may write onto: a tombstone is not among them, or the read path would write
     * the server's copy over a deletion that has not been sent yet. [Claimed.blocked] names the
     * resources holding a pending edit or a tombstone anywhere in their rows, which this batch
     * writes nothing for — the engine keeps such keys out of what it asks for, and this is the
     * second line, because its list and the provider's flags can drift in the seconds between.
     */
    private class Claimed(val rows: Map<String, List<ExistingRow>>, val blocked: Set<String>)

    private fun rowsClaiming(account: Account, calendarId: Long, names: Collection<String>): Claimed {
        if (names.isEmpty()) return Claimed(emptyMap(), emptySet())
        val placeholders = names.joinToString(",") { "?" }
        val selection = "${Events.CALENDAR_ID}=? AND (${Events._SYNC_ID} IN ($placeholders) " +
            "OR ${Events.ORIGINAL_SYNC_ID} IN ($placeholders))"
        val args = (listOf(calendarId.toString()) + names + names).toTypedArray()
        val rows = ArrayList<ExistingRow>()
        val blocked = HashSet<String>()
        resolver.query(
            eventsUri(account),
            arrayOf(
                Events._ID,
                Events._SYNC_ID,
                Events.ORIGINAL_SYNC_ID,
                Events.ORIGINAL_INSTANCE_TIME,
                Events.ORIGINAL_ALL_DAY,
                Events.DIRTY,
                Events.DELETED,
            ),
            selection,
            args,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val row = ExistingRow(
                    id = cursor.getLong(0),
                    syncId = cursor.getString(1),
                    originalSyncId = cursor.getString(2),
                    originalInstanceTime = cursor.longOrNull(Events.ORIGINAL_INSTANCE_TIME),
                    originalAllDay = cursor.flag(Events.ORIGINAL_ALL_DAY),
                    dirty = cursor.flag(Events.DIRTY),
                    deleted = cursor.flag(Events.DELETED),
                )
                rows += row
                if (!row.dirty && !row.deleted) continue
                row.syncId?.takeIf { it in names }?.let(blocked::add)
                row.originalSyncId?.takeIf { it in names }?.let(blocked::add)
            }
        }
        return Claimed(rowsByClaim(rows.filterNot { it.deleted }, names), blocked)
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
        // The access level is the calendar's half of the read-only switch: the stock app greys out
        // editing a calendar it does not own and stops offering it in "create event". Neither
        // `supportsUploading` nor `<EditSchema>` can be told about one Collection, so this is what
        // the editor is told.
        put(
            Calendars.CALENDAR_ACCESS_LEVEL,
            if (collection.writable) Calendars.CAL_ACCESS_OWNER else Calendars.CAL_ACCESS_READ,
        )
        // The stock app offers only the reminder methods and availabilities a calendar lists, so a
        // calendar that lists none cannot be given a reminder or a free/busy value at all.
        put(Calendars.ALLOWED_REMINDERS, "0,1,2")
        put(Calendars.ALLOWED_AVAILABILITY, "0,1")
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
        /** The resource exactly as it was fetched, which the master row keeps as its source. */
        private val body: String,
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
            val resolved = timesOf(event, resource.zones, fallbackZone, nowMillis)
            resolved.notes.forEach { Log.i(TAG, "$name: $it") }
            return resolved.times
        }

        private fun eventValues(event: ParsedEvent, times: EventTimes): ContentValues {
            val override = event.shape == EventShape.OVERRIDE
            val values = eventValuesOf(event, times)
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
                    // The source describes the resource, and the resource is the master row: an
                    // override carrying a copy of it would keep one alive after the master's is
                    // gone, and would make `localItems` ask a question about a row that is not an
                    // item.
                    putNull(Events.SYNC_DATA2)
                    putNull(Events.SYNC_DATA3)
                } else {
                    put(Events._SYNC_ID, name)
                    putNull(Events.ORIGINAL_SYNC_ID)
                    putNull(Events.ORIGINAL_INSTANCE_TIME)
                    putNull(Events.ORIGINAL_ALL_DAY)
                    putSource(this, body)
                }
                put(Events.UID_2445, event.uid)
                // The ETag belongs to the resource, so every component of it carries the same one.
                put(Events.SYNC_DATA1, etag)
                put(Events.DTSTART, values.dtStart)
                put(Events.DTEND, values.dtEnd)
                put(Events.DURATION, values.duration)
                put(Events.ALL_DAY, if (values.allDay) 1 else 0)
                put(Events.EVENT_TIMEZONE, values.eventTimeZone)
                put(Events.EVENT_END_TIMEZONE, values.eventEndTimeZone)
                put(Events.TITLE, values.title)
                put(Events.DESCRIPTION, values.description)
                put(Events.EVENT_LOCATION, values.location)
                put(Events.RRULE, values.rrule)
                put(Events.RDATE, values.rdate)
                put(Events.EXDATE, values.exdate)
                // Omitted, not written as null, when the VEVENT carries no STATUS. A key that is
                // present with a null value is what CalendarProvider2's update path unboxes when it
                // asks whether the status changed, and that unboxing is an NPE inside the provider
                // which fails the whole write — no event without a STATUS could ever be updated,
                // only inserted. An absent STATUS and a null one say the same thing about the
                // event, so leaving the column alone is the honest spelling of it.
                values.status?.let { put(Events.STATUS, statusValue(it)) }
                put(Events.AVAILABILITY, availabilityValue(values.transparency))
                // What the read path writes is server state, never a local edit.
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
            for (reminder in event.reminders) {
                val offset = reminderOffsetMinutes(reminder, times, resource.zones, fallbackZone, nowMillis) ?: continue
                if (offset < 0) {
                    Log.i(TAG, "$name: a reminder at or after the event start was clamped to 0 minutes")
                }
                val values = ContentValues().apply {
                    put(Reminders.MINUTES, clampedReminderMinutes(offset))
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

        private fun eventInsert(values: ContentValues): ContentProviderOperation =
            ContentProviderOperation.newInsert(eventsUri(account)).withValues(values).build()

        /**
         * Guarded on the row being the clean one [rowsClaiming] read: a row an editor has changed
         * since holds an edit no run has sent, and writing the server's copy over it is the lost
         * update the flag exists to prevent. The expected count makes the provider refuse the whole
         * batch rather than that one row, which [upsert] answers by building it again.
         */
        private fun eventUpdate(id: Long, values: ContentValues): ContentProviderOperation =
            ContentProviderOperation.newUpdate(eventsUri(account))
                .withSelection(
                    "${Events._ID}=? AND ${Events.DIRTY}=0 AND ${Events.DELETED}=0",
                    arrayOf(id.toString()),
                )
                .withValues(values)
                .withExpectedCount(1)
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
        val originalInstanceTime: Long? = null,
        val originalAllDay: Boolean = false,
        /** `Events.DIRTY`: a local edit whose upload has not been answered. */
        val dirty: Boolean = false,
        /** `Events.DELETED`: a tombstone the user made, which step U sends as a `DELETE`. */
        val deleted: Boolean = false,
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

    /**
     * The deletes of [deleteIds], appended to a batch instead of run.
     *
     * Dirty and deleted rows are left where they are, with no expected count: a row that became one
     * or the other since the batch was planned is an edit or a deletion this app has not sent, and
     * keeping it costs a duplicate until the next run uploads it, while deleting it costs the edit.
     */
    private fun appendDeletes(batch: MutableList<ContentProviderOperation>, account: Account, ids: List<Long>) {
        for (chunk in ids.chunked(ID_CHUNK)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val args = chunk.map { it.toString() }.toTypedArray()
            batch += ContentProviderOperation.newDelete(eventsUri(account))
                .withSelection(
                    "${Events._ID} IN ($placeholders) AND ${Events.DIRTY}=0 AND ${Events.DELETED}=0",
                    args,
                )
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

/** `Events.STATUS` the other way; a value the provider keeps for another purpose is no status. */
private fun statusOf(status: Int?): EventStatus? = when (status) {
    Events.STATUS_TENTATIVE -> EventStatus.TENTATIVE
    Events.STATUS_CONFIRMED -> EventStatus.CONFIRMED
    Events.STATUS_CANCELED -> EventStatus.CANCELLED
    else -> null
}

/**
 * The stored source and its oversize marker, written together.
 *
 * The two columns are one fact: a text without its marker is a row whose next upload would patch a
 * resource it never kept, and a marker without a text is a row that would never be uploaded without
 * being held either.
 */
private fun putSource(values: ContentValues, text: String) {
    if (utf8Length(text) > SOURCE_CAP_BYTES) {
        values.putNull(Events.SYNC_DATA2)
        values.put(Events.SYNC_DATA3, SOURCE_OVERSIZE)
    } else {
        values.put(Events.SYNC_DATA2, text)
        values.putNull(Events.SYNC_DATA3)
    }
}

/** The `Events` columns one pending resource is read from. `SYNC_DATA2` is here and in no wider query. */
private val RESOURCE_PROJECTION = arrayOf(
    Events._ID,
    Events.ORIGINAL_INSTANCE_TIME,
    Events.ORIGINAL_ALL_DAY,
    Events.DELETED,
    Events.DTSTART,
    Events.DTEND,
    Events.DURATION,
    Events.ALL_DAY,
    Events.EVENT_TIMEZONE,
    Events.EVENT_END_TIMEZONE,
    Events.TITLE,
    Events.DESCRIPTION,
    Events.EVENT_LOCATION,
    Events.RRULE,
    Events.RDATE,
    Events.EXDATE,
    Events.STATUS,
    Events.AVAILABILITY,
    Events.SYNC_DATA2,
    Events.SYNC_DATA3,
)

private fun Cursor.intOrNull(column: String): Int? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getInt(index)
}

private fun Cursor.longOrNull(column: String): Long? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getLong(index)
}

private fun Cursor.flag(column: String): Boolean = intOrNull(column)?.let { it != 0 } ?: false

/**
 * One `Events` row as the upload queue reads it.
 *
 * Android-free on purpose: what a row is — a create, an update, a deletion — decides which `DELETE`
 * or `PUT` the engine sends, and a mistake there loses an edit rather than traffic.
 */
internal class QueueRow(
    val id: Long,
    val syncId: String?,
    val originalSyncId: String?,
    val originalId: Long?,
    val dirty: Boolean,
    val deleted: Boolean,
    val etag: String?,
    val uid: String?,
)

/**
 * The rows of one Collection that await upload, one entry per resource.
 *
 * Deletions first, then creates, then updates: a deletion ahead of a create keeps a rename from
 * meeting the name it gave up, and a create ahead of an update keeps an update from naming a row
 * that does not exist yet. Within a kind the order is by row id, so a run's request order is the
 * same on every run that changes nothing.
 *
 * A resource is the unit of upload, so an override that changed queues its *master*: the bytes are
 * the whole `.ics`, and the master is the row that carries the resource's name and ETag.
 */
internal fun pendingPlan(rows: List<QueueRow>): List<LocalChange> {
    val deletion = ArrayList<LocalChange>()
    val creation = ArrayList<LocalChange>()
    val update = ArrayList<LocalChange>()
    val mastersBySyncId = HashMap<String, QueueRow>()
    val mastersById = HashMap<Long, QueueRow>()
    val overrides = ArrayList<QueueRow>()
    for (row in rows) {
        val master = row.originalId == null && row.originalSyncId == null
        if (master && row.deleted) {
            // A tombstone: the `DELETE` goes out under the name the resource had, or the row is
            // purged without a request at all when the phone created it and it never reached the
            // server — which is what a null key means to the lifecycle.
            deletion += LocalChange(row.id, ChangeKind.DELETE, row.syncId, row.etag, row.uid)
            continue
        }
        if (master && row.syncId == null) {
            if (row.dirty) creation += LocalChange(row.id, ChangeKind.CREATE, null, null, row.uid)
            continue
        }
        if (master) {
            row.syncId?.let { mastersBySyncId[it] = row }
            mastersById[row.id] = row
        } else {
            overrides += row
        }
    }
    val changedMasters = HashSet<Long>()
    for (override in overrides) {
        if (!override.dirty && !override.deleted) continue
        override.originalId?.let(changedMasters::add)
        override.originalSyncId?.let { name -> mastersBySyncId[name]?.let { changedMasters += it.id } }
    }
    for ((id, row) in mastersById) {
        if (!row.dirty && id !in changedMasters) continue
        update += LocalChange(row.id, ChangeKind.UPDATE, row.syncId, row.etag, row.uid)
    }
    return deletion.sortedBy { it.rowId } + creation.sortedBy { it.rowId } + update.sortedBy { it.rowId }
}

/**
 * The rows a listing that completed may delete: those whose resource it did not name.
 *
 * Two exclusions, and each is the difference between a stale row and a lost edit. A `DIRTY` row is
 * an edit whose upload has not been answered; for an event the phone created, that row is the only
 * copy there is, and a selection that doomed it — which is what "no `_SYNC_ID` and no
 * `ORIGINAL_SYNC_ID`" used to mean — would delete it on the first run after the server refused the
 * `PUT`. A `DELETED` row is a tombstone step U has yet to send, which no listing can confirm.
 */
internal fun doomedRows(rows: List<CalendarMapper.ExistingRow>, keepHrefs: Set<String>): List<Long> = rows
    .filterNot { it.dirty || it.deleted }
    .filter {
        val name = it.syncId ?: it.originalSyncId
        name == null || name !in keepHrefs
    }
    .map { it.id }

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
