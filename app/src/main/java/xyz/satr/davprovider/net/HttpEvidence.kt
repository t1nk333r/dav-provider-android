package xyz.satr.davprovider.net

import okhttp3.Response
import xyz.satr.davprovider.core.DavHttpClient
import xyz.satr.davprovider.core.ResponseEvidence

/**
 * Turns what the transport saw into the evidence the error classifier consumes, so that the
 * classifier never has to know what an OkHttp response looks like.
 */
object HttpEvidence {

    /**
     * Enough of a body to recognise a `DAV:` condition or an HTML page from a proxy, small enough
     * that a multistatus is never held in memory for diagnostics.
     */
    const val BODY_PEEK_BYTES = 2048L

    /** One line must not be able to fill the log on its own. */
    private const val MAX_LINE_CHARS = 200

    /**
     * The most a body parsed out of evidence may hold.
     *
     * Generous on purpose. A home set holding thousands of Collections is a real answer, so this is
     * not a second, smaller version of the peek bound above — it is a ceiling against a server that
     * answers with something else entirely, and the reason [bufferedBody] stops reading at it.
     */
    private const val MAX_WHOLE_BODY_BYTES = 8L * 1024 * 1024

    /**
     * Reads the response without consuming it: the body is still there for dav4jvm to parse, which
     * is what makes this safe to call on every response of a sync.
     */
    fun of(response: Response, certificateOffered: Boolean): ResponseEvidence =
        evidence(response, certificateOffered, peekBody(response))

    fun of(response: Response, client: DavHttpClient): ResponseEvidence = of(response, client.certificateOffered)

    /**
     * The same evidence with the body read whole rather than peeked.
     *
     * For a caller that *parses* the body out of this evidence — both probes do — because a peek is
     * bounded on purpose and anything past the bound is gone. A multistatus cut off mid-element is
     * not a truncated answer, it is a malformed one: a home set holding enough Collections pushed
     * its listing past [BODY_PEEK_BYTES], and the walk read the whole thing as unreadable and
     * enumerated no Collections at all. Reading a body to parse it and bounding a body to log it are
     * different needs, so they are different functions rather than one length that has to suit both.
     */
    fun ofWholeBody(response: Response, client: DavHttpClient): ResponseEvidence =
        ofWholeBody(response, client.certificateOffered)

    fun ofWholeBody(response: Response, certificateOffered: Boolean): ResponseEvidence =
        evidence(response, certificateOffered, wholeBody(response))

    private fun evidence(
        response: Response,
        certificateOffered: Boolean,
        body: String?,
    ): ResponseEvidence = ResponseEvidence(
        httpStatus = response.code,
        locationHeader = response.header("Location"),
        wwwAuthenticate = response.header("WWW-Authenticate"),
        contentType = response.header("Content-Type"),
        body = body,
        requestMethod = response.request.method,
        certificateOffered = certificateOffered,
    )

    /** The no-response case: the transport class is defined by the absence of a response. */
    fun transportFailure(method: String?, certificateOffered: Boolean, cause: Throwable): ResponseEvidence =
        ResponseEvidence(
            httpStatus = null,
            locationHeader = null,
            wwwAuthenticate = null,
            contentType = null,
            body = null,
            requestMethod = method,
            certificateOffered = certificateOffered,
            transportFailure = cause,
        )

    /** The first non-blank line, verbatim up to [MAX_LINE_CHARS], for `SyncError.firstBodyLine`. */
    fun firstLine(body: String?): String? =
        body?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(MAX_LINE_CHARS)

    /** Diagnostic reading only: a body that will not peek is never the failure being reported. */
    private fun peekBody(response: Response): String? = try {
        val text = response.peekBody(BODY_PEEK_BYTES).string().trim()
        if (text.isEmpty()) null else text
    } catch (e: Exception) {
        null
    }

    /**
     * Everything the server sent, for the caller that parses it.
     *
     * Consumes the body, so it belongs only to a caller that owns the response and has nothing else
     * to hand it to — which is both probes.
     *
     * Refused rather than truncated past [MAX_WHOLE_BODY_BYTES]. A truncated multistatus is not a
     * shorter answer, it is a malformed one — that is what the bound above [BODY_PEEK_BYTES] taught
     * this file the first time — so a limit that kept the first N bytes would bring back the bug it
     * was meant to catch. Refusing is a different outcome: the caller reports a response it could
     * not read, which is true, instead of enumerating nothing, which is not.
     *
     * The read is bounded in what it *holds*, not only in what it keeps: [bufferedBody] asks for one
     * byte past the limit and stops there, so a server cannot make this allocate by sending more.
     */
    private fun wholeBody(response: Response): String? = try {
        val text = bufferedBody(response)?.trim()
        if (text.isNullOrEmpty()) null else text
    } catch (e: Exception) {
        null
    }

    /** The body, or null when there is none or it exceeds [MAX_WHOLE_BODY_BYTES]. */
    private fun bufferedBody(response: Response): String? {
        val source = response.body?.source() ?: return null
        // Blocking, and deliberately so: this is asked for a caller that is about to parse the body.
        source.request(MAX_WHOLE_BODY_BYTES + 1)
        if (source.buffer.size > MAX_WHOLE_BODY_BYTES) return null
        return source.readUtf8()
    }
}
