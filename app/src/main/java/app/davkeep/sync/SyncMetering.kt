package app.davkeep.sync

import android.accounts.Account
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether the network a run would go over is metered.
 *
 * §8's unmetered-only rule is this app's own: the platform has no sync setting for it, so the rule
 * is this project's code, and the part of it that can be wrong is the decision rather than the
 * system call. Taking the answer as a seam is what lets that decision be exercised without a
 * device, a SIM or a network.
 */
fun interface NetworkMetering {
    fun isMetered(): Boolean
}

/**
 * The real answer, from the system's own view of the active network.
 *
 * "No network at all" counts as metered. It is not a network the user has agreed to spend, nothing
 * can be synced over it, and deferring costs nothing while the framework retries on its own.
 */
internal class ConnectivityManagerMetering(context: Context) : NetworkMetering {

    private val connectivity: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun isMetered(): Boolean {
        val manager = connectivity ?: return true
        val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
    }
}

/**
 * Records that an automatic run did nothing because §8's unmetered-only setting is on.
 *
 * Separate from [SyncReporter] on purpose: no run happened and no Collection was considered, so
 * reporting this as a run's result would replace the Account's real status with a blank one. The
 * reader is the account screen, which shows the state instead of inferring it from a queued sync
 * and the network in use at the moment someone happens to look.
 */
fun interface SyncDeferralRecorder {
    fun recordDeferred(account: Account)
}

/** What a run may do, decided before it has done anything. */
internal sealed interface RunDecision {

    /** §8 constrains this run in no way: it may do its work. */
    data object Proceed : RunDecision

    /** The Account has nothing selected to sync for this authority, and this run is not the user asking. */
    data object NothingToDo : RunDecision

    /**
     * §8's deferral: the setting is on and the network is metered, so the run does no work and asks
     * the framework to come back at [untilSeconds] — seconds since the epoch, which is the unit
     * [android.content.SyncResult.delayUntil] speaks.
     */
    data class Defer(val untilSeconds: Long) : RunDecision
}

/**
 * §8's conditions on a run — the ones the app imposes on top of the platform's.
 *
 * The check is here, before the run reads a credential or opens a connection, because everything it
 * guards against is spending: an automatic run on a metered network with the setting on does no
 * work at all and asks the framework to try again a few minutes later. The delay is minutes rather
 * than the interval because what it waits for is the network changing, not the clock; and the
 * periodic sync is the real retry, so a short one costs nothing.
 *
 * A manual run is the user overriding their own setting, so it bypasses this whole decision —
 * including the early exit for an Account with nothing selected. A "Sync now" that silently did
 * nothing is exactly the opacity this app exists to remove.
 */
internal class SyncPreflight(
    private val network: NetworkMetering,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun decide(manual: Boolean, unmeteredOnly: Boolean, hasWork: Boolean): RunDecision = when {
        // Checked first, and not merely as a shortcut: the user asking outranks both rules below.
        manual -> RunDecision.Proceed
        !hasWork -> RunDecision.NothingToDo
        unmeteredOnly && network.isMetered() -> RunDecision.Defer(clock() / 1000 + DEFERRED_RETRY_SECONDS)
        else -> RunDecision.Proceed
    }

    private companion object {
        /** A few minutes ahead: long enough not to spin, short enough not to look stuck. */
        const val DEFERRED_RETRY_SECONDS = 5L * 60
    }
}
