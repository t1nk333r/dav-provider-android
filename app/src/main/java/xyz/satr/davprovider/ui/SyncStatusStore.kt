package xyz.satr.davprovider.ui

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import xyz.satr.davprovider.core.ErrorClass

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
 */
internal data class AccountReport(
    val status: AccountStatus,
    val lastSyncAt: Long,
    val collections: List<CollectionReport>,
    val summary: String? = null,
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
internal class SyncStatusStore(context: Context) {

    private val manager = AccountManager.get(context.applicationContext)

    /** Null when nothing has been recorded, or when the record cannot be read. */
    fun read(account: Account): AccountReport? {
        val raw = manager.getUserData(account, KEY) ?: return null
        return runCatching { decode(raw) }.getOrNull()
    }

    fun record(
        account: Account,
        atMillis: Long,
        status: AccountStatus,
        summary: String?,
        collections: List<CollectionReport>,
    ) {
        manager.setUserData(account, KEY, encode(AccountReport(status, atMillis, collections, summary)))
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
        )
    }

    private companion object {
        const val KEY = "dav_sync_status_v1"
        const val KEY_STATUS = "status"
        const val KEY_LAST_SYNC = "lastSyncAt"
        const val KEY_COLLECTIONS = "collections"
        const val KEY_COLLECTION_ID = "id"
        const val KEY_OUTCOME = "outcome"
        const val KEY_ERROR_CLASS = "errorClass"
        const val KEY_SUMMARY = "summary"
    }
}
