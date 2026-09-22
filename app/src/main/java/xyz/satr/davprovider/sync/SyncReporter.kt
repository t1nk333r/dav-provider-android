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
 *
 * [incomplete] is the other half of that separation, in the opposite direction: a run can learn
 * less than the Collection had to tell it without any request failing — a body the server listed
 * and then would not hand over, a listing it admitted cutting short. Those runs must not read as
 * OK, because the state they would otherwise store makes the next run skip the Collection
 * entirely; and they must not notify, because nothing about them is terminal and the next run may
 * well succeed. Carrying it as its own flag, rather than an invented error, is what gets both.
 */
data class CollectionOutcome(
    val collectionId: String,
    val displayName: String? = null,
    val error: SyncError? = null,
    /** Rows the mapper wrote for this Collection. */
    val written: Int = 0,
    /** Rows removed because the server's listing no longer contained them. */
    val deleted: Int = 0,
    /** Changes step U is done with: the server answered for them, or there was nothing to send. */
    val uploaded: Int = 0,
    /** Changes still on the phone: rows this run could not send, or rows that moved under an answer. */
    val pending: Int = 0,
    /** Changes given up because the Collection is not writable. */
    val refused: Int = 0,
    /** The keys of the changes the server's copy replaced under an edit. */
    val conflicts: List<String> = emptyList(),
    /** True when the cheap check showed the Collection had not changed at all. */
    val unchanged: Boolean = false,
    /** Members this run asked for by href and the server never handed over. */
    val missing: Int = 0,
    /** True when the server said it was holding members back rather than listing them all. */
    val truncated: Boolean = false,
    /** True when a sync token the server no longer knew made this run list in full instead. */
    val relisted: Boolean = false,
) {
    internal constructor(
        collection: DavCollection,
        error: SyncError? = null,
        written: Int = 0,
        deleted: Int = 0,
        uploaded: Int = 0,
        pending: Int = 0,
        refused: Int = 0,
        conflicts: List<String> = emptyList(),
        unchanged: Boolean = false,
        missing: Int = 0,
        truncated: Boolean = false,
        relisted: Boolean = false,
    ) : this(
        collection.id, collection.displayName, error, written, deleted, uploaded, pending, refused,
        conflicts, unchanged, missing, truncated, relisted,
    )

    /** True when this run ended knowing less than the Collection was going to tell it. */
    val incomplete: Boolean get() = missing > 0 || truncated

    /**
     * True when the Collection did not finish this run.
     *
     * [pending] is the third way it can fail to finish: the listing may have completed perfectly and
     * the Collection still has something of this phone's that never left it. A conflict is
     * deliberately not one of these — the row was given up and the same run fetched the server's
     * version onto it, so there is nothing left to retry, which is the difference between "come back"
     * and "this is what the server has".
     */
    val failed: Boolean
        get() = incomplete || pending > 0 ||
            (error != null && error.errorClass != ErrorClass.ORIGIN_REFUSED_INFO)
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
    /**
     * Whether the framework started this run, as opposed to the user asking for it.
     *
     * The distinction is §8's evidence and nothing else's: an automatic run is a scheduled slot
     * that either arrived or did not, while a manual one happens whenever the user presses a button
     * and therefore says nothing about the schedule in either direction. Without this flag a manual
     * run would look exactly like an automatic one, and a "Sync now" between two missed slots would
     * be read as the schedule recovering.
     */
    val automatic: Boolean,
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
