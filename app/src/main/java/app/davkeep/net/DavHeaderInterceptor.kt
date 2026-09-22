package app.davkeep.net

import okhttp3.Interceptor
import okhttp3.Response
import app.davkeep.core.DavHeader

/**
 * Sends every [DavHeader] of the Account on every request, whatever the method.
 *
 * A Header replaces any value the caller set under the same name. The Account is the authority on
 * what goes out: a proxy service token silently overridden by a caller's default is exactly the
 * failure this feature exists to remove, and it is indistinguishable from a wrong token when the
 * proxy answers.
 */
class DavHeaderInterceptor(private val headers: List<DavHeader>) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (headers.isEmpty()) return chain.proceed(request)

        val builder = request.newBuilder()
        for (header in headers) builder.header(header.name, header.value)
        return chain.proceed(builder.build())
    }
}
