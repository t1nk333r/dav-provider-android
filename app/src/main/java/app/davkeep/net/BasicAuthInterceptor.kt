package app.davkeep.net

import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Sends Basic credentials on the first request instead of waiting for a challenge: a sync opens with
 * a `PROPFIND`, and an unauthenticated one is both a wasted round trip and a request the proxy in
 * front of the Origin may reject in a way that reads as a configuration error.
 *
 * A password is optional — `username:` is legal, and an Account with a username and no password is a
 * legitimate configuration.
 */
class BasicAuthInterceptor(username: String, password: String?) : Interceptor {

    private val authorization: String = Credentials.basic(username, password.orEmpty())

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // A request that already carries credentials (an answer to a challenge, say) is left alone.
        if (request.header("Authorization") != null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header("Authorization", authorization).build())
    }
}
