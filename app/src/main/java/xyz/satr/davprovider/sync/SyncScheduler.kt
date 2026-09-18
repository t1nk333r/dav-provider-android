package xyz.satr.davprovider.sync

import android.accounts.Account
import android.content.ContentResolver
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import xyz.satr.davprovider.core.DavCollection

/** The authorities this app syncs: one per platform provider, so §3's pairing is named once. */
val SYNC_AUTHORITIES: List<String> = listOf(ContactsContract.AUTHORITY, CalendarContract.AUTHORITY)

/**
 * The interval an Account is synced at.
 *
 * Fifteen minutes is not a preference: the framework runs a periodic sync as a persisted
 * `JobScheduler` job, whose minimum period is fifteen minutes, so a smaller number here would be a
 * promise the platform does not keep.
 */
const val PERIODIC_SYNC_INTERVAL_SECONDS = 15L * 60

/**
 * §8's schedule for an Account, in one place, so that no screen invents its own.
 *
 * Everything here is the platform's own sync settings for an Account — whether the framework may run
 * a sync on its own, and how often. §2 keeps the sync adapter the single mechanism, so there is no
 * second scheduler to keep in step, and nothing here starts a run except [syncNow], which is the
 * user asking.
 *
 * The settings live in the framework rather than in this app, and they are keyed by account and
 * authority: writing the same schedule twice must therefore leave one schedule, which [enable]'s
 * remove-then-add is what guarantees.
 */
object SyncScheduler {

    /**
     * One instance, because [removePeriodicSync] finds a periodic sync by *equal* extras: sharing
     * the bundle is what makes the removal match what [enable] added. Empty on purpose — a periodic
     * sync may not carry MANUAL, EXPEDITED or any of the other request-only extras.
     */
    private val PERIODIC_EXTRAS: Bundle = Bundle()

    /**
     * §8: the schedule follows the Collection selection.
     *
     * An Account with nothing selected has nothing to sync, and a periodic job that does nothing
     * forever is worse than no job — it is a wakeup, a run and a status line for a question nobody
     * asked. Selection is the only way an Account comes to have anything to sync, which is why this
     * is the entry point every selection write ends with.
     */
    fun applySelection(account: Account, collections: List<DavCollection>) {
        if (hasSyncableCollections(collections)) enable(account) else disable(account)
    }

    /** Whether the framework may sync this Account on its own, at [PERIODIC_SYNC_INTERVAL_SECONDS]. */
    fun enable(account: Account) {
        for (authority in SYNC_AUTHORITIES) {
            ContentResolver.setSyncAutomatically(account, authority, true)
            // Removed before it is added. The framework keys a periodic sync on the account, the
            // authority *and* the extras, so an add that did not match an earlier add exactly would
            // leave two jobs racing to sync the same provider.
            ContentResolver.removePeriodicSync(account, authority, PERIODIC_EXTRAS)
            ContentResolver.addPeriodicSync(
                account,
                authority,
                PERIODIC_EXTRAS,
                PERIODIC_SYNC_INTERVAL_SECONDS,
            )
        }
    }

    /** Stops the framework's runs of this Account: no network-tickle syncs, no periodic job. */
    fun disable(account: Account) {
        for (authority in SYNC_AUTHORITIES) {
            ContentResolver.setSyncAutomatically(account, authority, false)
            ContentResolver.removePeriodicSync(account, authority, PERIODIC_EXTRAS)
        }
    }

    /**
     * §8's manual sync: the user asking, which bypasses every constraint — the schedule, the
     * unmetered-only setting, and any backoff the framework is holding.
     *
     * `SYNC_EXTRAS_MANUAL` is not a hint. It is the only way a run can say that a person started it,
     * and both the framework and the engine's pre-flight read exactly it.
     */
    fun syncNow(account: Account, authority: String? = null) {
        for (target in authority?.let { listOf(it) } ?: SYNC_AUTHORITIES) {
            // A manual sync runs even where the platform's own switch was turned off: the user
            // asking is the override, and `isAlwaysSyncable` only covers a freshly added Account.
            ContentResolver.setIsSyncable(account, target, 1)
            ContentResolver.requestSync(account, target, manualExtras())
        }
    }

    /**
     * Called when the Account is removed.
     *
     * The framework keeps what it was told about an Account in its own storage, keyed by name and
     * type, and the periodic sync it was given is a persisted job. An Account that no longer exists
     * must not leave one behind, waking the device to sync a name nothing resolves.
     */
    fun cancel(account: Account) {
        disable(account)
        for (authority in SYNC_AUTHORITIES) ContentResolver.cancelSync(account, authority)
    }

    /**
     * MANUAL is the extra the engine's pre-flight reads; it also implies IGNORE_SETTINGS and
     * IGNORE_BACKOFF, and EXPEDITED moves the request to the front of the queue rather than leaving
     * it behind whatever the framework had already planned.
     */
    private fun manualExtras(): Bundle = Bundle().apply {
        putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
        putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, true)
        putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, true)
    }
}

/**
 * Whether a run of this Account could have anything to bring in.
 *
 * `selected && available` is [SyncEngine]'s own filter, and the two must agree: a Collection that is
 * selected but unavailable is one the server no longer lists, so an Account that has only those has
 * as little to sync as one with nothing selected at all.
 */
internal fun hasSyncableCollections(collections: List<DavCollection>): Boolean =
    collections.any { it.selected && it.available }
