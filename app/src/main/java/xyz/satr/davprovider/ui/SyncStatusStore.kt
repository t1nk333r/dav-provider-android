package xyz.satr.davprovider.ui

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import xyz.satr.davprovider.core.ErrorClass
import xyz.satr.davprovider.sync.SyncDeferralRecorder

/** How one Collection came out of the last run. [SKIPPED] is §5 class 3: information, not failure. */
internal enum class CollectionOutcome { OK, FAILED, SKIPPED }

internal data class CollectionReport(
    val collectionId: String,
    val outcome: CollectionOutcome,
    val errorClass: ErrorClass? = null,
    val summary: String? = null,
)

internal enum class AccountStatus { OK, PARTIAL, FAILED, NEVER_SYNCED }

/**
 * One authority's verdict from its most recent run.
 *
 * [status] and [summary] are the sync engine's per-run rollup, which knows about a terminal class
 * that stopped the run and about a failure that never reached a Collection. They are kept per
 * authority because the two authorities run separately and each is entitled to its own answer.
 */
internal data class AuthorityReport(
    val status: AccountStatus,
    val summary: String? = null,
)

/**
 * The last run of one account: an Account-wide rollup plus one entry per Collection, because one
 * broken Collection must never hide the state of the others.
 *
 * [summary] carries a run-level failure — the account vanished mid-run, the HTTP client could not
 * be built, or a terminal credential failure stopped the remaining Collections — which is the only
 * explanation when no Collection reports anything.
 *
 * [deferred] is §8's waiting state, and the one field here that no run produced: the last automatic
 * run did no work because the Account only syncs on unmetered networks. It survives leaving the
 * screen, and the next run that does happen clears it.
 */
internal data class AccountReport(
    val status: AccountStatus,
    val lastSyncAt: Long,
    val collections: List<CollectionReport>,
    val summary: String? = null,
    val deferred: Boolean = false,
    val authorities: Map<String, AuthorityReport> = emptyMap(),
) {
    /**
     * The verdict to display.
     *
     * The worst authority, not the last one to run: contacts and calendars finish at different
     * moments, and letting the later one speak meant a failed calendar was reported as "All
     * Collections synced" whenever contacts happened to succeed afterwards. The card then
     * contradicted the Collection line printed directly beneath it.
     */
    val composedStatus: AccountStatus
        get() = authorities.values.maxByOrNull { it.status.severity }?.status ?: status

    /** The reason to display: the failing authority's, which the last run may not be. */
    val composedSummary: String?
        get() = authorities.values
            .filter { it.status.severity > AccountStatus.OK.severity }
            .maxByOrNull { it.status.severity }
            ?.summary
            ?: summary
}

/**
 * How bad a status is, so that two authorities can be compared rather than ordered by when they
 * happened to run. NEVER_SYNCED sits below OK because an authority that has not reported says
 * nothing about the account.
 */
internal val AccountStatus.severity: Int
    get() = when (this) {
        AccountStatus.NEVER_SYNCED -> 0
        AccountStatus.OK -> 1
        AccountStatus.PARTIAL -> 2
        AccountStatus.FAILED -> 3
    }

/**
 * Per-Account sync status, kept in AccountManager userdata next to the account record.
 *
 * It lives here rather than in a file of its own so that it shares the account's lifetime: the
 * status can never outlive the account it describes, and never describes an account twice.
 * Only §5 fields are stored — the classifier's summary, the class, the status and the method —
 * never a response body, a header value or a password.
 *
 * The rollup is the sync engine's, not this store's: it is the side that knows about terminal
 * classes and about a run that stopped early.
 */
internal class SyncStatusStore(context: Context) : SyncDeferralRecorder {

    private val manager = AccountManager.get(context.applicationContext)

    /** Null when nothing has been recorded, or when the record cannot be read. */
    fun read(account: Account): AccountReport? {
        val raw = manager.getUserData(account, KEY) ?: return null
        return runCatching { decode(raw) }.getOrNull()
    }

    /**
     * A run happened, so this carries the truth and any earlier deferral is over. That is what makes
     * a manual sync — or the next run on Wi-Fi — clear the waiting state without anyone asking.
     *
     * The per-Collection list is **merged, not replaced**. Contacts and calendars are two separate
     * runs that each report only their own Collections, so replacing the list let the second
     * authority erase the first: a calendar that had just synced displayed "not synced yet"
     * because the contacts run that followed it wrote a list it was not in. Collection ids are
     * derived from the Collection URL and are therefore unique across authorities, which is what
     * makes merging by id safe.
     *
     * A Collection that is deselected keeps its last entry rather than losing it, and the lookup
     * that renders the row only asks about Collections the Account still has, so a stale entry is
     * never shown — it is superseded the next time that Collection runs.
     */
    fun record(
        account: Account,
        authority: String,
        atMillis: Long,
        status: AccountStatus,
        summary: String?,
        collections: List<CollectionReport>,
    ) {
        val previous = read(account)
        val merged = mergeCollectionReports(previous?.collections.orEmpty(), collections)
        val authorities = previous?.authorities.orEmpty() +
            (authority to AuthorityReport(status, summary))
        manager.setUserData(
            account,
            KEY,
            encode(
                AccountReport(
                    status = status,
                    lastSyncAt = atMillis,
                    collections = merged,
                    summary = summary,
                    authorities = authorities,
                ),
            ),
        )
    }

