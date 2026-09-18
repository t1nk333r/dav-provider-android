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
 */
class SyncPreferences(context: Context) {

    private val manager = AccountManager.get(context.applicationContext)

    /** Whether automatic runs of this Account wait for an unmetered network. */
    fun unmeteredOnly(account: Account): Boolean = manager.getUserData(account, KEY) == TRUE

    fun setUnmeteredOnly(account: Account, enabled: Boolean) {
        manager.setUserData(account, KEY, if (enabled) TRUE else null)
    }

    private companion object {
        const val KEY = "dav_sync_unmetered_only_v1"
        const val TRUE = "true"
    }
}
