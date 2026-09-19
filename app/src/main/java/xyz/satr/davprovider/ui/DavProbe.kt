package xyz.satr.davprovider.ui

import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.satr.davprovider.core.DavAccount
import xyz.satr.davprovider.core.DavHttpClientFactory
import xyz.satr.davprovider.core.ErrorClass
import xyz.satr.davprovider.core.ResponseEvidence
import xyz.satr.davprovider.core.SyncError
import xyz.satr.davprovider.core.SyncErrorClassifier
import xyz.satr.davprovider.error.certificateUnavailable
import xyz.satr.davprovider.net.ClientCertificateUnavailableException
import xyz.satr.davprovider.net.HttpEvidence

/** One request and everything §5 needs to explain it. */
internal data class ProbeOutcome(
    val variant: String,
    val error: SyncError,
    val evidence: ResponseEvidence,
)

/** A redirect a probe was carried by, kept so the attempt log can show where each step landed. */
internal data class RedirectHop(val status: Int, val location: String)

/** One `PROPFIND` exchange: where it was finally sent, what redirected it, and what came back. */
internal data class DavAttempt(
    val url: String,
    val hops: List<RedirectHop>,
    val outcome: ProbeOutcome,
)

/** How far one probe is carried by redirects before the redirect itself is the answer. */
private const val MAX_REDIRECTS = 3

/**
 * The account UI's network calls: a `PROPFIND`, run on demand.
 *
 * Every caller — the save-time check, the Diagnose matrix and §8's discovery walk — classifies the
 * answer with the same classifier the sync engine uses, so what the user reads here is what a sync
 * would have reported. Diagnose is bounded because it builds its variants by copying an account:
 * four copies, four requests, no retries of its own.
 */
internal object DavProbe {

    const val METHOD = "PROPFIND"

    private val XML = "application/xml; charset=utf-8".toMediaType()

