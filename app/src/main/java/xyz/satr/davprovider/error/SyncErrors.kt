package xyz.satr.davprovider.error

import xyz.satr.davprovider.core.ErrorClass
import xyz.satr.davprovider.core.ResponseEvidence
import xyz.satr.davprovider.core.SyncError

/**
 * The taxonomy's summary lines, kept verbatim in one place. They are what the user reads while the
 * Details expander shows the raw evidence, so every one is plain language and none of them claims
 * more than was observed.
 */
internal object ErrorSummary {

    const val PROXY_REJECTED_CREDENTIALS = "Access denied by the proxy — the token is wrong or expired"
    const val ORIGIN_WANTS_CREDENTIALS = "The server needs a username and password"
    const val CERTIFICATE_UNAVAILABLE = "The selected certificate is no longer available — reselect it"
    const val CREDENTIALS_UNREADABLE =
        "Credentials can't be read — this happens after restoring a device. Re-enter them."
    const val NO_CERTIFICATE_SENT = "No certificate was sent"
    const val TRANSPORT_FAILURE = "Couldn't reach the server"
    const val SERVER_ERROR = "The server is having trouble"
    const val NOT_FOUND = "That path doesn't exist on the server"
    const val METHOD_REFUSED = "The server rejected this request type"
    const val MALFORMED_RESPONSE = "The server's response couldn't be understood"
    const val PROXY_INTERFERENCE = "Something between the app and the server changed the response"

    /** Class 3 has no single line: the DAV condition the Origin sent names what it refused. */
    const val ORIGIN_REFUSED = "The server refused this request"

    /**
     * The one condition with a distinct line: `<supported-report/>` is the negative answer to the
     * `sync-collection` probe, so the sync engine falls back to polling instead of failing.
     */
    const val SUPPORTED_REPORT = "This collection doesn't support fast sync — using a slower method"

    /**
     * A 2xx the caller should not have classified. Class 3 is informational, so this cannot mark a
     * Collection failed or fire a notification.
     */
    const val NO_ERROR = "The server accepted the request"
}

/** XML and HTML bodies are usually one long line; the cap is what keeps the expander readable. */
private const val BODY_LINE_MAX = 200

/** The first non-blank line of the response body, trimmed. Null when there was no body. */
internal fun firstBodyLine(body: String?): String? {
    val line = body?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim() ?: return null
    return if (line.length <= BODY_LINE_MAX) line else line.take(BODY_LINE_MAX)
}

/**
 * Every [SyncError] carries the same four observations, so they are copied from the evidence in
 * exactly one place; only the class, the wording and the DAV condition vary.
 */
internal fun ResponseEvidence.toError(
    errorClass: ErrorClass,
    summary: String,
    cause: Throwable? = null,
    davCondition: String? = null,
): SyncError = SyncError(
    errorClass = errorClass,
    summary = summary,
    httpStatus = httpStatus,
    firstBodyLine = firstBodyLine(body),
    certificateOffered = certificateOffered,
    requestMethod = requestMethod,
    davCondition = davCondition,
    cause = cause,
)

/** Class 3's line, wording the refusal by the condition that carried it. */
internal fun originRefusedSummary(condition: String?): String = when (condition) {
    null -> ErrorSummary.ORIGIN_REFUSED
    "supported-report" -> ErrorSummary.SUPPORTED_REPORT
    else -> "${ErrorSummary.ORIGIN_REFUSED} ($condition)"
}

/**
 * KeyChain returned no certificate or threw while producing one, so nothing was ever offered.
 * The failure happens before a request exists, hence the empty evidence.
 */
fun certificateUnavailable(cause: Throwable? = null): SyncError = SyncError(
    errorClass = ErrorClass.CERTIFICATE_UNAVAILABLE,
    summary = ErrorSummary.CERTIFICATE_UNAVAILABLE,
    httpStatus = null,
    firstBodyLine = null,
    certificateOffered = false,
    requestMethod = null,
    cause = cause,
)

/**
 * Keystore decryption failed. Expected after a device restore, because the Keystore key does not
 * travel with the Account (ADR-0001) — which is why this is a prompt to re-enter, not an auth error.
 */
fun credentialsUnreadable(cause: Throwable? = null): SyncError = SyncError(
    errorClass = ErrorClass.CREDENTIALS_UNREADABLE,
    summary = ErrorSummary.CREDENTIALS_UNREADABLE,
    httpStatus = null,
    firstBodyLine = null,
    certificateOffered = false,
    requestMethod = null,
    cause = cause,
)

/**
 * A request failed and the KeyManager alias callback never fired. Worded as the observation "No
 * certificate was sent" because TLS cannot report what the server would have accepted — only what
 * was offered.
 *
 * @throws IllegalArgumentException if [evidence] says a certificate was offered; then this is not
 * the class that describes the failure.
 */
fun noCertificateSent(evidence: ResponseEvidence): SyncError {
    require(!evidence.certificateOffered) {
        "NO_CERTIFICATE_SENT describes a request that offered no certificate"
    }
    return evidence.toError(ErrorClass.NO_CERTIFICATE_SENT, ErrorSummary.NO_CERTIFICATE_SENT)
}

/** A response arrived but could not be understood: XML, iCalendar or vCard level parse failure. */
fun malformedResponse(evidence: ResponseEvidence, cause: Throwable? = null): SyncError =
    evidence.toError(ErrorClass.MALFORMED_RESPONSE, ErrorSummary.MALFORMED_RESPONSE, cause)
