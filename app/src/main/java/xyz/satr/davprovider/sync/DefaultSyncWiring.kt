package xyz.satr.davprovider.sync

import android.content.Context
import android.provider.CalendarContract
import android.provider.ContactsContract
import xyz.satr.davprovider.core.CollectionType
import xyz.satr.davprovider.error.SyncErrorClassifierImpl
import xyz.satr.davprovider.net.DavHttpClientFactoryImpl
import xyz.satr.davprovider.provider.calendar.CalendarMapper
import xyz.satr.davprovider.provider.contacts.ContactsMapper
import xyz.satr.davprovider.store.AccountManagerAccountStore

/**
 * The components the app actually runs, named once.
 *
 * This is the only file in this package that knows what the rest of the app provides, which is what
 * keeps [SyncEngine] constructible with fakes and the authority-to-component pairing in one place.
 * The mapper is chosen by authority because that is what makes the two halves separable at all: one
 * authority is one platform provider, and [SyncEngine] syncs only the Collections it can write to.
 *
 * [reporter] belongs to the caller: a run produces a result, and where that result is kept is the
 * reader's business.
 */
internal fun defaultSyncEngineProvider(
    context: Context,
    reporter: SyncReporter,
): SyncEngineProvider = SyncEngineProvider { _, authority ->
    val appContext = context.applicationContext
    val collectionType = when (authority) {
        ContactsContract.AUTHORITY -> CollectionType.ADDRESS_BOOK
        CalendarContract.AUTHORITY -> CollectionType.CALENDAR
        else -> error("no sync components serve the authority $authority")
    }

    SyncEngine(
        mapper = when (collectionType) {
            CollectionType.ADDRESS_BOOK -> ContactsMapper(appContext)
            CollectionType.CALENDAR -> CalendarMapper(appContext)
        },
        collectionType = collectionType,
        accountStore = AccountManagerAccountStore(appContext),
        httpClientFactory = DavHttpClientFactoryImpl(appContext),
        classifier = SyncErrorClassifierImpl(),
        reporter = reporter,
    )
}
