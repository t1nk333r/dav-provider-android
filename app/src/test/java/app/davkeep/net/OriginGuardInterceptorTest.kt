package app.davkeep.net

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * An Account's credentials and custom Headers belong to one origin.
 *
 * This interceptor is the only thing standing between a `Location` and the proxy service token:
 * dav4jvm follows redirects itself, by re-issuing the request through the same client, and the only
 * check in its loop is that HTTPS is not downgraded to HTTP.
 */
class OriginGuardInterceptorTest {

    private fun outgoing(origin: String?, url: String, headerNames: List<String> = HEADER_NAMES): Request {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", BASIC)
            .apply { HEADER_NAMES.forEach { header(it, "service-token") } }
            .build()
        var outgoing: Request? = null
        OriginGuardInterceptor(origin, headerNames).intercept(FakeChain(request) { outgoing = it })
        return checkNotNull(outgoing) { "the interceptor never proceeded" }
    }

    @Test
    fun `a request to the account's own origin keeps everything`() {
        val outgoing = outgoing(ORIGIN, "https://dav.example/addressbooks/user/contacts/")

        assertEquals(BASIC, outgoing.header("Authorization"))
        assertEquals("service-token", outgoing.header("CF-Access-Client-Secret"))
    }

    /** The origin always names its port; a request URL need not. Both spell the same origin. */
    @Test
    fun `a URL that names no port is still the account's own origin`() {
        val outgoing = outgoing(ORIGIN, "https://dav.example/addressbooks/user/")

        assertEquals(BASIC, outgoing.header("Authorization"))
        assertEquals("service-token", outgoing.header("CF-Access-Client-Id"))
    }

    @Test
    fun `a request to another host carries neither`() {
        val outgoing = outgoing(ORIGIN, "https://attacker.example/addressbooks/user/contacts/")

        assertNull(outgoing.header("Authorization"))
        assertNull(outgoing.header("CF-Access-Client-Secret"))
        assertNull(outgoing.header("CF-Access-Client-Id"))
    }

    /** Another port on the same host is another origin, which is what the certificate rule says too. */
    @Test
    fun `a request to another port carries neither`() {
        val outgoing = outgoing(ORIGIN, "https://dav.example:8443/addressbooks/user/contacts/")

        assertNull(outgoing.header("Authorization"))
        assertNull(outgoing.header("CF-Access-Client-Id"))
    }

    @Test
    fun `a downgrade from https to http carries neither`() {
        val outgoing = outgoing(ORIGIN, "http://dav.example/addressbooks/user/contacts/")

        assertNull(outgoing.header("Authorization"))
        assertNull(outgoing.header("CF-Access-Client-Secret"))
    }

    /** Fail closed: an origin that cannot be read is not an origin to send credentials to. */
    @Test
    fun `an account whose origin cannot be parsed carries neither`() {
        val outgoing = outgoing(null, "https://dav.example/addressbooks/user/contacts/")

        assertNull(outgoing.header("Authorization"))
        assertNull(outgoing.header("CF-Access-Client-Id"))
    }

    @Test
    fun `a request with nothing but the account's headers is still stripped`() {
        val outgoing = outgoing(ORIGIN, "https://attacker.example/", headerNames = HEADER_NAMES)

        assertNull(outgoing.header("CF-Access-Client-Id"))
    }

    private companion object {
        /** What `DavAccount.origin` holds: the port is resolved, so an origin always names one. */
        const val ORIGIN = "https://dav.example:443"
        const val BASIC = "Basic dXNlcjpwYXNzd29yZA=="
        val HEADER_NAMES = listOf("CF-Access-Client-Id", "CF-Access-Client-Secret")
    }
}
