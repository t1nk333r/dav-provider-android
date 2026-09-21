package xyz.satr.davprovider.ui

import android.content.Context
import xyz.satr.davprovider.R
import xyz.satr.davprovider.core.ErrorClass
import xyz.satr.davprovider.sync.AccountSyncReport
import xyz.satr.davprovider.sync.AccountSyncStatus
import xyz.satr.davprovider.sync.CollectionOutcome as SyncCollectionOutcome
import xyz.satr.davprovider.sync.SyncReporter
import xyz.satr.davprovider.sync.SyncWiring

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
                level = if (outcome.failed) SyncLog.Level.ERROR else SyncLog.Level.INFO,
                authority = report.authority,
                displayName = outcome.displayName,
                // The classifier observed the handshake on every exchange it read, including the
                // ones that ended well, so this is recorded wherever it exists and never guessed.
                certificateOffered = outcome.error?.certificateOffered,
                written = outcome.written,
                deleted = outcome.deleted,
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
     */
    private fun collectionOutcome(outcome: SyncCollectionOutcome) = when {
        outcome.incomplete -> CollectionOutcome.FAILED
        outcome.error == null -> CollectionOutcome.OK
        outcome.error.errorClass == ErrorClass.ORIGIN_REFUSED_INFO -> CollectionOutcome.SKIPPED
        else -> CollectionOutcome.FAILED
    }

    /**
     * What a run has to say about a Collection when no request failed.
     *
     * Null is the ordinary answer — the caller writes "ok". The three that are not null are the
     * run admitting to something: two of them are less than the Collection had to tell it, and the
     * third is the one recovery worth a line, since a token the server has forgotten explains a run
     * that suddenly cost a whole listing.
     */
    private fun plainSummary(outcome: SyncCollectionOutcome): String? = when {
        outcome.truncated -> appContext.getString(R.string.log_collection_truncated)
        outcome.missing > 0 -> appContext.resources.getQuantityString(
            R.plurals.log_collection_missing,
            outcome.missing,
            outcome.missing,
        )
        outcome.relisted -> appContext.getString(R.string.log_collection_relisted)
        else -> null
    }

    private fun accountStatus(status: AccountSyncStatus) = when (status) {
        AccountSyncStatus.OK -> AccountStatus.OK
        AccountSyncStatus.PARTIAL -> AccountStatus.PARTIAL
        AccountSyncStatus.FAILED -> AccountStatus.FAILED
    }

    private fun runSummary(report: AccountSyncReport): String? = when {
        report.error != null -> report.error.summary
        report.aborted -> appContext.getString(R.string.log_run_aborted)
        else -> null
    }

    /**
     * A run that failed is an error; a run that only partly worked is something to notice. Class 3
     * never lands here as a failure — the engine keeps it out of Partial.
     */
    private fun logRunLevel(report: AccountSyncReport): SyncLog.Level = when {
        report.error != null || report.status == AccountSyncStatus.FAILED -> SyncLog.Level.ERROR
        report.status == AccountSyncStatus.PARTIAL -> SyncLog.Level.WARN
        else -> SyncLog.Level.INFO
    }

    /**
     * The log's run line, which unlike [runSummary] says something even when the run went well: a
     * ring buffer is read after the fact, and "ok" is an answer, whereas a missing line is not.
     */
    private fun logRunSummary(report: AccountSyncReport): String = when {
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
        else -> appContext.getString(R.string.log_collection_ok)
    }
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
