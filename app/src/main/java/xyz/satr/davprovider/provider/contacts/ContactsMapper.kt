package xyz.satr.davprovider.provider.contacts

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.ContentProviderResult
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.BaseColumns
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.DisplayPhoto
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import android.provider.ContactsContract.SyncState
import android.util.Log
import java.util.Locale
import xyz.satr.davprovider.core.CollectionState
import xyz.satr.davprovider.core.DavCollection
import xyz.satr.davprovider.core.ProviderMapper

/**
 * Writes one Account's address books into `ContactsContract`.
 *
 * Identity is `(Account, SYNC3, SOURCE_ID)`: [RawContacts.SOURCE_ID] holds the key the sync engine
 * lists resources under, `SYNC1` its vCard UID and `SYNC2` its ETag, both stored exactly as given.
 * `SYNC3` carries the Collection id, which is what scopes every lookup, diff and delete in this
 * class to the one address book the call is about: an Account spans many Collections.
 *
 * v1 is read-only, so a write re-applies what the server says and nothing else: an item's rows are
 * replaced wholesale rather than merged, which is also what lets [clearDirty]'s `DIRTY` reset hold —
 * a row that still carried a local edit would be re-dirtied the moment it is touched.
 *
 * @param photoFetcher used for `PHOTO;VALUE=uri`, and only for a URL that is on the Collection's own
 *   Origin. Left null, such photos are skipped and counted, which is what v1 does.
 */
