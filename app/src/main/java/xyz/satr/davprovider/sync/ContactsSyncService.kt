package xyz.satr.davprovider.sync

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.provider.ContactsContract

/**
 * The contacts authority's bound service. The manifest names this class and points its
 * `android.content.SyncAdapter` metadata at `@xml/syncadapter_contacts`.
 */
class ContactsSyncService : Service() {

    private lateinit var adapter: DavSyncAdapter

    override fun onCreate() {
        super.onCreate()
        adapter = DavSyncAdapter(this, ContactsContract.AUTHORITY)
    }

    override fun onBind(intent: Intent): IBinder = adapter.syncAdapterBinder
}
