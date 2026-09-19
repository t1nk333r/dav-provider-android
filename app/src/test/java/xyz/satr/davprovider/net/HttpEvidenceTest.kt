package xyz.satr.davprovider.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.satr.davprovider.ui.ReadResult
import xyz.satr.davprovider.ui.WebDavXml

/**
 * The two readings of a response body, and why the walk needs the second one.
 *
 * [HttpEvidence.BODY_PEEK_BYTES] bounds what diagnostics keep, on purpose. Discovery used to parse
 * *that* body: a home set's listing past the bound was cut off mid-element, so it read as malformed
 * rather than as a listing — a walk that had worked with four Collections enumerated none at all
 * once there were six. The bound is for the log; it must never become the parser's limit.
 */
class HttpEvidenceTest {

    private val base = "https://dav.invalid/dav/user/"
    private val xml = "application/xml".toMediaType()

    private fun multistatus(calendars: Int): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<multistatus xmlns=\"DAV:\" xmlns:C=\"urn:ietf:params:xml:ns:caldav\">")
        append(
            "<response><href>/dav/</href><propstat><prop><resourcetype>" +
                "<principal/><collection/></resourcetype></prop>" +
                "<status>HTTP/1.1 200 OK</status></propstat></response>",
        )
        for (index in 1..calendars) {
            append("<response><href>/dav/user/cal-$index/</href>")
            append(
                "<propstat><prop><resourcetype><C:calendar/><collection/></resourcetype>" +
                    "<displayname>Calendar $index</displayname></prop>" +
                    "<status>HTTP/1.1 200 OK</status></propstat>",
            )
            append(
                "<propstat><prop><C:calendar-color/></prop>" +
                    "<status>HTTP/1.1 404 Not Found</status></propstat>",
            )
            append("</response>")
        }
        append("</multistatus>")
    }

    private fun responseWith(body: String): Response = Response.Builder()
        .request(Request.Builder().url(base).build())
        .protocol(Protocol.HTTP_1_1)
        .code(207)
        .message("Multi-Status")
        .body(body.toResponseBody(xml))
        .build()

    @Test
    fun `a listing past the diagnostic bound is still read whole, and every Collection enumerates`() {
        // Enough Collections that the listing is far longer than the diagnostics would keep.
        val listing = multistatus(calendars = 12)
        assertTrue(
            "the fixture must exceed the bound to exercise the defect",
            listing.length > HttpEvidence.BODY_PEEK_BYTES,
        )

        val kept = HttpEvidence.of(responseWith(listing), certificateOffered = false).body.orEmpty()
        assertTrue("diagnostics stay bounded", kept.length <= HttpEvidence.BODY_PEEK_BYTES)
        assertTrue("and the fixture is longer than that", kept.length < listing.length)

        val parsed = WebDavXml.collections(
            HttpEvidence.ofWholeBody(responseWith(listing), certificateOffered = false).body,
            base,
        )
        val found = (parsed as? ReadResult.Read)?.value
        assertEquals(
            "the whole listing is what a parser gets, so none of it is lost",
            (1..12).map { "${base}cal-$it/" },
            found?.map { it.url },
        )
    }

    @Test
    fun `the whole body is returned unchanged, not trimmed to a prefix`() {
        val listing = multistatus(calendars = 8)
        assertEquals(
            listing,
            HttpEvidence.ofWholeBody(responseWith(listing), certificateOffered = false).body,
        )
    }
}
