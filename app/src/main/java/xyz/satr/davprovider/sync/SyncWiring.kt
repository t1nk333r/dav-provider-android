package xyz.satr.davprovider.sync

import android.content.Context

/** Builds the engine a run should use for one authority. */
fun interface SyncEngineProvider {
    fun create(context: Context, authority: String): SyncEngine
}

/**
 * The single place the app installs the sync side of its object graph, from `Application.onCreate`:
 *
 * ```
 * SyncWiring.install(appContext, reporter)
 * ```
 *
 * Nothing in this package builds a mapper, a store, a classifier or an HTTP client — those belong to
 * their own components — so this seam is also what lets a test run [SyncEngine] against fakes.
 */
object SyncWiring {

    @Volatile
    var provider: SyncEngineProvider? = null

    /**
     * Installs the graph the app actually runs (see [defaultSyncEngineProvider]).
     *
     * [reporter] is the caller's: a run produces a result, and where that result is kept is the
     * reader's business.
     */
    fun install(context: Context, reporter: SyncReporter) {
        provider = defaultSyncEngineProvider(context, reporter)
    }

    /**
     * @throws IllegalStateException when the app never installed a provider — failing a run loudly
     * is better than reporting a successful, empty one.
     */
    fun requireProvider(): SyncEngineProvider = provider ?: error(
        "no SyncEngineProvider installed: call SyncWiring.install(applicationContext, reporter) " +
            "from Application.onCreate",
    )
}
