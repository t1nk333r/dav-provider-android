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
 * Records what a run did: the per-Collection status the account screen reads, one line per attempt
 * in the ring-buffer log, and a notification when the failure is one that needs the user.
 *
 * The engine hands over the whole run at once, so nothing here decides severity — the engine
 * knows which classes are terminal and whether it stopped early. This only translates.
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
                summary = outcome.error?.summary,
            )
        }
        store.record(
            account = report.account,
            atMillis = report.finishedAt,
            status = accountStatus(report.status),
            summary = runSummary(report),
            collections = collections,
        )
        report.collections.forEach { outcome ->
            log.append(
                account = report.account.name,
                collectionId = outcome.collectionId,
                summary = outcome.error?.summary
                    ?: appContext.getString(R.string.log_collection_ok, outcome.written, outcome.deleted),
                errorClass = outcome.error?.errorClass,
                httpStatus = outcome.error?.httpStatus,
                method = outcome.error?.requestMethod,
            )
        }
        report.error?.let { error ->
            log.append(
                account = report.account.name,
                collectionId = null,
                summary = error.summary,
                errorClass = error.errorClass,
                httpStatus = error.httpStatus,
                method = error.requestMethod,
            )
        }
        if (report.collections.isEmpty() && report.error == null) {
            log.append(
                account = report.account.name,
                collectionId = null,
                summary = appContext.getString(R.string.log_no_collections),
                errorClass = null,
                httpStatus = null,
                method = null,
            )
        }
    }

    /** §5 class 3 is information: it must not mark a Collection failed nor contribute to Partial. */
    private fun collectionOutcome(outcome: SyncCollectionOutcome) = when {
        outcome.error == null -> CollectionOutcome.OK
        outcome.error.errorClass == ErrorClass.ORIGIN_REFUSED_INFO -> CollectionOutcome.SKIPPED
        else -> CollectionOutcome.FAILED
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
