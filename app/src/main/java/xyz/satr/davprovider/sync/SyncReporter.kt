package xyz.satr.davprovider.sync

import android.accounts.Account
import xyz.satr.davprovider.core.DavCollection
import xyz.satr.davprovider.core.ErrorClass
import xyz.satr.davprovider.core.SyncError

/** The Account-level rollup of §5: one broken Collection must never mask the others. */
enum class AccountSyncStatus { OK, PARTIAL, FAILED }

/**
 * What one Collection's part of a run produced.
 *
 * [error] carries what the app observed, including the informational class 3 — which is why
 * [failed] exists separately: class 3 must not mark a Collection failed nor contribute to Partial.
 */
data class CollectionOutcome(
    val collectionId: String,
    val displayName: String? = null,
    val error: SyncError? = null,
    /** Rows the mapper wrote for this Collection. */
    val written: Int = 0,
    /** Rows removed because the server's listing no longer contained them. */
    val deleted: Int = 0,
    /** True when the cheap check showed the Collection had not changed at all. */
    val unchanged: Boolean = false,
) {
    internal constructor(
        collection: DavCollection,
        error: SyncError? = null,
        written: Int = 0,
        deleted: Int = 0,
        unchanged: Boolean = false,
    ) : this(collection.id, collection.displayName, error, written, deleted, unchanged)

    val failed: Boolean get() = error != null && error.errorClass != ErrorClass.ORIGIN_REFUSED_INFO
}

/** Everything one run of one Account did. */
data class AccountSyncReport(
    val account: Account,
    val authority: String,
    val status: AccountSyncStatus,
    val collections: List<CollectionOutcome>,
    /** True when a terminal auth failure stopped the run before the remaining Collections. */
    val aborted: Boolean,
    /** Set when the run failed outside any Collection — no usable Account, no usable client. */
    val error: SyncError?,
    val finishedAt: Long,
)

/**
 * Receives the result of every run.
 *
 * Called once, after the last Collection, so a reader never sees half a run; persisting it is the
 * reader's business — the engine keeps no status of its own.
 */
fun interface SyncReporter {
    fun onSyncFinished(report: AccountSyncReport)
}
