package xyz.satr.davprovider.ui

import android.content.Context
import xyz.satr.davprovider.core.AccountStore
import xyz.satr.davprovider.core.DavHttpClientFactory
import xyz.satr.davprovider.core.SyncErrorClassifier
import xyz.satr.davprovider.error.SyncErrorClassifierImpl
import xyz.satr.davprovider.net.DavHttpClientFactoryImpl
import xyz.satr.davprovider.store.AccountManagerAccountStore

/**
 * Where the UI resolves the shared implementations, so that changing one is a change in one file.
 *
 * The UI uses these strictly through the frozen interfaces; the concrete classes appear here and
 * nowhere else in this package. Every one of them takes only an application context and is cheap
 * to construct, so call sites construct what they need.
 */
internal object UiDependencies {

    fun accountStore(context: Context): AccountStore =
        AccountManagerAccountStore(context.applicationContext)

    fun httpClientFactory(context: Context): DavHttpClientFactory =
        DavHttpClientFactoryImpl(context.applicationContext)

    fun errorClassifier(): SyncErrorClassifier = SyncErrorClassifierImpl()
}
