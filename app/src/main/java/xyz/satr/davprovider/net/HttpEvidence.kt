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
     * Reads the response without consuming it: the body is still there for dav4jvm to parse, which
     * is what makes this safe to call on every response of a sync.
     */
    fun of(response: Response, certificateOffered: Boolean): ResponseEvidence = ResponseEvidence(
        httpStatus = response.code,
        locationHeader = response.header("Location"),
        wwwAuthenticate = response.header("WWW-Authenticate"),
        contentType = response.header("Content-Type"),
        body = peekBody(response),
        requestMethod = response.request.method,
        certificateOffered = certificateOffered,
    )

    fun of(response: Response, client: DavHttpClient): ResponseEvidence = of(response, client.certificateOffered)

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
}