    /**
     * §8: an automatic run did nothing, and the user can see it.
     *
     * Everything a run recorded is kept — the status and the last sync time stay the last run's —
     * because the deferral says nothing about how the Account last fared. An Account that has never
     * completed a run still gets a record, since "not synced yet, waiting for Wi-Fi" is exactly the
     * state that must not be silent.
     */
    override fun recordDeferred(account: Account) {
        val previous = read(account)
        manager.setUserData(
            account,
            KEY,
            encode(
                AccountReport(
                    status = previous?.status ?: AccountStatus.NEVER_SYNCED,
                    lastSyncAt = previous?.lastSyncAt ?: 0L,
                    collections = previous?.collections.orEmpty(),
                    summary = previous?.summary,
                    deferred = true,
                ),
            ),
        )
    }

    fun clear(account: Account) {
        manager.setUserData(account, KEY, null)
    }

    private fun encode(report: AccountReport): String {
        val collections = JSONArray()
        report.collections.forEach { entry ->
            collections.put(
                JSONObject().apply {
                    put(KEY_COLLECTION_ID, entry.collectionId)
                    put(KEY_OUTCOME, entry.outcome.name)
                    entry.errorClass?.let { put(KEY_ERROR_CLASS, it.name) }
                    entry.summary?.let { put(KEY_SUMMARY, it) }
                },
            )
        }
        return JSONObject().apply {
            put(KEY_STATUS, report.status.name)
            put(KEY_LAST_SYNC, report.lastSyncAt)
            report.summary?.let { put(KEY_SUMMARY, it) }
            // Written only when set, so a record from before this field existed decodes unchanged.
            if (report.deferred) put(KEY_DEFERRED, true)
            put(KEY_COLLECTIONS, collections)
            if (report.authorities.isNotEmpty()) {
                put(
                    KEY_AUTHORITIES,
                    JSONObject().apply {
                        report.authorities.forEach { (authority, verdict) ->
                            put(
                                authority,
                                JSONObject().apply {
                                    put(KEY_STATUS, verdict.status.name)
                                    verdict.summary?.let { put(KEY_SUMMARY, it) }
                                },
                            )
                        }
                    },
                )
            }
        }.toString()
    }

    private fun decode(raw: String): AccountReport {
        val root = JSONObject(raw)
        val collections = root.optJSONArray(KEY_COLLECTIONS) ?: JSONArray()
        val entries = ArrayList<CollectionReport>(collections.length())
        for (index in 0 until collections.length()) {
            val entry = collections.getJSONObject(index)
            entries += CollectionReport(
                collectionId = entry.getString(KEY_COLLECTION_ID),
                outcome = CollectionOutcome.valueOf(entry.getString(KEY_OUTCOME)),
                errorClass = entry.optString(KEY_ERROR_CLASS).takeIf { it.isNotEmpty() }
                    ?.let { ErrorClass.valueOf(it) },
                summary = entry.optString(KEY_SUMMARY).takeIf { it.isNotEmpty() },
            )
        }
        return AccountReport(
            status = AccountStatus.valueOf(root.getString(KEY_STATUS)),
            lastSyncAt = root.optLong(KEY_LAST_SYNC),
            collections = entries,
            summary = root.optString(KEY_SUMMARY).takeIf { it.isNotEmpty() },
            deferred = root.optBoolean(KEY_DEFERRED, false),
            authorities = decodeAuthorities(root.optJSONObject(KEY_AUTHORITIES)),
        )
    }

    /**
     * A record written before the per-authority verdicts existed has none, and decoding one is not
     * an error: the top-level fields it does have remain the fallback the card renders until each
     * authority reports once.
     */
    private fun decodeAuthorities(raw: JSONObject?): Map<String, AuthorityReport> {
        if (raw == null) return emptyMap()
        val decoded = LinkedHashMap<String, AuthorityReport>(raw.length())
        raw.keys().forEach { authority ->
            val entry = raw.optJSONObject(authority) ?: return@forEach
            val status = runCatching { AccountStatus.valueOf(entry.getString(KEY_STATUS)) }
                .getOrNull() ?: return@forEach
            decoded[authority] = AuthorityReport(
                status = status,
                summary = entry.optString(KEY_SUMMARY).takeIf { it.isNotEmpty() },
            )
        }
        return decoded
    }

    private companion object {
        const val KEY = "dav_sync_status_v1"
        const val KEY_STATUS = "status"
        const val KEY_LAST_SYNC = "lastSyncAt"
        const val KEY_DEFERRED = "deferred"
        const val KEY_COLLECTIONS = "collections"
        const val KEY_COLLECTION_ID = "id"
        const val KEY_OUTCOME = "outcome"
        const val KEY_ERROR_CLASS = "errorClass"
        const val KEY_SUMMARY = "summary"
        const val KEY_AUTHORITIES = "authorities"
    }
}

/**
 * Folds one run's per-Collection outcomes into what earlier runs recorded.
 *
 * Contacts and calendars are separate runs, and each reports only the Collections of its own
 * authority, so this has to accumulate rather than replace: replacing is what made a freshly synced
 * calendar read "not synced yet", because the contacts run that followed wrote a list its
 * Collection was not in. Collection ids come from the Collection URL, so the same id from either
 * authority is the same Collection and a later answer for one is simply newer.
 *
 * The order is previous-then-new so an id keeps its position and the list does not reshuffle as
 * authorities take turns.
 */
internal fun mergeCollectionReports(
    previous: List<CollectionReport>,
    reported: List<CollectionReport>,
): List<CollectionReport> {
    if (previous.isEmpty()) return reported
    val byId = LinkedHashMap<String, CollectionReport>(previous.size + reported.size)
    previous.forEach { byId[it.collectionId] = it }
    reported.forEach { byId[it.collectionId] = it }
    return byId.values.toList()
}
