package xyz.satr.davprovider.net

import okhttp3.Request
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

    private companion object {
        const val PROPFIND_URL = "https://dav.example/addressbooks/user/contacts/"
    }
}
