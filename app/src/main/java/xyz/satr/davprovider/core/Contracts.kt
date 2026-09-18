package xyz.satr.davprovider.core

import android.accounts.Account
import okhttp3.OkHttpClient

/**
 * Shared contracts between the slices. Owned by the integration boundary: implementations
 * live in their own packages and must not change these signatures without coordination.
 *
 * Vocabulary follows CONTEXT.md — Account, Collection, Credentials, Origin.
 */

const val ACCOUNT_TYPE = "xyz.satr.davprovider"

// ---------------------------------------------------------------- model

/** One name/value pair sent on every request for an Account. Values are secrets. */
data class DavHeader(val name: String, val value: String)

enum class CollectionType { ADDRESS_BOOK, CALENDAR }

/**
 * One address book or calendar. [id] is stable and local; it lands in RawContacts.SYNC3
 * so that (account, SYNC3, SOURCE_ID) is unique across Collections of one Account.
 */
data class DavCollection(
    val id: String,
    val url: String,
    val type: CollectionType,
    val displayName: String?,
    val color: Int?,
    val selected: Boolean = false,
    val available: Boolean = true,
)

/** One configured server. Exactly one AccountManager account. */
data class DavAccount(
    val label: String,
    val baseUrl: String,
    val headers: List<DavHeader> = emptyList(),
    val certAlias: String? = null,
    val username: String? = null,
    val password: String? = null,
    val collections: List<DavCollection> = emptyList(),
) {
    val androidAccount: Account get() = Account(label, ACCOUNT_TYPE)
}

// ---------------------------------------------------------------- errors

/** The twelve failure classes of the spec's taxonomy. Order matters: see SyncErrorClassifier. */
enum class ErrorClass(val terminal: Boolean, val retryable: Boolean) {
    PROXY_REJECTED_CREDENTIALS(true, false),
    ORIGIN_WANTS_CREDENTIALS(true, false),
    ORIGIN_REFUSED_INFO(false, false),
    CERTIFICATE_UNAVAILABLE(true, false),
    CREDENTIALS_UNREADABLE(true, false),
    NO_CERTIFICATE_SENT(true, false),
    TRANSPORT_FAILURE(false, true),
    SERVER_ERROR(false, true),
    NOT_FOUND(false, false),
    METHOD_REFUSED(false, false),
    MALFORMED_RESPONSE(false, false),
    PROXY_INTERFERENCE(true, false),
}

/**
 * What the app observed. [summary] is the plain-language line; the expander shows the rest
 * verbatim. Never claim more than was observed: see NO_CERTIFICATE_SENT.
 */
data class SyncError(
    val errorClass: ErrorClass,
    val summary: String,
    val httpStatus: Int?,
    val firstBodyLine: String?,
    val certificateOffered: Boolean,
    val requestMethod: String?,
    val davCondition: String? = null,
    val cause: Throwable? = null,
)

/** Evidence handed to the classifier. Deliberately transport-agnostic so it is testable. */
data class ResponseEvidence(
    val httpStatus: Int?,
    val locationHeader: String?,
    val wwwAuthenticate: String?,
    val contentType: String?,
    val body: String?,
    val requestMethod: String?,
    val certificateOffered: Boolean,
    val transportFailure: Throwable? = null,
)

interface SyncErrorClassifier {
    /** Applies the spec's ordered rules; first match wins. Status alone never decides severity. */
    fun classify(evidence: ResponseEvidence): SyncError
}

// ---------------------------------------------------------------- credentials

/**
 * Keystore-wrapped ciphertext stored in AccountManager userdata (ADR-0001).
 * Credentials can be replaced, never revealed.
 */
interface CredentialStore {
    fun put(account: Account, key: String, secret: String)
    /** @throws CredentialsUnreadableException after a device restore: the Keystore key is gone. */
    fun get(account: Account, key: String): String?
    fun remove(account: Account, key: String)
    fun clear(account: Account)
}

class CredentialsUnreadableException(cause: Throwable?) :
    Exception("Credentials can't be read — this happens after restoring a device.", cause)

/** Persistence of the Account record itself, in AccountManager. */
interface AccountStore {
    fun list(): List<DavAccount>
    fun load(account: Account): DavAccount?
    fun save(davAccount: DavAccount)
    fun delete(account: Account)
}

// ---------------------------------------------------------------- http

/**
 * Builds the HTTP client for one Account: header interceptor plus an optional
 * KeyChain-backed client certificate.
 */
interface DavHttpClientFactory {
    fun create(davAccount: DavAccount): DavHttpClient
}

/**
 * [certificateOffered] reports whether the X509KeyManager alias callback actually fired,
 * which is the only honest answer to "was a certificate sent".
 */
interface DavHttpClient {
    val okHttp: OkHttpClient
    val certificateOffered: Boolean
}

// ---------------------------------------------------------------- sync state

/** Per-Collection state. Lives with the data in the provider, never in a separate database. */
data class CollectionState(
    val ctag: String? = null,
    val syncToken: String? = null,
    val supportsSyncCollection: Boolean? = null,
    val capabilityCheckedAt: Long = 0L,
    val lastSuccessAt: Long = 0L,
)

/** One item as listed by PROPFIND Depth:1. */
data class RemoteItem(val href: String, val etag: String?)

/**
 * Writes fetched resources into a platform provider. One implementation per authority.
 * The sync engine owns ordering, batching and the invariants; mappers own columns.
 */
interface ProviderMapper {
    val authority: String

    /** Must be called before any write; the provider reaps rows of unregistered accounts. */
    fun assertAccountRegistered(account: Account)

    fun readState(account: Account, collection: DavCollection): CollectionState
    fun writeState(account: Account, collection: DavCollection, state: CollectionState)

    /** Local (href -> ETag) for every row of this Collection. */
    fun localItems(account: Account, collection: DavCollection): Map<String, String?>

    /**
     * Upsert one batch. [resources] is href -> raw vCard/iCalendar text; [etags] is href -> ETag
     * for the same hrefs, written onto the rows in the same transaction as the bodies.
     *
     * The two must be persisted together: an ETag stored without its body (or vice versa) would
     * make the next run's diff against [localItems] lie about what is already present.
     */
    fun upsert(
        account: Account,
        collection: DavCollection,
        resources: Map<String, String>,
        etags: Map<String, String?>,
    ): Int

    /** Only ever called with a listing that completed. */
    fun deleteMissing(account: Account, collection: DavCollection, keepHrefs: Set<String>): Int

    /** Read-only backstop: clear DIRTY and re-apply server state. */
    fun clearDirty(account: Account, collection: DavCollection)
}
