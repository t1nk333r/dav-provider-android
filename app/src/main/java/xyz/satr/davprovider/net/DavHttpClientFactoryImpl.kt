package xyz.satr.davprovider.net

import android.content.Context
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import xyz.satr.davprovider.core.DavAccount
import xyz.satr.davprovider.core.DavHttpClient
import xyz.satr.davprovider.core.DavHttpClientFactory

private const val TIMEOUT_SECONDS = 30L

/**
 * Builds the HTTP client of one Account out of that Account alone: its Headers, its Basic
 * credentials and its KeyChain alias. Nothing is read from storage here, so a caller can copy an
 * Account with one mechanism removed and get exactly the client that mechanism's absence describes.
 */
class DavHttpClientFactoryImpl(context: Context) : DavHttpClientFactory {

    // The KeyChain needs a context that outlives an Activity.
    private val context: Context = context.applicationContext

    /**
     * @throws ClientCertificateUnavailableException when the Account names an alias the KeyChain no
     * longer resolves. Blocking when an alias is set; call it off the main thread.
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
        // Added last, so a Header configured under a name Basic also uses is the one that is sent.
        builder.addInterceptor(DavHeaderInterceptor(davAccount.headers))

        val keyManager = davAccount.certAlias?.let { loadKeyChainKeyManager(context, it) }
        if (keyManager != null) {
            val trustManager = platformTrustManager()
            builder.sslSocketFactory(sslSocketFactory(keyManager, trustManager), trustManager)
        }
        return DavHttpClientImpl(builder.build(), keyManager)
    }
}

internal class DavHttpClientImpl(
    override val okHttp: OkHttpClient,
    private val keyManager: KeyChainKeyManager?,
) : DavHttpClient {

    /**
     * Sticky for the lifetime of this client: a client belongs to one sync run, so this answers
     * "did that run ever reach a handshake that asked for a certificate".
     */
    override val certificateOffered: Boolean get() = keyManager?.certificateOffered == true
}
