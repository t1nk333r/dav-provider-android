package app.davkeep.ui

import android.content.Context
import app.davkeep.R
import app.davkeep.core.ErrorClass
import app.davkeep.sync.AccountSyncReport
import app.davkeep.sync.AccountSyncStatus
import app.davkeep.sync.CollectionOutcome as SyncCollectionOutcome
import app.davkeep.sync.SyncReporter
import app.davkeep.sync.SyncWiring

/**
 * Records what a run did: the per-Collection status the account screen reads, one entry per
 * Collection and one for the run in the ring-buffer log, and a notification when the failure is one
 * that needs the user.
 *
 * The engine hands over the whole run at once, so nothing here decides severity — the engine
 * knows which classes are terminal and whether it stopped early. This only translates, which is why
 * a Collection that reported the informational class 3 is logged as information rather than as the
 * failure its error object could look like.
 */
internal class UiSyncReporter(context: Context) : SyncReporter {

    private val appContext = context.applicationContext

    override fun onSyncFinished(report: AccountSyncReport) {
        val store = SyncStatusStore(appContext)
        val log = SyncLog(appContext)
        SyncNotifications(appContext).dispatch(report)
        val collections = report.collections.map { outcome ->
            CollectionReport(
                collectionId = outcome.collectionId,
                outcome = collectionOutcome(outcome),
                errorClass = outcome.error?.errorClass,
                summary = outcome.error?.summary ?: plainSummary(outcome),
            )
        }
        store.record(
            account = report.account,
            authority = report.authority,
            atMillis = report.finishedAt,
            status = accountStatus(report.status),
            summary = runSummary(report),
            collections = collections,
            // §8's evidence needs to know whether the framework asked for this run or the user did.
            automatic = report.automatic,
        )
        // The whole run in one write. The log is read and rewritten per call, so a Collection apiece
        // plus the run's own line was that many rewrites of a 200-line file, on the sync thread.
        val entries = report.collections.map { outcome ->
            log.entry(
                account = report.account.name,
                collectionId = outcome.collectionId,
                summary = outcome.error?.summary
                    ?: plainSummary(outcome)
                    ?: appContext.getString(R.string.log_collection_ok),
                errorClass = outcome.error?.errorClass,
                httpStatus = outcome.error?.httpStatus,
                method = outcome.error?.requestMethod,
                // §5: class 3 is the server talking, not the Collection failing.
                level = when {
                    outcome.failed -> SyncLog.Level.ERROR
                    // Not a failure — nothing is left to retry — but the one outcome where the user's
                    // own edit was given up in favour of the server's, which is not a quiet "ok".
                    outcome.conflicts.isNotEmpty() -> SyncLog.Level.WARN
                    else -> SyncLog.Level.INFO
                },
                authority = report.authority,
                displayName = outcome.displayName,
                // The classifier observed the handshake on every exchange it read, including the
                // ones that ended well, so this is recorded wherever it exists and never guessed.
                certificateOffered = outcome.error?.certificateOffered,
                written = outcome.written,
                deleted = outcome.deleted,
                // What step U did, so "was my edit sent" is answerable from the log rather than
                // from the server. Refused counts as pending: the edit is still on the phone.
                uploaded = outcome.uploaded,
                pending = outcome.pending + outcome.refused,
                unchanged = outcome.unchanged,
                firstBodyLine = outcome.error?.firstBodyLine,
                davCondition = outcome.error?.davCondition,
                cause = logCause(outcome.error?.cause),
            )
        } + log.entry(
            // Exactly one run-level entry, so "did this run sync anything" is one line to read.
            account = report.account.name,
            collectionId = null,
            summary = logRunSummary(report),
            errorClass = report.error?.errorClass,
            httpStatus = report.error?.httpStatus,
            method = report.error?.requestMethod,
            level = logRunLevel(report),
            authority = report.authority,
            certificateOffered = report.error?.certificateOffered,
            written = report.collections.sumOf { it.written },
            deleted = report.collections.sumOf { it.deleted },
            uploaded = report.collections.sumOf { it.uploaded },
            pending = report.collections.sumOf { it.pending + it.refused },
            firstBodyLine = report.error?.firstBodyLine,
            davCondition = report.error?.davCondition,
            cause = logCause(report.error?.cause),
        )
        log.appendAll(entries)
    }

    /**
     * §5 class 3 is information: it must not mark a Collection failed nor contribute to Partial.
     *
     * A Collection that ended incomplete is the mirror case — no error object at all, and yet not
     * OK, because a run that fetched less than the listing named has nothing to report as success.
     * It reads as failed here so the card shows Partial and the row says which Collection; it
     * notifies nothing, because [SyncNotifications] fires on terminal error classes and this has
     * none.
     *
     * A Collection with something still to upload is the same shape from the write side, and reads
     * the same way: the list of edits still on the phone is not a list of successes. It notifies
     * nothing either, for the same reason.
     */
    private fun collectionOutcome(outcome: SyncCollectionOutcome) = when {
        outcome.pending > 0 -> CollectionOutcome.FAILED
        outcome.incomplete -> CollectionOutcome.FAILED
        outcome.error == null -> CollectionOutcome.OK
        outcome.error.errorClass == ErrorClass.ORIGIN_REFUSED_INFO -> CollectionOutcome.SKIPPED
        else -> CollectionOutcome.FAILED
    }

