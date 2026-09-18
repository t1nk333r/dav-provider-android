package xyz.satr.davprovider.net

import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.satr.davprovider.core.DavHeader

/**
 * Drives the interceptor directly and asserts on the request the next chain step would receive.
 *
 * The chain is a fake because OkHttp's Android artifact reaches for `android.util.Log` while a client
 * is being built: the interceptor's own contract needs no client, no network and no Android runtime.
 */
class DavHeaderInterceptorTest {

    private fun outgoingRequest(headers: List<DavHeader>, request: Request): Request {
        var outgoing: Request? = null
        DavHeaderInterceptor(headers).intercept(FakeChain(request) { outgoing = it })
        return checkNotNull(outgoing) { "the interceptor never proceeded" }
    }

    @Test
    fun `adds every configured header to an outgoing request`() {
        val headers = listOf(
            DavHeader("CF-Access-Client-Id", "service-token-id.access"),
            DavHeader("CF-Access-Client-Secret", "service-token-secret"),
            DavHeader("X-Tenant", "acme"),
        )

        val outgoing = outgoingRequest(headers, Request.Builder().url(PROPFIND_URL).method("PROPFIND", null).build())

        assertEquals("PROPFIND", outgoing.method)
        for (header in headers) assertEquals(header.value, outgoing.header(header.name))
        assertEquals(headers.size, outgoing.headers.size)
    }

    @Test
    fun `a configured header replaces the value the caller already set`() {
        val outgoing = outgoingRequest(
            listOf(DavHeader("Authorization", "Bearer configured")),
            Request.Builder().url(PROPFIND_URL).header("Authorization", "Bearer caller").build(),
        )

        assertEquals("Bearer configured", outgoing.header("Authorization"))
    }

    @Test
    fun `an account without headers leaves the request untouched`() {
        val outgoing = outgoingRequest(
            emptyList(),
            Request.Builder().url(PROPFIND_URL).header("Accept", "application/xml").build(),
        )

        assertEquals("application/xml", outgoing.header("Accept"))
        assertEquals(1, outgoing.headers.size)
    }

    /** Records the request it is handed and answers with a bare 200. */
    private class FakeChain(private val request: Request, private val onProceed: (Request) -> Unit) : Interceptor.Chain {

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            onProceed(request)
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("".toResponseBody(null))
                .build()
        }

        override fun connection(): Connection? = null

        override fun call(): Call = throw UnsupportedOperationException("DavHeaderInterceptor never uses the call")

        override fun connectTimeoutMillis(): Int = 0

        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override fun readTimeoutMillis(): Int = 0

        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

        override fun writeTimeoutMillis(): Int = 0

        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }

    private companion object {
        const val PROPFIND_URL = "https://dav.example/addressbooks/user/contacts/"
    }
}
