package xyz.satr.davprovider.sync

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import okhttp3.Interceptor
import xyz.satr.davprovider.core.DavHttpClient
import xyz.satr.davprovider.core.ResponseEvidence
import xyz.satr.davprovider.net.ResponseEvidenceRecorder

/**
 * The proxy's login path, the one §5 rule 2 names.
 *
 * It appears in this file only to choose *which* of two observed exchanges to report: one
 * [ResponseEvidence] carries one status, so both of them cannot be handed over at once. What that
 * status means stays the classifier's decision.
 */
private const val ACCESS_LOGIN_PATH = "/cdn-cgi/access/login"

/**
 * One sync run's HTTP access to an Account.
 *
 * Two settings are requirements rather than preferences. dav4jvm handles redirects itself, so a
 * client that followed them would hide the response §5 classifies — and the OkHttp engine refuses to
 * run at all without the HttpTimeout plugin installed. The timeouts are finite so that a request
 * that never answers fails as a transport error the framework can retry, instead of blocking the
 * sync thread until the process is killed.
 *
 * The recorder is what makes the failure witnessable: dav4jvm's exceptions carry a status and a body
 * excerpt but never headers, so without it the `Location` and `WWW-Authenticate` rules of §5 could
 * never match in the sync path.
 */
internal class DavHttpSession(
    private val davHttpClient: DavHttpClient,
    val client: HttpClient,
    private val recorder: ResponseEvidenceRecorder,
) {
    /**
     * Evidence of the exchange to report for a failure, or null while none has been answered.
     *
     * A redirect to the proxy's login endpoint wins over the hop that followed it. dav4jvm follows a
     * 3xx by issuing another request, so the decisive `3xx` + `Location` is overwritten by whatever
     * the login page answered — and §5 rule 2, the class that says "your token is wrong or expired",
     * could never match. Classifying the follow-up instead would report a login page as
     * PROXY_INTERFERENCE: terminal either way, but the wrong cause shown to the user.
     */
    val lastExchange: ResponseEvidence?
        get() {
            val redirect = recorder.redirect
            if (redirect != null && redirect.locationHeader?.contains(ACCESS_LOGIN_PATH, ignoreCase = true) == true)
                return redirect
            return recorder.last
        }

    /** Read live: a handshake later in the run is what answers "was a certificate offered". */
    val certificateOffered: Boolean get() = davHttpClient.certificateOffered

    fun close() {
        client.close()
    }
}

internal fun openDavHttpSession(davHttpClient: DavHttpClient): DavHttpSession {
    val recorder = ResponseEvidenceRecorder(davHttpClient)
    return DavHttpSession(
        davHttpClient = davHttpClient,
        client = davKtorClient(davHttpClient.okHttp, recorder),
        recorder = recorder,
    )
}

private fun davKtorClient(okHttp: okhttp3.OkHttpClient, recorder: Interceptor): HttpClient = HttpClient(OkHttp) {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) {
        connectTimeoutMillis = CONNECT_TIMEOUT_MS
        socketTimeoutMillis = SOCKET_TIMEOUT_MS
    }
    engine {
        // A copy, so the Account's client keeps deciding what is sent; the recorder only observes.
        preconfigured = okHttp.newBuilder().addInterceptor(recorder).build()
    }
}

private const val CONNECT_TIMEOUT_MS = 30_000L
private const val SOCKET_TIMEOUT_MS = 60_000L
