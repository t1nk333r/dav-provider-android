package xyz.satr.davprovider

import android.app.Application
import xyz.satr.davprovider.ui.UiSyncWiring

/**
 * Installs cross-slice wiring on process start.
 *
 * A sync can begin with no screen ever having been opened — at boot, or on the framework's
 * own schedule — so wiring installed from an Activity would leave those runs unreported.
 * Process start is the only hook that covers both paths.
 *
 * [UiSyncWiring.install] is idempotent per process; the activities call it too, as a
 * cheap fallback.
 */
class DavProviderApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        UiSyncWiring.install(this)
    }
}
