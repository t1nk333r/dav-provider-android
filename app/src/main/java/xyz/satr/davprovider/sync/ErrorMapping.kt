package xyz.satr.davprovider.sync

import at.bitfire.dav4jvm.ktor.exception.DavException
import org.xmlpull.v1.XmlPullParserException
import xyz.satr.davprovider.core.CredentialsUnreadableException
import xyz.satr.davprovider.core.ResponseEvidence
import xyz.satr.davprovider.core.SyncError
import xyz.satr.davprovider.core.SyncErrorClassifier
import xyz.satr.davprovider.error.certificateUnavailable
import xyz.satr.davprovider.error.credentialsUnreadable
import xyz.satr.davprovider.error.malformedResponse
import xyz.satr.davprovider.error.noCertificateSent
import xyz.satr.davprovider.net.ClientCertificateUnavailableException
import java.io.EOFException
import java.io.IOException

/**
 * Turns a thrown failure into a [SyncError] for §5.
 *
 * The HTTP failures go through the classifier over the best evidence available. dav4jvm's exceptions
 * carry a status code and a body excerpt but never headers, so the evidence is only as complete as
 * the HTTP layer's recording allows: without it, §5's proxy rule and its `WWW-Authenticate` rule
 * cannot fire, and the failure is classified from the status and body alone.
 *
 * The classes that have no HTTP exchange — the certificate alias is gone, the KeyStore could not
 * decrypt — come from the error layer directly: there is no response to read them from.
 */
internal class ErrorMapping(
    private val classifier: SyncErrorClassifier,
    private val certificateOffered: Boolean,
    /** Whether the Account selects a client certificate at all; only the app can know this. */
    private val certificateConfigured: Boolean,
    /** Evidence of the exchange to report, or null when the HTTP layer does not record one. */
    private val lastExchange: (() -> ResponseEvidence?)? = null,
) {

    fun classify(cause: Throwable, method: String?): SyncError {
        val evidence = evidenceOf(cause, method)

        return when {
            cause is ClientCertificateUnavailableException -> certificateUnavailable(cause)
            cause is CredentialsUnreadableException -> credentialsUnreadable(cause)

            // §5 class 6 is an observation, not an inference: a certificate was configured, its alias
            // callback never fired, and nothing came back at all. A response that arrived means the
            // handshake resolved, so a certificate cannot be blamed for what failed after it — and
            // this class is terminal, which makes blaming it there expensive.
            certificateConfigured && !certificateOffered && evidence.httpStatus == null ->
                noCertificateSent(evidence)

            // §5 class 11: a response arrived, but its body could not be read to the end.
            isMalformed(cause, evidence) ->
                malformedResponse(evidence, cause)

            else -> classifier.classify(evidence)
        }
    }

    /**
     * §5 class 11: dav4jvm accepted a response as one, then failed to read its body — the XML could not
     * be parsed, or the stream ended before it did.
     *
     * The cause chain is the discriminator, and only this side can see it: dav4jvm sometimes wraps the
     * failure in its own [DavException] with no status of its own, and sometimes lets the raw I/O
     * failure through. Either way the exchange produced a response, which is what separates this from
     * the transport class. A redirect is left to the classifier, because §5's redirect rules are what
     * explain it.
     *
     * Without this, a listing cut short mid-body would be classified by the 207 that carried it and —
     * being a success — quietly become informational, passing for a listing that completed.
     */
    private fun isMalformed(cause: Throwable, evidence: ResponseEvidence): Boolean {
        var current: Throwable? = cause
        while (current != null) {
            if (current is XmlPullParserException || current is EOFException) return true
            current = current.cause
        }

        val status = evidence.httpStatus ?: return false
        if (status in 300..399) return false

        return (cause is DavException && cause.statusCode == null) || cause is IOException
    }

    private fun evidenceOf(cause: Throwable, method: String?): ResponseEvidence {
        val dav = cause as? DavException
        val captured = lastExchange?.invoke()
        val status = dav?.statusCode ?: captured?.httpStatus
        return ResponseEvidence(
            httpStatus = status,
            // dav4jvm exposes no response headers, so these can only come from the HTTP layer.
            locationHeader = captured?.locationHeader,
            wwwAuthenticate = captured?.wwwAuthenticate,
            contentType = captured?.contentType,
            body = dav?.responseExcerpt ?: captured?.body,
            requestMethod = method,
            certificateOffered = certificateOffered,
            // The cause of a failure that arrived after a response is still what the classifier needs
            // to tell a body cut short from a server verdict; with no status at all it is the transport
            // class, which §5 also describes by the absence of a response.
            transportFailure = cause,
        )
    }
}
