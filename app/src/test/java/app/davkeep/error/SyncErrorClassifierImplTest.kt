package app.davkeep.error

import java.io.IOException
import java.net.ProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import app.davkeep.core.ErrorClass
import app.davkeep.core.ResponseEvidence

/**
 * §5's rules are ordered, and the ordering is their whole point: a 403 carrying a DAV error body is
 * informational while a 302 into a proxy login page is a hard credential failure. Both were seen on
 * the real server, so both are pinned here.
 */
class SyncErrorClassifierImplTest {

    private val classifier = SyncErrorClassifierImpl()

    @Test
    fun `403 with a DAV refusal body is informational, not a block`() {
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <error xmlns="DAV:"><supported-report /></error>
        """.trimIndent()

        val error = classifier.classify(
            evidence(
                body = body,
                httpStatus = 403,
                requestMethod = "REPORT",
                certificateOffered = true,
                contentType = "application/xml; charset=utf-8",
            )
        )

        assertEquals(ErrorClass.ORIGIN_REFUSED_INFO, error.errorClass)
        assertEquals("supported-report", error.davCondition)
        assertEquals("This collection doesn't support fast sync — using a slower method", error.summary)
        assertEquals(403, error.httpStatus)
        assertEquals("<?xml version=\"1.0\" encoding=\"utf-8\"?>", error.firstBodyLine)
        assertTrue(error.certificateOffered)
        assertEquals("REPORT", error.requestMethod)
    }

    @Test
    fun `a prefixed error element in any attribute order is still the Origin answering`() {
        val body = """
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:error xmlns:x="urn:example" xmlns:C="urn:ietf:params:xml:ns:caldav">
                  <C:supported-calendar-data/>
                </D:error>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        val error = classifier.classify(evidence(body = body, httpStatus = 403, requestMethod = "REPORT"))

        assertEquals(ErrorClass.ORIGIN_REFUSED_INFO, error.errorClass)
        assertEquals("supported-calendar-data", error.davCondition)
    }

    @Test
    fun `a DAV body outranks an HTML content type`() {
        val error = classifier.classify(
            evidence(
                body = "<error xmlns=\"DAV:\"><supported-report/></error>",
                httpStatus = 403,
                contentType = "text/html; charset=utf-8",
                requestMethod = "PROPFIND",
            )
        )

        assertEquals(ErrorClass.ORIGIN_REFUSED_INFO, error.errorClass)
        assertEquals("supported-report", error.davCondition)
    }

    @Test
    fun `a redirect into the proxy login page is a credential rejection`() {
        val error = classifier.classify(
            evidence(
                httpStatus = 302,
                locationHeader = "https://dav.example.invalid/cdn-cgi/access/login?redirect_url=%2Fdav%2F",
                requestMethod = "PROPFIND",
                certificateOffered = true,
            )
        )

        assertEquals(ErrorClass.PROXY_REJECTED_CREDENTIALS, error.errorClass)
        assertEquals("Access denied by the proxy — the token is wrong or expired", error.summary)
        assertEquals(302, error.httpStatus)
        assertTrue(error.certificateOffered)
    }

    @Test
    fun `a DAV body outranks the proxy redirect`() {
        val error = classifier.classify(
            evidence(
                httpStatus = 302,
                locationHeader = "https://dav.example.invalid/cdn-cgi/access/login",
                body = "<D:error xmlns:D=\"DAV:\"><D:need-privileges/></D:error>",
                requestMethod = "PROPFIND",
            )
        )

        assertEquals(ErrorClass.ORIGIN_REFUSED_INFO, error.errorClass)
        assertEquals("need-privileges", error.davCondition)
    }

    @Test
    fun `a challenge means the Origin wants its own credentials`() {
        val error = classifier.classify(
            evidence(
                httpStatus = 401,
                wwwAuthenticate = "Basic realm=\"dav\", charset=\"UTF-8\"",
                requestMethod = "PROPFIND",
            )
        )

        assertEquals(ErrorClass.ORIGIN_WANTS_CREDENTIALS, error.errorClass)
        assertEquals("The server needs a username and password", error.summary)
    }

    @Test
    fun `a 403 HTML block page reads as a proxy refusal, not interference`() {
        val body = "<!DOCTYPE html><html><body>Access denied</body></html>"

        val error = classifier.classify(evidence(body = body, httpStatus = 403, requestMethod = "PROPFIND"))

        assertEquals(ErrorClass.PROXY_INTERFERENCE, error.errorClass)
        assertEquals("The proxy refused this request before it reached the server", error.summary)
        assertEquals(body, error.firstBodyLine)
        assertNull(error.davCondition)
    }

    /**
     * Observed against a real client-certificate-gated hostname: the certificate was sent and the
     * proxy still refused. Saying so is what separates "the app never offered one" from "the proxy
     * did not accept the one it got" — indistinguishable from the block page alone, which is the
     * ambiguity this app exists to remove.
     */
    @Test
    fun `a 403 block page with a certificate on the wire says the certificate was not accepted`() {
        val error = classifier.classify(
            evidence(
                body = "<!DOCTYPE html><html><body>Access denied</body></html>",
                httpStatus = 403,
                requestMethod = "PROPFIND",
                certificateOffered = true,
            ),
        )

        assertEquals(ErrorClass.PROXY_INTERFERENCE, error.errorClass)
        assertEquals(
            "The proxy refused this request — a certificate was sent but not accepted",
            error.summary,
        )
    }

