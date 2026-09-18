package xyz.satr.davprovider.net

import okhttp3.Interceptor
import okhttp3.Response
import xyz.satr.davprovider.core.DavHttpClient
import xyz.satr.davprovider.core.ResponseEvidence

/**
 * Records the evidence of the most recent exchange of a client.
 *
 * The sync path never sees an OkHttp response — dav4jvm parses and discards it — so the headers that
 * carry a failure's meaning (a `Location` pointing at a proxy login, a `WWW-Authenticate` challenge)
 * would otherwise be unreachable. Install it on a copy of the Account's client:
 *
 * ```
 * val okHttp = davHttpClient.okHttp.newBuilder()
 *     .addInterceptor(ResponseEvidenceRecorder(davHttpClient))
 *     .build()
 * ```
 *
 * Deliberately opt-in: recording peeks at every body, which a sync that only cares about success
 * should not pay for. Call [reset] before each operation so what it holds belongs to that operation.
 */
class ResponseEvidenceRecorder(private val client: DavHttpClient) : Interceptor {

    /**
     * Evidence of the most recent response, or null while no response has arrived. The most recent
     * one is what is worth reporting; a failed request's is the one that will be read.
     */
    @Volatile
    var last: ResponseEvidence? = null
        private set

    /**
     * The most recent redirect the server returned, and only ever a 3xx.
     *
     * Kept apart from [last] because the hop that follows a redirect overwrites it: dav4jvm follows a
     * 3xx by issuing another request, so by the time a proxy-login redirect has been followed, the
     * response that carried its `Location` is no longer the latest one — and that `Location` is the
     * difference between a rejected token and a page of HTML.
     *
     * Not cleared by a non-3xx response: the response a login redirect leads to is a perfectly
     * ordinary `200`, so clearing on it would discard the redirect in the one case it is needed for.
     * Call [reset] at the start of each operation instead.
     */
    @Volatile
    var redirect: ResponseEvidence? = null
        private set

    /**
     * Forgets everything recorded so far, so that what is read next describes the operation about to
     * start rather than the last one. Without it a redirect seen early in a run would answer for
     * every later failure in that run, and a transport failure would be reported with a status the
     * server never sent for it.
     */
    fun reset() {
        last = null
        redirect = null
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        // Read after the exchange, so a certificate offered during this handshake is included.
        val evidence = HttpEvidence.of(response, client.certificateOffered)
        last = evidence
        if (response.code in 300..399) redirect = evidence
        return response
    }
}
