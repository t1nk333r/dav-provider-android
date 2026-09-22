package xyz.satr.davprovider.provider.contacts

import android.accounts.Account
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.RawContacts

/** Log tag for everything this package writes into the contacts provider. */
internal const val LOG_TAG = "DavContacts"

/**
 * The MIME type of the row that carries a resource's source vCard verbatim.
 *
 * `res/xml/contacts.xml` declares it as a data kind of this account type; the provider refuses data
 * rows whose MIME type is not declared for the account's sync adapter, so the two must stay in step.
 */
internal const val VCARD_MIME_TYPE = "vnd.android.cursor.item/vnd.xyz.satr.davprovider.vcard"

/**
 * Marks this URI as a sync-adapter write and names the Account the rows belong to.
 *
 * `ContactsProvider2` reads both from the URI rather than from the calling identity.
 * `CALLER_IS_SYNCADAPTER` decides whether a delete is a real delete or a tombstone, and whether the
 * write may touch account and sync columns at all; the account parameters are how a statement that
 * carries a selection (`update`, `delete`) is scoped to one Account, since none of those tables can
 * join to the account by itself.
 *
 * Every write in this package goes through here, and the uniformity is what makes the write-back
 * lifecycle work at all: the provider dirties a raw contact on any write that is not a sync adapter's,
 * so [ContactsMapper.markUploaded] — the one place `DIRTY` is cleared, and only after the server has
 * answered the upload — has to write through this URI or its own clear would re-dirty the row.
 */
internal fun Uri.forSyncAdapter(account: Account): Uri = buildUpon()
    .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
    .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
    .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
    .build()

/**
 * The sync-state variant of [forSyncAdapter]: same marker, no account parameters.
 *
 * SyncState holds one row per Account and carries the Account in its own columns. The provider
 * resolves account parameters on that URI by appending them to the selection, and would append
 * `data_set IS NULL` along with them — a column the syncstate table does not have.
 */
internal fun Uri.forSyncState(): Uri = buildUpon()
    .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
    .build()