    /**
     * What a run has to say about a Collection when no request failed.
     *
     * Null is the ordinary answer — the caller writes "ok". The ones that are not null are the run
     * admitting to something: a change of the phone's that did not leave it, a change the server's
     * copy replaced, an edit refused because the Collection may not be written, and the three from
     * the read side — two of them less than the Collection had to tell it, and the third the one
     * recovery worth a line, since a token the server has forgotten explains a run that suddenly
     * cost a whole listing.
     *
     * The write-side ones come first because they are about the user's own edits: a run that says
     * both that it did not send something and that the server listed fewer items than it asked for
     * is a run whose reader needs the first answer most.
     */
    private fun plainSummary(outcome: SyncCollectionOutcome): String? = when {
        outcome.pending > 0 -> quantity(R.plurals.log_collection_pending, outcome.pending)
        outcome.conflicts.isNotEmpty() -> quantity(R.plurals.log_collection_conflict, outcome.conflicts.size)
        outcome.refused > 0 -> quantity(R.plurals.log_collection_refused, outcome.refused)
        outcome.truncated -> appContext.getString(R.string.log_collection_truncated)
        outcome.missing > 0 -> quantity(R.plurals.log_collection_missing, outcome.missing)
        outcome.relisted -> appContext.getString(R.string.log_collection_relisted)
        else -> null
    }

    /** A count with its own wording, so no caller has to pass the number twice. */
    private fun quantity(plural: Int, count: Int): String =
        appContext.resources.getQuantityString(plural, count, count)

    private fun accountStatus(status: AccountSyncStatus) = when (status) {
        AccountSyncStatus.OK -> AccountStatus.OK
        AccountSyncStatus.PARTIAL -> AccountStatus.PARTIAL
        AccountSyncStatus.FAILED -> AccountStatus.FAILED
    }

    /**
     * The card's detail: a failure's own words, or the one thing a clean run still has to admit to.
     *
     * Refused edits are that thing. Nothing failed — the listing completed, the account is not
     * Partial — and nothing is left to retry either, so the summary is the only place the reason
     * those edits did not leave the phone can be said.
     */
    private fun runSummary(report: AccountSyncReport): String? {
        val refused = report.collections.sumOf { it.refused }
        return when {
            report.error != null -> report.error.summary
            report.aborted -> appContext.getString(R.string.log_run_aborted)
            refused > 0 -> quantity(R.plurals.status_detail_refused, refused)
            else -> null
        }
    }

    /**
     * A run that failed is an error; a run that only partly worked is something to notice. Class 3
     * never lands here as a failure — the engine keeps it out of Partial.
     *
     * A run where the server replaced an edit of the user's is worth noticing for the same reason:
     * nothing failed and there is nothing left to retry, but the run is not one to skim past either.
     */
    private fun logRunLevel(report: AccountSyncReport): SyncLog.Level = when {
        report.error != null || report.status == AccountSyncStatus.FAILED -> SyncLog.Level.ERROR
        report.status == AccountSyncStatus.PARTIAL -> SyncLog.Level.WARN
        report.collections.any { it.conflicts.isNotEmpty() } -> SyncLog.Level.WARN
        else -> SyncLog.Level.INFO
    }

    /**
     * The log's run line, which unlike [runSummary] says something even when the run went well: a
     * ring buffer is read after the fact, and "ok" is an answer, whereas a missing line is not.
     *
     * A run that sent something says so. An upload-only run is the case that needs it: it lists
     * nothing, so the Collection lines under it carry no counts, and without this line the one run
     * that changed the server would read exactly like a run that did nothing.
     */
    private fun logRunSummary(report: AccountSyncReport): String {
        val sent = report.collections.sumOf { it.uploaded }
        return when {
            report.error != null -> report.error.summary
            report.aborted -> appContext.getString(R.string.log_run_aborted)
            report.collections.isEmpty() -> appContext.getString(R.string.log_no_collections)
            report.status == AccountSyncStatus.PARTIAL -> appContext.getString(
                R.string.status_detail_partial,
                report.collections.count { it.failed },
                report.collections.size,
            )
            report.status == AccountSyncStatus.FAILED -> appContext.getString(
                R.string.status_detail_failed,
                report.collections.size,
            )
            sent > 0 -> quantity(R.plurals.log_run_uploaded, sent)
            // Nothing failed and nothing is left to retry, but edits did not leave the phone, and a
            // bare "ok" over a contact the user has just made is the silence this app exists to
            // avoid. Said here as well as on the Collection's own line, because the run line is the
            // one a reader skims.
            refusedIn(report) > 0 -> quantity(R.plurals.status_detail_refused, refusedIn(report))
            else -> appContext.getString(R.string.log_collection_ok)
        }
    }

    private fun refusedIn(report: AccountSyncReport): Int = report.collections.sumOf { it.refused }
}

/**
 * The wiring entry point for the process: installs the reporter the sync engine hands each run to.
 *
 * Idempotent, so the Application class and the screens may both call it; a run that happens in a
 * process where no screen was ever opened is the reason the Application exists.
 */
object UiSyncWiring {

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            SyncWiring.install(context.applicationContext, UiSyncReporter(context.applicationContext))
            installed = true
        }
    }
}
