package xyz.satr.davprovider.error

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import xyz.satr.davprovider.core.ErrorClass
import xyz.satr.davprovider.core.ResponseEvidence
import xyz.satr.davprovider.core.SyncError
import xyz.satr.davprovider.core.SyncErrorClassifier

private const val DAV_NAMESPACE = "DAV:"

/**
 * The proxy's own login page: being redirected here means the token, not the Origin, refused us.
 *
 * Shared with the sync path, which must prefer a redirect carrying this location over the hop that
 * followed it — otherwise rule 2 can never match once dav4jvm has followed the redirect.
 */
internal const val ACCESS_LOGIN_PATH = "/cdn-cgi/access/login"

/**
 * §5's rules, in order, first match wins.
 *
 * The status code alone never decides severity. A 403 carrying a DAV error body is the Origin
 * answering and is informational; a 302 into a proxy login page is a hard credential failure. Both
 * were observed on the real server, and any implementation that switches on status first
 * reproduces the opacity this app exists to remove.
 */
class SyncErrorClassifierImpl : SyncErrorClassifier {

    override fun classify(evidence: ResponseEvidence): SyncError {

        // 1. A DAV error body means the Origin itself answered, whatever the status says.
        val davError = parseDavError(evidence.body)
        if (davError != null) {
            return evidence.toError(
                errorClass = ErrorClass.ORIGIN_REFUSED_INFO,
                summary = originRefusedSummary(davError.condition),
                davCondition = davError.condition,
            )
        }

        val status = evidence.httpStatus

        // 2. A redirect into the proxy's login page: the Credentials never reached the Origin.
        val location = evidence.locationHeader
        if (status != null && status in 300..399 && location != null &&
            location.contains(ACCESS_LOGIN_PATH, ignoreCase = true)
        ) {
            return evidence.toError(
                ErrorClass.PROXY_REJECTED_CREDENTIALS,
                ErrorSummary.PROXY_REJECTED_CREDENTIALS,
            )
        }

        // 3. An authentication challenge: the Origin wants Credentials of its own.
        if (!evidence.wwwAuthenticate.isNullOrBlank()) {
            return evidence.toError(
                ErrorClass.ORIGIN_WANTS_CREDENTIALS,
                ErrorSummary.ORIGIN_WANTS_CREDENTIALS,
            )
        }

        // 4. HTML where a DAV response was expected: something in between rewrote the response.
        if (isDavRequest(evidence.requestMethod) && evidence.looksLikeHtml()) {
            return evidence.toError(ErrorClass.PROXY_INTERFERENCE, ErrorSummary.PROXY_INTERFERENCE)
        }

        // 5. No response at all is the transport class, not a server verdict.
        val transportFailure = evidence.transportFailure
        if (status == null) {
            return evidence.toError(
                ErrorClass.TRANSPORT_FAILURE,
                ErrorSummary.TRANSPORT_FAILURE,
                cause = transportFailure,
            )
        }

        // A failure raised with a status did reach the server, so "Couldn't reach the server" would
        // be false. When that status is a success, the exchange gave out after the response was
        // received — a body cut short — and reporting it as "no error" would present a truncated
        // listing as a complete one. That is the parse class. Any other status is still the server's
        // verdict and is classified as such below.
        if (transportFailure != null && status in 200..299) {
            return evidence.toError(
                ErrorClass.MALFORMED_RESPONSE,
                ErrorSummary.MALFORMED_RESPONSE,
                cause = transportFailure,
            )
        }

        // 6. Only now does the status decide.
        return when {
            status >= 500 || status == 429 ->
                evidence.toError(ErrorClass.SERVER_ERROR, ErrorSummary.SERVER_ERROR)

            status == 404 ->
                evidence.toError(ErrorClass.NOT_FOUND, ErrorSummary.NOT_FOUND)

            status == 405 || (status == 400 && evidence.requestMethod.equals("OPTIONS", ignoreCase = true)) ->
                evidence.toError(ErrorClass.METHOD_REFUSED, ErrorSummary.METHOD_REFUSED)

            // A challenge-less 401 is still a server asking for Credentials the request did not carry.
            status == 401 ->
                evidence.toError(ErrorClass.ORIGIN_WANTS_CREDENTIALS, ErrorSummary.ORIGIN_WANTS_CREDENTIALS)

            // Success: the classifier is not meant to be called here, and a DAV error body (rule 1)
            // is the only thing that would have made it an error. Class 3 keeps this informational,
            // so classifying a success by mistake can never mark a Collection failed.
            status in 200..299 ->
                evidence.toError(ErrorClass.ORIGIN_REFUSED_INFO, ErrorSummary.NO_ERROR)

            // Anything left is a response the Origin's contract does not explain, e.g. a bare 403 or
            // a redirect to a web page: something in between changed it. The status still shows verbatim.
            else ->
                evidence.toError(ErrorClass.PROXY_INTERFERENCE, ErrorSummary.PROXY_INTERFERENCE)
        }
    }
}