    @Test
    fun `a body that only looks like DAV XML falls through instead of throwing`() {
        val error = classifier.classify(
            evidence(
                body = "<error xmlns=\"DAV:\"><supported-report",
                httpStatus = 403,
                requestMethod = "PROPFIND",
            )
        )

        assertEquals(ErrorClass.PROXY_INTERFERENCE, error.errorClass)
        assertNull(error.davCondition)
    }

    @Test
    fun `a success is informational, never a failure`() {
        val error = classifier.classify(evidence(httpStatus = 200, requestMethod = "PROPFIND"))

        assertEquals(ErrorClass.ORIGIN_REFUSED_INFO, error.errorClass)
        assertFalse(error.errorClass.terminal)
        assertEquals(200, error.httpStatus)
    }

    @Test
    fun `400 on OPTIONS is a refused method`() {
        val error = classifier.classify(evidence(httpStatus = 400, requestMethod = "OPTIONS"))

        assertEquals(ErrorClass.METHOD_REFUSED, error.errorClass)
        assertEquals("The server rejected this request type", error.summary)
        assertEquals("OPTIONS", error.requestMethod)
    }

    @Test
    fun `500 is the server's trouble and is retryable`() {
        val error = classifier.classify(evidence(httpStatus = 503, requestMethod = "PROPFIND"))

        assertEquals(ErrorClass.SERVER_ERROR, error.errorClass)
        assertEquals(503, error.httpStatus)
        assertTrue(error.errorClass.retryable)
    }

    @Test
    fun `no response is the transport class`() {
        val error = classifier.classify(
            evidence(
                transportFailure = IOException("connection reset by peer"),
                requestMethod = "PROPFIND",
                certificateOffered = true,
            )
        )

        assertEquals(ErrorClass.TRANSPORT_FAILURE, error.errorClass)
        assertEquals("Couldn't reach the server", error.summary)
        assertNull(error.httpStatus)
        assertTrue(error.certificateOffered)
    }

    @Test
    fun `a 207 whose body gave out is a malformed response, not a success`() {
        val error = classifier.classify(
            evidence(
                body = "<D:multistatus xmlns:D=\"DAV:\"><D:response>",
                httpStatus = 207,
                requestMethod = "REPORT",
                certificateOffered = true,
                transportFailure = ProtocolException("unexpected end of stream"),
            )
        )

        assertEquals(ErrorClass.MALFORMED_RESPONSE, error.errorClass)
        assertEquals("The server's response couldn't be understood", error.summary)
        assertEquals(207, error.httpStatus)
    }

    @Test
    fun `a body that gave out after a 5xx is still the server's verdict`() {
        val error = classifier.classify(
            evidence(
                httpStatus = 503,
                requestMethod = "PROPFIND",
                transportFailure = ProtocolException("unexpected end of stream"),
            )
        )

        assertEquals(ErrorClass.SERVER_ERROR, error.errorClass)
        assertTrue(error.errorClass.retryable)
    }

    @Test
    fun `a request that offered no certificate reports the observation, not the server's requirement`() {
        val error = noCertificateSent(
            evidence(body = "\n\n  Forbidden\nmore", httpStatus = 403, requestMethod = "PROPFIND")
        )

        assertEquals(ErrorClass.NO_CERTIFICATE_SENT, error.errorClass)
        assertEquals("No certificate was sent", error.summary)
        assertEquals(403, error.httpStatus)
        assertEquals("Forbidden", error.firstBodyLine)
        assertEquals("PROPFIND", error.requestMethod)
        assertEquals(false, error.certificateOffered)
    }

    @Test
    fun `no-certificate-sent is refused when a certificate was in fact offered`() {
        assertThrows(IllegalArgumentException::class.java) {
            noCertificateSent(evidence(httpStatus = 403, certificateOffered = true))
        }
    }

    @Test
    fun `classes raised without a response carry their own class and wording`() {
        assertEquals(ErrorClass.CERTIFICATE_UNAVAILABLE, certificateUnavailable().errorClass)
        assertEquals("The selected certificate is no longer available — reselect it", certificateUnavailable().summary)

        val unreadable = credentialsUnreadable(IllegalStateException("keystore"))
        assertEquals(ErrorClass.CREDENTIALS_UNREADABLE, unreadable.errorClass)
        assertEquals(
            "Credentials can't be read — this happens after restoring a device. Re-enter them.",
            unreadable.summary,
        )

        val malformed = malformedResponse(evidence(httpStatus = 207, certificateOffered = true), IllegalArgumentException("bad xml"))
        assertEquals(ErrorClass.MALFORMED_RESPONSE, malformed.errorClass)
        assertEquals("The server's response couldn't be understood", malformed.summary)
        assertEquals(207, malformed.httpStatus)
    }

    private fun evidence(
        body: String? = null,
        httpStatus: Int? = null,
        locationHeader: String? = null,
        wwwAuthenticate: String? = null,
        contentType: String? = null,
        requestMethod: String? = null,
        certificateOffered: Boolean = false,
        transportFailure: Throwable? = null,
    ): ResponseEvidence = ResponseEvidence(
        httpStatus = httpStatus,
        locationHeader = locationHeader,
        wwwAuthenticate = wwwAuthenticate,
        contentType = contentType,
        body = body,
        requestMethod = requestMethod,
        certificateOffered = certificateOffered,
        transportFailure = transportFailure,
    )
}
