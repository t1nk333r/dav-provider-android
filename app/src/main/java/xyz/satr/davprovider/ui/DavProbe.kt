package xyz.satr.davprovider.ui

import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import xyz.satr.davprovider.core.DavAccount
import xyz.satr.davprovider.core.DavHttpClientFactory
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

/**
 * The account UI's only network call: a single `PROPFIND Depth: 0`, run on demand.
 *
 * Both callers — the save-time check and the Diagnose matrix — send this one request per variant
 * and classify the answer with the same classifier the sync engine uses, so what the user reads
 * here is what a sync would have reported. Diagnose is bounded because it builds its variants by
 * copying an account: four copies, four requests, no retries of its own.
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

    /** Blocking. Callers run this off the main thread. */
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
            return ProbeOutcome(variant, certificateUnavailable(e), failedEvidence(e))
        }
        // Redirects must not be followed, and the factory does not follow them: a proxy rejection
        // is a 3xx whose Location is the evidence, and following it would receive the login page.
        val request = Request.Builder()
            .url(url)
            .method(METHOD, PROPFIND_BODY.toRequestBody(XML))
            .header("Depth", "0")
            .build()
        return try {
            client.okHttp.newCall(request).execute().use { response ->
                val evidence = HttpEvidence.of(response, client)
                ProbeOutcome(variant, classifier.classify(evidence), evidence)
            }
        } catch (e: IOException) {
            val evidence = failedEvidence(e)
            ProbeOutcome(variant, classifier.classify(evidence), evidence)
        }
    }

    private fun failedEvidence(cause: Throwable) = ResponseEvidence(
        httpStatus = null,
        locationHeader = null,
        wwwAuthenticate = null,
        contentType = null,
        body = null,
        requestMethod = METHOD,
        certificateOffered = false,
        transportFailure = cause,
    )
}
