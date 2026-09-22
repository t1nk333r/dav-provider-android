package app.davkeep.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import app.davkeep.core.ErrorClass

/**
 * Which redirects one probe is carried by.
 *
 * The decision is a pure function of the response because the two redirects that are *not* followed
 * are the ones that matter: a login page would replace the credential rejection the classifier named
 * with a form, and another origin would receive the Account's Headers and Basic credentials, which
 * travel on every request this client sends. OkHttp is not built here — its Android artifact needs
 * `android.util.Log` — so the policy is exercised directly.
 */
class DavProbeTest {

    private val url = "https://dav.invalid/.well-known/carddav"

    @Test
    fun `a redirect on the Account's own origin is followed`() {
        assertEquals(
            "https://dav.invalid/dav/",
            nextHop(url, status = 302, location = "/dav/", errorClass = ErrorClass.PROXY_INTERFERENCE, hopsTaken = 0),
        )
    }

    @Test
    fun `a relative Location is resolved against the URL that answered`() {
        // Relative to the answering URL's directory, as a `Location` reference is defined to be.
        assertEquals(
            "https://dav.invalid/dav/user/principals/me/",
            nextHop("https://dav.invalid/dav/user/", 301, "principals/me/", ErrorClass.PROXY_INTERFERENCE, 0),
        )
    }

    @Test
    fun `a redirect that leaves the Account's origin is not followed`() {
        assertNull(
            nextHop(url, 302, "https://somewhere-else.invalid/dav/", ErrorClass.PROXY_INTERFERENCE, 0),
        )
        // Neither is one that only drops the transport's encryption.
        assertNull(
            nextHop(url, 302, "http://dav.invalid/dav/", ErrorClass.PROXY_INTERFERENCE, 0),
        )
    }

    @Test
    fun `a redirect into the proxy's login page is not followed`() {
        assertNull(
            nextHop(
                url,
                302,
                "https://dav.invalid/cdn-cgi/access/login?redirect_url=%2Fdav%2F",
                ErrorClass.PROXY_REJECTED_CREDENTIALS,
                0,
            ),
        )
    }

    @Test
    fun `a probe is carried by a bounded number of hops`() {
        assertEquals("https://dav.invalid/dav/", nextHop(url, 302, "/dav/", ErrorClass.PROXY_INTERFERENCE, 2))
        assertNull(nextHop(url, 302, "/dav/", ErrorClass.PROXY_INTERFERENCE, 3))
    }

    @Test
    fun `a response that is not a redirect is an answer, and so is one with nowhere to go`() {
        assertNull(nextHop(url, 207, null, ErrorClass.ORIGIN_REFUSED_INFO, 0))
        assertNull(nextHop(url, 401, null, ErrorClass.ORIGIN_WANTS_CREDENTIALS, 0))
        assertNull(nextHop(url, 302, null, ErrorClass.PROXY_INTERFERENCE, 0))
    }
}
