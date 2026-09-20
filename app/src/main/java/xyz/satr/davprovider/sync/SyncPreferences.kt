package xyz.satr.davprovider.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context

/**
 * §8's per-Account sync settings — the ones the platform has no setting for.
 *
 * Kept in the Account's own userdata, for the same reason the run status is: it describes one
 * Account, so it must disappear with it and can never name an Account that is gone.
 *
 * Unmetered-only is **off** by default. A fresh install has to sync without the user first finding
 * a setting, and the setting's purpose is to stop traffic the user has decided they do not want —
 * a decision nobody has made yet at install time.
 *
 * The walk's timestamp is here for the same reason and is not a setting: it describes one Account,
 * and it is the Account's own state rather than a second source of truth beside the record — it must
 * disappear with the Account, and it must never name one that is gone.
 *
 * The interval defaults to [MIN_INTERVAL_SECONDS], which is also the floor of the values offered and
 * the floor of anything this class will store. Fifteen minutes is not a preference: the framework
 * runs a periodic sync as a persisted `JobScheduler` job whose minimum period is fifteen minutes, so
 * a smaller number would be a promise the platform does not keep — the job would run every fifteen
 * minutes anyway while the screen claimed a shorter interval. A longer interval is a real choice,
 * and the longest one offered is a day, past which "Sync now" is the honest answer rather than a
 * schedule.
 */
class SyncPreferences(context: Context) {

    private val manager = AccountManager.get(context.applicationContext)

    /** Whether automatic runs of this Account wait for an unmetered network. */
    fun unmeteredOnly(account: Account): Boolean = manager.getUserData(account, UNMETERED_KEY) == TRUE

    fun setUnmeteredOnly(account: Account, enabled: Boolean) {
        manager.setUserData(account, UNMETERED_KEY, if (enabled) TRUE else null)
    }

    /**
     * How often the framework syncs this Account.
     *
     * The value is clamped on the way in *and* on the way out: userdata this build did not write —
     * an older build's, or a half-written one — must not become an interval the platform reads as
     * "as often as possible", which is how a sync loop starts.
     */
    fun intervalSeconds(account: Account): Long =
        manager.getUserData(account, INTERVAL_KEY)
            ?.toLongOrNull()
            ?.coerceAtLeast(MIN_INTERVAL_SECONDS)
            ?: DEFAULT_INTERVAL_SECONDS

    fun setIntervalSeconds(account: Account, seconds: Long) {
        manager.setUserData(account, INTERVAL_KEY, seconds.coerceAtLeast(MIN_INTERVAL_SECONDS).toString())
    }

    /**
     * When §8's walk of this Account last ran to the end, in milliseconds since the epoch, or null
     * when none this build knows of ever has.
     *
     * Per Account and not per authority: [SyncEngine] is built once per authority and both of them
     * run against this Account, while the walk they would each make finds both halves of it — so a
     * key that named the authority would double the traffic the setting exists to bound.
     */
    fun lastEnumeratedAt(account: Account): Long? =
        manager.getUserData(account, ENUMERATED_KEY)?.toLongOrNull()

    /**
     * Records that a walk of this Account ran to the end. Only one that did: a walk that failed has
     * to be retried by the next run rather than suppressed for a day, so the caller writes this
     * after the walk, never before it.
     */
    fun setLastEnumeratedAt(account: Account, at: Long) {
        manager.setUserData(account, ENUMERATED_KEY, at.toString())
    }

    companion object {
        /**
         * The shortest interval the platform honours, so it is both the floor of the chooser and
         * what an Account gets before anyone has chosen.
         */
        const val MIN_INTERVAL_SECONDS: Long = 15L * 60

        /** What a fresh Account gets: the interval that keeps it in step without asking. */
        const val DEFAULT_INTERVAL_SECONDS: Long = MIN_INTERVAL_SECONDS

        /**
         * The intervals the chooser offers, in one place and in ascending order, so that the value
         * stored and the value displayed cannot come from two different lists.
         */
        val OFFERED_INTERVAL_SECONDS: List<Long> = listOf(
            15L * 60,
            30L * 60,
            60L * 60,
            2L * 60 * 60,
            6L * 60 * 60,
            24L * 60 * 60,
        )

        private const val UNMETERED_KEY = "dav_sync_unmetered_only_v1"
        private const val INTERVAL_KEY = "dav_sync_interval_seconds_v1"
        private const val ENUMERATED_KEY = "dav_sync_last_enumerated_at_v1"
        private const val TRUE = "true"
    }
}
