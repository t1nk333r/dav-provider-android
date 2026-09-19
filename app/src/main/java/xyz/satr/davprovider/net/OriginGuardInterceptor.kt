package xyz.satr.davprovider.net

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Keeps the Account's credentials and custom Headers from leaving its origin.
 *
 * The two `followRedirects = false` settings this client is built with do not stop redirects being
 * followed — they are what dav4jvm *requires* in order to follow them itself. `DavResource` re-issues
 * a request to a `3xx`'s `Location`, up to five hops, and the only check inside that loop is that a
 * redirect does not downgrade HTTPS to HTTP: there is no same-origin rule. The re-issued request
 * goes through this client, so it carries whatever the interceptors attached to the first one.
 *
 * That is the whole of what this app sends to a server. A `Location` pointing anywhere else would
 * receive the Basic password and, worse, the proxy service token — the single credential this app
 * exists to deliver to exactly one host. A server that has been compromised, or anything that can
 * answer for it, needs only to redirect.
 *
 * Added last, so it sees the request as the other interceptors left it. Nothing same-origin is
 * touched, which is every request in a sync that is not being redirected; a cross-origin redirect
 * now fails authentication instead of being handed the credentials.
 */
class OriginGuardInterceptor(
    private val origin: String?,
    private val headerNames: List<String>,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // The rule the client certificate is already released under: scheme, host and port, with a
        // URL that names no port meaning that scheme's default. One origin rule for the app.
        if (originAllows(origin, Peer(request.url.host, request.url.port))) {
            return chain.proceed(request)
        }
        val stripped = request.newBuilder().removeHeader(AUTHORIZATION)
        headerNames.forEach { stripped.removeHeader(it) }
        return chain.proceed(stripped.build())
    }

    private companion object {
        const val AUTHORIZATION = "Authorization"
    }
}
