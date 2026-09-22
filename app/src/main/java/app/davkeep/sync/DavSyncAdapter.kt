package app.davkeep.sync

import android.accounts.Account
import android.content.AbstractThreadedSyncAdapter
import android.content.ContentProviderClient
import android.content.Context
import android.content.SyncResult
import android.os.Bundle
import kotlinx.coroutines.runBlocking

/**
 * Runs one sync for one authority on the thread the framework gives it.
 *
 * Scheduling, backing off and cancelling are the framework's, not this class's: a run is started
 * when asked and stopped when told, and nothing here retries.
 *
 * [extras] is handed to the engine unchanged because it is the only place a run can learn that the
 * user started it: §8 lets a manual sync bypass the app's own constraints, and this call is where
 * the framework's request becomes that run.
 */
class DavSyncAdapter(
    context: Context,
    private val authority: String,
) : AbstractThreadedSyncAdapter(context.applicationContext, AUTO_INITIALIZE, ALLOW_PARALLEL_SYNCS) {

    private val appContext: Context = context.applicationContext

    /**
     * Set when the framework cancels this sync.
     *
     * Cancellation is cooperative, and this is the only signal available: the platform does not
     * expose a cancel query. The run stops between Collections and between batches, and what it has
     * written so far stands — no CTag was recorded for a Collection that did not finish.
     */
    @Volatile
    private var cancelled = false

    override fun onSyncCanceled() {
        cancelled = true
        super.onSyncCanceled()
    }

    override fun onSyncCanceled(thread: Thread) {
        cancelled = true
        super.onSyncCanceled(thread)
    }

    override fun onPerformSync(
        account: Account,
        extras: Bundle,
        authority: String,
        provider: ContentProviderClient?,
        syncResult: SyncResult,
    ) {
        // Built per run: the collaborators are per-Account and per-authority, and an HTTP client
        // must not outlive the run whose certificate state it reports.
        val engine = SyncWiring.requireProvider().create(appContext, authority)
        runBlocking {
            engine.sync(account, extras, syncResult) { cancelled }
        }
    }

    private companion object {
        /** `isAlwaysSyncable="true"` in the sync-adapter XML initialises isSyncable; nothing to do here. */
        const val AUTO_INITIALIZE = false

        /** Must match `allowParallelSyncs="false"`: Collections are sequential within one run. */
        const val ALLOW_PARALLEL_SYNCS = false
    }
}
