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
)

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
     */
    fun record(
        account: Account,
        atMillis: Long,
        status: AccountStatus,
        summary: String?,
        collections: List<CollectionReport>,
    ) {
        manager.setUserData(account, KEY, encode(AccountReport(status, atMillis, collections, summary)))
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
        )
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
    }
}