class ContactsMapper(
    context: Context,
    private val photoFetcher: PhotoFetcher? = null,
) : ProviderMapper {

    private val resolver: ContentResolver = context.contentResolver
    private val accountManager: AccountManager = AccountManager.get(context)

    /** The provider owns this bound and it varies with device memory, so it is asked, once. */
    private var maxPhotoDimension: Int? = null

    override val authority: String = ContactsContract.AUTHORITY

    override fun assertAccountRegistered(account: Account) {
        // Load-bearing, not a sanity check: the provider resolves an account from every write URI and
        // creates an accounts row for whatever it is handed, while its cleanup path deletes the rows
        // of an ACCOUNT_TYPE it can no longer resolve. An unregistered Account therefore means rows
        // owned by nobody, or rows that are about to be deleted under the user.
        val registered = accountManager.getAccountsByType(account.type).any { it.name == account.name }
        check(registered) { "Account ${account.name} (${account.type}) is not registered" }
    }

    override fun readState(account: Account, collection: DavCollection): CollectionState =
        readStates(account)[collection.id] ?: CollectionState()

    override fun writeState(account: Account, collection: DavCollection, state: CollectionState) {
        assertAccountRegistered(account)
        // SyncState keeps one row per Account, so this Collection's state is merged into that row's
        // blob: replacing it outright would forget every other Collection of the Account.
        val states = readStates(account).toMutableMap()
        states[collection.id] = state
        val data = SyncStateCodec.encode(states)

        val rowId = syncStateRowId(account)
        if (rowId == null) {
            resolver.insert(
                SyncState.CONTENT_URI.forSyncState(),
                ContentValues().apply {
                    put(SyncState.ACCOUNT_NAME, account.name)
                    put(SyncState.ACCOUNT_TYPE, account.type)
                    put(SyncState.DATA, data)
                },
            )
        } else {
            resolver.update(
                ContentUris.withAppendedId(SyncState.CONTENT_URI, rowId).forSyncState(),
                ContentValues().apply { put(SyncState.DATA, data) },
                null,
                null,
            )
        }
    }

    override fun localItems(account: Account, collection: DavCollection): Map<String, String?> {
        val items = LinkedHashMap<String, String?>()
        resolver.query(
            RawContacts.CONTENT_URI.forSyncAdapter(account),
            arrayOf(RawContacts.SOURCE_ID, RawContacts.SYNC2),
            contactSelection(),
            collectionArgs(account, collection),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val sourceId = cursor.getString(0) ?: continue
                items[sourceId] = cursor.getString(1)
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
        assertAccountRegistered(account)

        val stats = UpsertStats()
        val contacts = ArrayList<Resource>()
        val groups = ArrayList<Resource>()
        for ((key, text) in resources) {
            val parsed = parseVCard(text)
            if (parsed == null) {
                stats.unreadable++
                Log.w(LOG_TAG, "${collection.id}: $key did not parse, leaving it for the next run")
                continue
            }
            if (parsed.isGroup) groups += Resource(key, text, parsed) else contacts += Resource(key, text, parsed)
        }

        val batch = ArrayList<ContentProviderOperation>()
        val dataUri = Data.CONTENT_URI.forSyncAdapter(account)
        val rawContactsUri = RawContacts.CONTENT_URI.forSyncAdapter(account)
        val groupsUri = Groups.CONTENT_URI.forSyncAdapter(account)

        // The rows this Collection already holds, for every name this batch asks about, read once for
        // the batch: the write path asks "which row is this href?" once per resource and "which row is
        // this UID or title?" once per membership end, so a run in which only contacts changed used to
        // pay an indexed query for each of those answers. The names are the batch's own, so this reads
        // what those questions can have an answer for and nothing else.
        val known = knownRows(account, collection, contacts, groups)

        // Pass 1: contacts, with their ETags, in the rows that carry them. Nothing else in the batch
        // may point at a contact that does not exist yet.
        val writtenContacts = ArrayList<Pair<Resource, RowRef>>()
        val contactsByUid = HashMap<String, RowRef>()
        for (resource in contacts) {
            val ref = appendContact(account, collection, resource, etags[resource.key], dataUri, rawContactsUri, batch, stats, known)
            writtenContacts += resource to ref
            resource.parsed.uid?.let { contactsByUid[it] = ref }
        }

        // Pass 2: groups, which memberships need a row id for.
        val writtenGroups = ArrayList<Pair<Resource, RowRef>>()
        val groupsByTitle = HashMap<String, RowRef>()
        for (resource in groups) {
            val ref = appendGroup(account, collection, resource, etags[resource.key], dataUri, groupsUri, batch, known)
            writtenGroups += resource to ref
            resource.parsed.displayName?.let { groupsByTitle[it.lowercase(Locale.ROOT)] = ref }
        }

        val unresolved = ArrayList<DeferredMembership>()
        // A contact's CATEGORIES name the groups it belongs to, which is how a DAV client that has no
        // group objects at all still expresses membership.
        for ((resource, ref) in writtenContacts) {
            for (category in resource.parsed.categories) {
                val group = groupsByTitle[category.lowercase(Locale.ROOT)]
                    ?: known.groupIdsByTitle[category]?.let { RowRef.existing(it) }
                if (group == null) {
                    unresolved += DeferredMembership(MEMBERSHIP_FROM_CATEGORIES, contact = ref, groupTitle = category)
                    continue
                }
                batch += membershipOperation(dataUri, ref, group, MEMBERSHIP_FROM_CATEGORIES)
                stats.memberships++
            }
        }
        // A group vCard names its members by UID, which is the other direction of the same relation.
        for ((resource, group) in writtenGroups) {
            for (uid in resource.parsed.members) {
                val contact = contactsByUid[uid]
                    ?: known.contactIdsByUid[uid]?.let { RowRef.existing(it) }
                if (contact == null) {
                    unresolved += DeferredMembership(MEMBERSHIP_FROM_GROUP, contactUid = uid, group = group)
                    continue
                }
                batch += membershipOperation(dataUri, contact, group, MEMBERSHIP_FROM_GROUP)
                stats.memberships++
            }
        }

        stats.items = contacts.size + groups.size

        // Nothing to write is a real outcome — every resource may have failed to parse — and an
        // empty batch is not something the framework accepts.
        if (batch.isEmpty()) {
            stats.log(collection)
            return stats.items
        }

        // Re-checked here and not only at the start: a long first sync is exactly when a user is
        // most likely to remove the Account, and the rows of an Account that no longer exists are
        // rows the provider reaps.
        assertAccountRegistered(account)
        val results = resolver.applyBatch(authority, batch)

        // Pass 3: memberships whose other end this batch never named. Both ends can arrive in any
        // order across batches, and a batch sees everything the run wrote before it: whatever is not
        // in [known] either does not exist, in which case the next run resolves it once it does, or is
        // counted as unresolved and logged rather than failing the batch around it.
        val reconciliation = ArrayList<ContentProviderOperation>()
        for (membership in unresolved) {
            val contactId = membership.contact?.let { resolveId(it, results) }
                ?: membership.contactUid?.let { known.contactIdsByUid[it] }
            val groupId = membership.group?.let { resolveId(it, results) }
                ?: membership.groupTitle?.let { known.groupIdsByTitle[it] }
            if (contactId == null || groupId == null) {
                stats.unresolvedMemberships++
                continue
            }
            reconciliation += membershipOperation(
                dataUri,
                RowRef.existing(contactId),
                RowRef.existing(groupId),
                membership.provenance,
            )
            stats.memberships++
        }
        if (reconciliation.isNotEmpty()) {
            assertAccountRegistered(account)
            resolver.applyBatch(authority, reconciliation)
        }

        stats.log(collection)
        return stats.items
    }

    override fun deleteMissing(account: Account, collection: DavCollection, keepHrefs: Set<String>): Int {
        assertAccountRegistered(account)
        // The caller contract is that this only ever runs against a listing that completed; the
        // mapper's own part in that is scoping every delete to this Account and this Collection, so
        // a short listing can never reach another address book's rows.
        val staleContacts = ArrayList<Long>()
        resolver.query(
            RawContacts.CONTENT_URI.forSyncAdapter(account),
            arrayOf(RawContacts._ID, RawContacts.SOURCE_ID),
            contactSelection(),
            collectionArgs(account, collection),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val sourceId = cursor.getString(1) ?: continue
                if (sourceId !in keepHrefs) staleContacts += cursor.getLong(0)
            }
        }

        // Groups are scoped by SYNC3 for the same reason contacts are: a group's href is unique on
        // the server, but nothing about it says which address book the listing came from.
        val staleGroups = ArrayList<Long>()
        resolver.query(
            Groups.CONTENT_URI.forSyncAdapter(account),
            arrayOf(Groups._ID, Groups.SOURCE_ID),
            "${Groups.ACCOUNT_NAME}=? AND ${Groups.ACCOUNT_TYPE}=? AND ${Groups.SYNC3}=? " +
                "AND ${Groups.SOURCE_ID} IS NOT NULL",
            collectionArgs(account, collection),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val sourceId = cursor.getString(1) ?: continue
                if (sourceId !in keepHrefs) staleGroups += cursor.getLong(0)
            }
        }

        val deleted = deleteRows(RawContacts.CONTENT_URI.forSyncAdapter(account), staleContacts) +
            deleteRows(Groups.CONTENT_URI.forSyncAdapter(account), staleGroups)
        if (deleted > 0) {
            Log.i(LOG_TAG, "${collection.id}: deleted $deleted rows that are gone from the server")
        }
        return deleted
    }

    override fun clearDirty(account: Account, collection: DavCollection) {
        assertAccountRegistered(account)
        // Stock editors honour supportsUploading="false" and never write, but nothing stops a
        // third-party one from editing a row anyway. Clearing DIRTY stops the framework from trying
        // to upload an edit this app has no path to send, and the next upsert puts the server's
        // version back — which is what makes such an edit visibly revert instead of lingering.
        resolver.update(
            RawContacts.CONTENT_URI.forSyncAdapter(account),
            ContentValues().apply { put(RawContacts.DIRTY, 0) },
            contactSelection(),
            collectionArgs(account, collection),
        )
        // A group can be renamed from the Contacts app just as a contact can be edited. The selection
        // stays on columns the groups view and table share; the Account comes from the URI, which the
        // provider turns into an account id — the groups table has no account name column of its own.
        resolver.update(
            Groups.CONTENT_URI.forSyncAdapter(account),
            ContentValues().apply { put(Groups.DIRTY, 0) },
            "${Groups.SYNC3}=?",
            arrayOf(collection.id),
        )
    }

    // ------------------------------------------------------------------ rows

    /**
     * Writes one contact's identity and body, and returns the row the rest of the batch refers to.
     *
     * The ETag goes in the same operation as the body: an ETag persisted on its own, or a body
     * without one, would make the next run's diff against [localItems] describe something that is
     * not there.
     */
    private fun appendContact(
        account: Account,
        collection: DavCollection,
        resource: Resource,
        etag: String?,
        dataUri: Uri,
        rawContactsUri: Uri,
        batch: MutableList<ContentProviderOperation>,
        stats: UpsertStats,
        known: KnownRows,
    ): RowRef {
        val sync = ContentValues().apply {
            put(RawContacts.SOURCE_ID, resource.key)
            put(RawContacts.SYNC3, collection.id)
            put(RawContacts.SYNC1, resource.parsed.uid)
            if (etag != null) put(RawContacts.SYNC2, etag) else putNull(RawContacts.SYNC2)
            // Nothing this app writes is an edit waiting to be uploaded.
            put(RawContacts.DIRTY, 0)
        }

        val existing = known.contactIdsBySourceId[resource.key]
        val ref: RowRef
        if (existing == null) {
            val index = batch.size
            batch += ContentProviderOperation.newInsert(rawContactsUri)
                .withValues(ContentValues(sync).apply {
                    put(RawContacts.ACCOUNT_NAME, account.name)
                    put(RawContacts.ACCOUNT_TYPE, account.type)
                })
                .build()
            ref = RowRef.pending(index)
        } else {
            ref = RowRef.existing(existing)
            batch += ContentProviderOperation.newUpdate(rawContactsUri)
                .withSelection("${RawContacts._ID}=?", arrayOf(existing.toString()))
                .withValues(sync)
                .build()
            // The server is the source of truth, so nothing the previous run wrote may survive: a
            // phone number the server has since dropped would otherwise stay on the contact forever.
            // The one exception is a membership a group vCard wrote — belonging to a group is not
            // this contact's to own, and the group re-writes that relation when the group changes.
            batch += ContentProviderOperation.newDelete(dataUri)
                .withSelection(
                    "${Data.RAW_CONTACT_ID}=? AND NOT (${Data.MIMETYPE}=? AND ${Data.DATA2}=?)",
                    arrayOf(existing.toString(), GroupMembership.CONTENT_ITEM_TYPE, MEMBERSHIP_FROM_GROUP),
                )
                .build()
        }

        for (row in resource.parsed.rows) {
            batch += dataInsert(dataUri, ref, row)
        }
        photoRow(resource, collection, stats)?.let { batch += dataInsert(dataUri, ref, it) }
        batch += dataInsert(dataUri, ref, verbatimRow(resource, collection))
        stats.unmappedProperties += resource.parsed.unmappedProperties
        return ref
    }

    /**
     * Writes a group vCard's row, and returns the row memberships point at.
     *
     * A group is not a contact: it has no raw contact row, so nothing of its vCard can be stored
     * beside it the way [verbatimRow] stores a contact's. What it does carry is kept — its title, its
     * href, and one membership row per member that also exists locally.
     */
    private fun appendGroup(
        account: Account,
        collection: DavCollection,
        resource: Resource,
        etag: String?,
        dataUri: Uri,
        groupsUri: Uri,
        batch: MutableList<ContentProviderOperation>,
        known: KnownRows,
    ): RowRef {
        val sync = ContentValues().apply {
            put(Groups.SOURCE_ID, resource.key)
            put(Groups.SYNC3, collection.id)
            put(Groups.TITLE, resource.parsed.displayName)
            if (etag != null) put(Groups.SYNC2, etag) else putNull(Groups.SYNC2)
            put(Groups.DIRTY, 0)
        }

        val existing = known.groupIdsBySourceId[resource.key]
        return if (existing == null) {
            val index = batch.size
            batch += ContentProviderOperation.newInsert(groupsUri)
                .withValues(ContentValues(sync).apply {
                    put(Groups.ACCOUNT_NAME, account.name)
                    put(Groups.ACCOUNT_TYPE, account.type)
                    // A group nobody can see is a group the Contacts app will not offer.
                    put(Groups.GROUP_VISIBLE, 1)
                })
                .build()
            RowRef.pending(index)
        } else {
            batch += ContentProviderOperation.newUpdate(groupsUri)
                .withSelection("${Groups._ID}=?", arrayOf(existing.toString()))
                .withValues(ContentValues(sync).apply { put(Groups.GROUP_VISIBLE, 1) })
                .build()
            // This group's previous members are replaced rather than added to: a member the server
            // has since taken out of the group must not stay in it. Only this direction's rows are
            // touched, so the memberships the contacts' own categories produced are left alone.
            batch += ContentProviderOperation.newDelete(dataUri)
                .withSelection(
                    "${Data.MIMETYPE}=? AND ${GroupMembership.GROUP_ROW_ID}=? AND ${Data.DATA2}=?",
                    arrayOf(GroupMembership.CONTENT_ITEM_TYPE, existing.toString(), MEMBERSHIP_FROM_GROUP),
                )
                .build()
            RowRef.existing(existing)
        }
    }

    private fun dataInsert(dataUri: Uri, ref: RowRef, row: DataRow): ContentProviderOperation =
        ContentProviderOperation.newInsert(dataUri)
            .withValues(row.values)
            .withValue(Data.MIMETYPE, row.mimeType)
            .withRowRef(Data.RAW_CONTACT_ID, ref)
            .build()

    /**
     * The value for [column]: a row id, or a reference to the result of an earlier operation in the
     * same batch — the only way to write a row whose parent does not exist until the batch runs.
     */
    private fun ContentProviderOperation.Builder.withRowRef(column: String, ref: RowRef): ContentProviderOperation.Builder {
        val id = ref.id
        if (id != null) return withValue(column, id)
        val index = ref.batchIndex
        checkNotNull(index) { "row reference has neither an id nor a batch index" }
        return withValueBackReference(column, index)
    }

    /**
     * A membership row: DATA1 is the group, and DATA2 records which side of the relation wrote it.
     * The marker is this app's own convention — the platform spends DATA1 on the group row id and
     * computes the group's source id from a join — and it exists because the two directions that can
     * write a membership refresh independently of each other.
     */
    private fun membershipOperation(
        dataUri: Uri,
        contact: RowRef,
        group: RowRef,
        provenance: String,
    ): ContentProviderOperation =
        ContentProviderOperation.newInsert(dataUri)
            .withValue(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE)
            .withRowRef(Data.RAW_CONTACT_ID, contact)
            .withRowRef(GroupMembership.GROUP_ROW_ID, group)
            .withValue(Data.DATA2, provenance)
            .build()

    /**
     * The resource exactly as the server sent it.
     *
     * A contact editor can only show what has a column, so anything without a mapping — a property
     * this version predates, the `ADR` the platform reshapes, the exact spelling of a name — would
     * otherwise be gone for good. One row buys that back, which is what makes read-only v1 lossless
     * at rest and a future upload able to send back more than it was given.
     *
     * DATA2 and DATA3 are the summary and detail columns `res/xml/contacts.xml` declares for this
     * data kind, so the row has something short and honest to show: where the contact came from.
     */
    private fun verbatimRow(resource: Resource, collection: DavCollection): DataRow =
        DataRow(
            VCARD_MIME_TYPE,
            ContentValues().apply {
                put(Data.DATA1, resource.text)
                put(Data.DATA2, collection.displayName ?: collection.id)
                put(Data.DATA3, resource.key)
            },
        )

    /**
     * The photo row, from bytes the vCard carries or from a URL it points at.
     *
     * The bytes written are the display-size image: the provider derives both the thumbnail and the
     * display photo file from them, and only creates that file when what it is given is larger than
     * a thumbnail.
     */
    private fun photoRow(resource: Resource, collection: DavCollection, stats: UpsertStats): DataRow? {
        val source = resource.parsed.photo ?: return null
        val bytes = when (source) {
            is PhotoSource.Inline -> source.bytes
            is PhotoSource.Link -> linkedPhotoBytes(source, collection, resource.key, stats) ?: return null
        }
        val image = PhotoImages.toDisplayPhoto(bytes, displayPhotoDimension())
        if (image == null) {
            stats.unreadablePhotos++
            Log.w(LOG_TAG, "${collection.id}/${resource.key}: photo does not decode, skipping it")
            return null
        }
        return DataRow(Photo.CONTENT_ITEM_TYPE, ContentValues().apply { put(Photo.PHOTO, image) })
    }

    /**
     * Fetches a photo the vCard only points at.
     *
     * The URL is data the server chose, so it is fetched only when it names the same Origin as the
     * Collection: anything else would send this Account's Credentials to a host the data itself
     * named. A URL that fails that test is skipped and counted — never fetched, and never quietly
     * treated as though the vCard had no photo.
     */
    private fun linkedPhotoBytes(
        source: PhotoSource.Link,
        collection: DavCollection,
        key: String,
        stats: UpsertStats,
    ): ByteArray? {
        val target = PhotoImages.resolve(collection.url, source.reference)
        if (target == null || !PhotoImages.isSameOrigin(collection.url, target)) {
            stats.foreignPhotos++
            Log.w(LOG_TAG, "${collection.id}/$key: refusing a photo that is not on the Origin")
            return null
        }
        val fetcher = photoFetcher
        if (fetcher == null) {
            stats.unfetchedPhotos++
            return null
        }
        return fetcher.fetch(target.toString())
    }

    // ------------------------------------------------------------------ lookups

    /**
     * The rows of this Collection that the names in one batch can mean.
     *
     * Read once for the batch, before any of it is built — which is what makes it correct as well as
     * cheap. Rows an earlier batch of this run wrote are committed and therefore visible; rows this
     * batch writes are not, and those are the batch's own maps and [RowRef.pending], both of which are
     * consulted first. A direction this batch does not name costs no read at all.
     */
    private fun knownRows(
        account: Account,
        collection: DavCollection,
        contacts: List<Resource>,
        groups: List<Resource>,
    ): KnownRows {
        val rawContactsUri = RawContacts.CONTENT_URI.forSyncAdapter(account)
        val groupsUri = Groups.CONTENT_URI.forSyncAdapter(account)
        val scope = collectionArgs(account, collection)
        return KnownRows(
            contactIdsBySourceId = idsByName(
                rawContactsUri,
                RawContacts.SOURCE_ID,
                contactSelection(),
                scope,
                contacts.map { it.key },
            ),
            // A group's MEMBER names a contact by its vCard UID, which this class stores in SYNC1.
            contactIdsByUid = idsByName(
                rawContactsUri,
                RawContacts.SYNC1,
                contactSelection(),
                scope,
                groups.flatMap { it.parsed.members }.distinct(),
            ),
            groupIdsBySourceId = idsByName(
                groupsUri,
                Groups.SOURCE_ID,
                groupSelection(),
                scope,
                groups.map { it.key },
            ),
            // Groups are named by their title, which is all a `CATEGORIES` value has to go on.
            groupIdsByTitle = idsByName(
                groupsUri,
                Groups.TITLE,
                groupSelection(),
                scope,
                contacts.flatMap { it.parsed.categories }.distinct(),
            ),
        )
    }

    /**
     * The id of every row carrying one of [names] in [column], within [scope].
     *
     * Chunked like [deleteRows], and for the same reason: an `IN` list is bounded by the variables one
     * statement may bind, and a group vCard may name thousands of members. [scopeArgs] spends three of
     * those, which one chunk of [SQL_VARIABLES_PER_STATEMENT] leaves room for.
     *
     * The first row wins, which is what the per-resource query returned as well.
     */
    private fun idsByName(
        uri: Uri,
        column: String,
        scope: String,
        scopeArgs: Array<String>,
        names: List<String>,
    ): Map<String, Long> {
        if (names.isEmpty()) return emptyMap()
        val ids = HashMap<String, Long>()
        for (chunk in names.chunked(SQL_VARIABLES_PER_STATEMENT)) {
            val placeholders = chunk.joinToString(",") { "?" }
            resolver.query(uri, arrayOf(BaseColumns._ID, column), "$scope AND $column IN ($placeholders)", scopeArgs + chunk, null)
                ?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(1) ?: continue
                        ids.putIfAbsent(name, cursor.getLong(0))
                    }
                }
        }
        return ids
    }

    /**
     * The rows one batch may point at, read for the batch as a whole. A name that is absent is not an
     * error: no such row is there yet, which is what [RowRef.pending] exists for.
     */
    private class KnownRows(
        val contactIdsBySourceId: Map<String, Long>,
        val contactIdsByUid: Map<String, Long>,
        val groupIdsBySourceId: Map<String, Long>,
        val groupIdsByTitle: Map<String, Long>,
    )

    private fun queryId(uri: Uri, selection: String, args: Array<String>): Long? =
        resolver.query(uri, arrayOf(BaseColumns._ID), selection, args, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }

    private fun syncStateRowId(account: Account): Long? =
        queryId(
            SyncState.CONTENT_URI.forSyncState(),
            "${SyncState.ACCOUNT_NAME}=? AND ${SyncState.ACCOUNT_TYPE}=?",
            arrayOf(account.name, account.type),
        )

    private fun readStates(account: Account): Map<String, CollectionState> =
        resolver.query(
            SyncState.CONTENT_URI.forSyncState(),
            arrayOf(SyncState.DATA),
            "${SyncState.ACCOUNT_NAME}=? AND ${SyncState.ACCOUNT_TYPE}=?",
            arrayOf(account.name, account.type),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) SyncStateCodec.decode(cursor.getBlob(0)) else emptyMap()
        } ?: emptyMap()

    /**
     * The longest side the provider will keep for a display photo. It is the provider's bound, not
     * this app's, and it is smaller on a memory-constrained device: asking costs one query and stops
     * a large photo from being decoded at full size for nothing.
     */
    private fun displayPhotoDimension(): Int {
        maxPhotoDimension?.let { return it }
        val queried = resolver.query(
            DisplayPhoto.CONTENT_MAX_DIMENSIONS_URI,
            arrayOf(DisplayPhoto.DISPLAY_MAX_DIM),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        } ?: 0
        val dimension = queried.takeIf { it > 0 } ?: FALLBACK_MAX_PHOTO_DIMENSION
        maxPhotoDimension = dimension
        return dimension
    }

    // ------------------------------------------------------------------ selections

    /**
     * The scope of every read and write in this class: one Account, one Collection.
     *
     * Contacts can be selected by account directly, because a query and an update are both evaluated
     * against the raw contacts view. A group *write* cannot: the provider applies it to the groups
     * table, which has no account name column, so group writes select on `_id` and take their Account
     * from the URI parameters instead.
     */
    private fun contactSelection(): String =
        "${RawContacts.ACCOUNT_NAME}=? AND ${RawContacts.ACCOUNT_TYPE}=? AND ${RawContacts.SYNC3}=? " +
            "AND ${RawContacts.SOURCE_ID} IS NOT NULL AND ${RawContacts.DELETED}=0"

    /** The same scope for groups, whose lookups do run against the groups view. */
    private fun groupSelection(): String =
        "${Groups.ACCOUNT_NAME}=? AND ${Groups.ACCOUNT_TYPE}=? AND ${Groups.SYNC3}=?"

    private fun collectionArgs(account: Account, collection: DavCollection): Array<String> =
        arrayOf(account.name, account.type, collection.id)

    /**
     * Deletes rows by id, in chunks.
     *
     * An `IN` list is the only form that stays precise under a listing of a few thousand items, and
     * it is bounded by SQLite's variable limit per statement — a delete built from the whole
     * Collection at once would fail on exactly the accounts this app is for.
     */
    private fun deleteRows(uri: Uri, ids: List<Long>): Int {
        var deleted = 0
        for (chunk in ids.chunked(SQL_VARIABLES_PER_STATEMENT)) {
            val placeholders = chunk.joinToString(",") { "?" }
            deleted += resolver.delete(
                uri,
                "${BaseColumns._ID} IN ($placeholders)",
                chunk.map { it.toString() }.toTypedArray(),
            )
        }
        return deleted
    }

    private fun resolveId(ref: RowRef, results: Array<ContentProviderResult>): Long? =
        ref.id ?: ref.batchIndex?.let { index ->
            results.getOrNull(index)?.uri?.let { uri -> ContentUris.parseId(uri) }
        }

    /**
     * A row the batch is about to create, or one that already exists — exactly one of the two,
     * because which of them it is decides whether a value is a row id or a back-reference.
     */
    private class RowRef private constructor(val id: Long?, val batchIndex: Int?) {
        companion object {
            fun existing(id: Long): RowRef = RowRef(id, null)
            fun pending(batchIndex: Int): RowRef = RowRef(null, batchIndex)
        }
    }

    /** One resource of a batch: the key it is known by, its text, and what the text says. */
    private class Resource(val key: String, val text: String, val parsed: ParsedVCard)

    /** A membership whose other end the batch could not name, kept for the reconciliation pass. */
    private class DeferredMembership(
        val provenance: String,
        val contact: RowRef? = null,
        val contactUid: String? = null,
        val group: RowRef? = null,
        val groupTitle: String? = null,
    )

    /** What one [upsert] did, in the numbers that make a sync readable in a log. */
    private class UpsertStats {
        var items = 0
        var memberships = 0
        var unmappedProperties = 0
        var unresolvedMemberships = 0
        var unreadable = 0
        var foreignPhotos = 0
        var unreadablePhotos = 0
        var unfetchedPhotos = 0

        fun log(collection: DavCollection) {
            Log.i(
                LOG_TAG,
                "${collection.id}: $items items, $memberships memberships, " +
                    "$unmappedProperties unmapped properties, $unresolvedMemberships unresolved " +
                    "memberships, $unreadable unreadable, $foreignPhotos photos off the Origin, " +
                    "$unreadablePhotos photos that do not decode, $unfetchedPhotos photos not fetched",
            )
        }
    }

    private companion object {
        /** AOSP's own bound for a large-memory device, used only if the provider cannot be asked. */
        const val FALLBACK_MAX_PHOTO_DIMENSION = 720

        /** Comfortably under SQLite's bound on the number of variables in one statement. */
        const val SQL_VARIABLES_PER_STATEMENT = 500

        /**
         * Marks a membership row written from a contact's `CATEGORIES`, as opposed to one written
         * from a group's member list. The two are refreshed at different times — a contact changes
         * without its groups changing, and a group changes without its members changing — so each
         * side replaces only the rows it owns.
         */
        const val MEMBERSHIP_FROM_CATEGORIES = "categories"

        /** Marks a membership row written from a group vCard's member list. */
        const val MEMBERSHIP_FROM_GROUP = "members"
    }
}