    /**
     * `PROPFIND` and not `OPTIONS`: an unauthenticated `OPTIONS` can answer 400 on a DAV server,
     * which reads as a broken configuration, while `PROPFIND` separates 207, 302 and 401 cleanly.
     */
    private val PROPFIND_BODY = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:propfind xmlns:d="DAV:">
          <d:prop>
            <d:resourcetype/>
            <d:displayname/>
            <d:current-user-principal/>
          </d:prop>
        </d:propfind>
    """.trimIndent()

    /**
     * A `PROPFIND` whose answer is taken at face value, redirects included.
     *
     * Saving an Account and Diagnose both want the exchange itself: a proxy rejection is a `3xx`
     * whose `Location` is the evidence, so following it would receive the login page instead.
     *
     * Blocking. Callers run this off the main thread.
     */
    fun propfind(
        factory: DavHttpClientFactory,
        classifier: SyncErrorClassifier,
        davAccount: DavAccount,
        url: String,
        variant: String,
    ): ProbeOutcome {
        val client = try {
            factory.create(davAccount)
        } catch (e: ClientCertificateUnavailableException) {
            return certificateOutcome(variant, e)
        }
        return try {
            client.okHttp.newCall(request(url, depth = 0, body = PROPFIND_BODY)).execute().use { response ->
                val evidence = HttpEvidence.ofWholeBody(response, client)
                ProbeOutcome(variant, classifier.classify(evidence), evidence)
            }
        } catch (e: IOException) {
            val evidence = HttpEvidence.transportFailure(METHOD, client.certificateOffered, e)
            ProbeOutcome(variant, classifier.classify(evidence), evidence)
        }
    }

    /**
     * The same request, carried by redirects to where they land.
     *
     * Discovery asks a server for `/.well-known/carddav`, which is *supposed* to be answered with a
     * redirect, so a `3xx` is a step of the walk rather than a verdict. Two redirects are still
     * never followed, and each is reported with the response that carried it:
     *
     * - one into the proxy's login page, which [classifier] reports as the credential rejection it
     *   is, and which would otherwise fetch a login form and call it a response;
     * - one that leaves the Account's own origin, because an Account's Headers and Basic credentials
     *   are attached to every request this client sends and a `Location` names the host that would
     *   receive them. Only the client certificate is already confined to the origin.
     *
     * Blocking. Callers run this off the main thread.
     */
    fun propfindFollowingRedirects(
        factory: DavHttpClientFactory,
        classifier: SyncErrorClassifier,
        davAccount: DavAccount,
        url: String,
        depth: Int,
        body: String,
        variant: String,
    ): DavAttempt {
        val client = try {
            factory.create(davAccount)
        } catch (e: ClientCertificateUnavailableException) {
            return DavAttempt(url, emptyList(), certificateOutcome(variant, e))
        }
        val hops = ArrayList<RedirectHop>(MAX_REDIRECTS)
        var target = url
        while (true) {
            val response = try {
                client.okHttp.newCall(request(target, depth, body)).execute()
            } catch (e: IOException) {
                val evidence = HttpEvidence.transportFailure(METHOD, client.certificateOffered, e)
                return DavAttempt(target, hops, ProbeOutcome(variant, classifier.classify(evidence), evidence))
            }
            response.use { answered ->
                val evidence = HttpEvidence.ofWholeBody(answered, client)
                val error = classifier.classify(evidence)
                val next = nextHop(
                    current = target,
                    status = answered.code,
                    location = answered.header("Location"),
                    errorClass = error.errorClass,
                    hopsTaken = hops.size,
                )
                if (next == null) return DavAttempt(target, hops, ProbeOutcome(variant, error, evidence))
                hops += RedirectHop(answered.code, next)
                target = next
            }
        }
    }

    private fun request(url: String, depth: Int, body: String): Request = Request.Builder()
        .url(url)
        .method(METHOD, body.toRequestBody(XML))
        .header("Depth", depth.toString())
        .build()

    /** Nothing was ever offered, so the evidence is the absence of an exchange. */
    private fun certificateOutcome(variant: String, cause: Throwable): ProbeOutcome {
        val evidence = HttpEvidence.transportFailure(METHOD, certificateOffered = false, cause = cause)
        return ProbeOutcome(variant, certificateUnavailable(cause), evidence)
    }
}

/**
 * Where a response sends a probe next, or null when it is the end of the road — the policy of what
 * discovery is willing to be carried by, in one place:
 *
 * - a response that is not a redirect is an answer, not a hop;
 * - a redirect into the proxy's login page is the credential rejection the classifier named, and
 *   following it would swap that reading for a login form;
 * - a redirect that leaves the Account's own origin is not followed, because the Headers and the
 *   Basic credentials of an Account travel on every request the client sends and a `Location` names
 *   the host that would receive them. Only the client certificate is already confined to the origin;
 * - and one probe is carried by at most [MAX_REDIRECTS] hops, after which the redirect itself is the
 *   answer rather than a walk that never ends.
 */
internal fun nextHop(
    current: String,
    status: Int,
    location: String?,
    errorClass: ErrorClass,
    hopsTaken: Int,
): String? {
    if (status !in 300..399 || location == null) return null
    if (errorClass == ErrorClass.PROXY_REJECTED_CREDENTIALS) return null
    if (hopsTaken >= MAX_REDIRECTS) return null
    val next = resolveUrl(current, location) ?: return null
    return next.takeIf { sameOrigin(current, it) }
}

private fun sameOrigin(from: String, to: String): Boolean {
    val first = from.toHttpUrlOrNull() ?: return false
    val second = to.toHttpUrlOrNull() ?: return false
    return first.scheme == second.scheme && first.host == second.host && first.port == second.port
}

/**
 * A DAV `href` or a `Location`, joined to the URL it was written against.
 *
 * An `href` may be absolute, an absolute path or relative, and the Location of a redirect is a
 * reference of the same kind; OkHttp's `resolve` is the app's convention for joining them (the photo
 * fetch relies on it too). Null when the reference is neither a URL nor resolvable against [baseUrl].
 */
internal fun resolveUrl(baseUrl: String, reference: String): String? =
    baseUrl.toHttpUrlOrNull()?.resolve(reference)?.toString() ?: reference.toHttpUrlOrNull()?.toString()
