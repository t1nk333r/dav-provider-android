package app.davkeep.net

import android.content.Context
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import app.davkeep.core.ClientCertificateSource
import app.davkeep.core.ClientCertificateStore
import app.davkeep.core.DavAccount
import app.davkeep.core.DavHttpClient
import app.davkeep.core.DavHttpClientFactory
import app.davkeep.store.ClientCertificateStoreImpl

private const val TIMEOUT_SECONDS = 30L

/**
 * Builds the HTTP client of one Account out of that Account alone: its Headers, its Basic
 * credentials and its client certificate. Nothing but the certificate material is read from storage
 * here, so a caller can copy an Account with one mechanism removed and get exactly the client that
 * mechanism's absence describes.
 */
class DavHttpClientFactoryImpl(
    context: Context,
    private val certificates: ClientCertificateStore = ClientCertificateStoreImpl(context),
) : DavHttpClientFactory {

    // The KeyChain needs a context that outlives an Activity.
    private val appContext: Context = context.applicationContext

    /**
     * @throws ClientCertificateUnavailableException when the Account names a KeyChain alias that no
     * longer resolves. Blocking when one is set; call it off the main thread.
     */
    override fun create(davAccount: DavAccount): DavHttpClient {
        val builder = OkHttpClient.Builder()
            // dav4jvm follows redirects itself at the DAV layer, and the proxy-rejection class is
            // defined on a raw 3xx plus its Location: a client that follows them silently destroys
            // both, turning a rejected token into a page of HTML.
            .followRedirects(false)
            .followSslRedirects(false)
            // Long enough for a slow listing, short enough that a dead socket becomes a retryable
            // transport failure instead of a stuck sync. Retrying is the sync framework's job.
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        davAccount.username?.let { builder.addInterceptor(BasicAuthInterceptor(it, davAccount.password)) }
        // Added after it, so a Header configured under a name Basic also uses is the one that is sent.
        builder.addInterceptor(DavHeaderInterceptor(davAccount.headers))
        // Added last, so it sees what the two above attached. dav4jvm follows redirects itself, and
        // its loop has no same-origin rule — this is what keeps a `Location` pointing elsewhere from
        // being handed the Account.
        builder.addInterceptor(OriginGuardInterceptor(davAccount.origin, davAccount.headers.map { it.name }))

        val keyManager = keyManagerFor(davAccount)
        if (keyManager != null) {
            val trustManager = platformTrustManager()
            builder.sslSocketFactory(sslSocketFactory(keyManager, trustManager), trustManager)
        }
        return DavHttpClientImpl(builder.build(), keyManager)
    }

    /**
     * The Account's client certificate, if it selects one.
     *
     * A KeyChain alias is resolved here, so a selection that no longer resolves fails before a
     * request exists and can be reported as itself. An imported archive is not: the lookup is
     * handed to the manager and read per handshake, so an import or a removal applies to the next
     * request instead of only to the next client.
     */
    private fun keyManagerFor(davAccount: DavAccount): ClientKeyManager? =
        when (val source = davAccount.certificate) {
            null -> null
            is ClientCertificateSource.KeyChainAlias ->
                loadKeyChainKeyManager(appContext, source.alias, davAccount.origin)

            ClientCertificateSource.Imported ->
                ClientKeyManager(IMPORTED_ALIAS, davAccount.origin) {
                    certificates.identity(davAccount.androidAccount)
                }
        }
}

internal class DavHttpClientImpl(
    override val okHttp: OkHttpClient,
    private val keyManager: ClientKeyManager?,
) : DavHttpClient {

    /**
     * Sticky for the lifetime of this client: a client belongs to one sync run, so this answers
     * "did that run ever reach a handshake that asked for a certificate".
     */
    override val certificateOffered: Boolean get() = keyManager?.certificateOffered == true
}