/**
 * The Origin's own error report, or null when the body is not one.
 *
 * Detection keys on the DAV namespace rather than the literal tag text: the element may use any
 * prefix, declare its namespace in any attribute order, and sit inside a multistatus body rather
 * than at the root.
 */
private class DavErrorBody(val condition: String?)

private fun parseDavError(body: String?): DavErrorBody? {
    // Cheap reject first: a proxy's block page never declares the DAV namespace, and the XML
    // parser is not free. An empty body is the common case for a bare status failure.
    if (body == null || !body.contains(DAV_NAMESPACE)) return null
    // Classifying an error must never itself throw: a truncated or hostile body means rule 1 does
    // not apply, and the rules below still describe what was observed.
    return runCatching { readDavError(body) }.getOrNull()
}

private fun readDavError(body: String): DavErrorBody? {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // Server-supplied XML: never let it fetch a URL or expand entities. Not every feature is
        // implemented on every platform, and refusing the document is worse than parsing it.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { isExpandEntityReferences = false }
        runCatching { isXIncludeAware = false }
    }
    val builder = factory.newDocumentBuilder().apply {
        // An unparseable body is an expected outcome here — it only means rule 1 does not apply —
        // so the parser must not print it. Fatal errors still surface through the call below.
        setErrorHandler(object : ErrorHandler {
            override fun warning(exception: SAXParseException) = Unit
            override fun error(exception: SAXParseException) = Unit
            override fun fatalError(exception: SAXParseException): Unit = throw exception
        })
    }
    val document = builder.parse(InputSource(StringReader(body)))
    val error = document.getElementsByTagNameNS(DAV_NAMESPACE, "error").item(0) as? Element
        ?: return null
    return DavErrorBody(firstConditionName(error))
}

/** The DAV precondition element inside `<error>`; its name is the condition the server reported. */
private fun firstConditionName(error: Element): String? {
    var child: Node? = error.firstChild
    while (child != null) {
        if (child.nodeType == Node.ELEMENT_NODE) {
            val element = child as Element
            return element.localName ?: element.tagName
        }
        child = child.nextSibling
    }
    return null
}

/**
 * Rule 4 is scoped to DAV methods. A plain GET legitimately returns HTML — a discovery probe of a
 * web root, or a photo — so HTML there is the server's answer rather than interference. Every other
 * method this app issues is a DAV method, and an unreported method is treated as one.
 */
private fun isDavRequest(method: String?): Boolean =
    method == null || !(method.equals("GET", ignoreCase = true) || method.equals("HEAD", ignoreCase = true))

private fun ResponseEvidence.looksLikeHtml(): Boolean {
    if (contentType?.contains("text/html", ignoreCase = true) == true) return true
    val head = body?.trimStart(' ', '\t', '\r', '\n', '\uFEFF') ?: return false
    return head.startsWith("<html", ignoreCase = true) ||
        head.startsWith("<!DOCTYPE html", ignoreCase = true)
}
