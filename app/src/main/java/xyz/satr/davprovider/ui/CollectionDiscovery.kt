package xyz.satr.davprovider.ui

import java.security.MessageDigest
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xyz.satr.davprovider.core.CollectionType
import xyz.satr.davprovider.core.DavAccount
import xyz.satr.davprovider.core.DavCollection
import xyz.satr.davprovider.core.DavHttpClientFactory
import xyz.satr.davprovider.core.ErrorClass
import xyz.satr.davprovider.core.SyncErrorClassifier
import xyz.satr.davprovider.error.malformedResponse

/**
 * What one attempt at discovery produced. [completed] distinguishes "the server has no Collections"
 * from "nothing was learned about the server": only the former may mark a stored Collection
 * unavailable, so a failed enumeration can never look like a disappearance.
 */
internal data class DiscoveryOutcome(
    val completed: Boolean,
    val collections: List<DavCollection>,
    val notes: List<String>,
)

/**
 * The one request §8's walk makes, as a function.
 *
 * The walk's decisions — which fallback it took, and whether what it calls a completed enumeration
 * actually ran to the end — are the part that can mark a live Collection unavailable, so they are
 * exercised against canned responses rather than only against a server.
 */
internal fun interface DiscoveryProbe {
    fun propfind(url: String, depth: Int, body: String, label: String): DavAttempt
}

/**
 * §8's discovery: a user-supplied base URL resolved to a set of Collections, falling back rather
 * than failing, because one server's way of publishing itself must not end the walk.
 *
 * `.well-known/carddav` and `.well-known/caldav` → `current-user-principal` → the home set → a
 * `Depth: 1` enumeration; and when none of that resolves, the base URL itself read as a Collection.
 * Every attempt is recorded in [DiscoveryOutcome.notes] with what it asked, where it was sent, what
 * came back and what that was worth — which is what removes the need for a bare "couldn't find
 * CalDAV or CardDAV services".
 *
 * Blocking, like the rest of the app's network: callers run it off the main thread.
 */
internal object CollectionDiscovery {

    private val WELL_KNOWN_PATHS = listOf("carddav", "caldav")

    private const val CURRENT_USER_PRINCIPAL = "current-user-principal"

    /** Only the resolved id is derived outside; the length fixed here is 128 bits of SHA-256. */
    private const val ID_PREFIX = "dav-"
    private const val ID_BYTES = 16
    private const val HEX = "0123456789abcdef"

    private val PRINCIPAL_BODY = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:propfind xmlns:d="DAV:">
          <d:prop>
            <d:current-user-principal/>
          </d:prop>
        </d:propfind>
    """.trimIndent()

    /**
     * Both home sets in one request: a property a server does not implement comes back as an empty
     * `propstat` inside the same `207`, so asking for the two together cannot fail on the one the
     * server does not have.
     */
    private val HOME_SET_BODY = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:propfind xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav" xmlns:cal="urn:ietf:params:xml:ns:caldav">
          <d:prop>
            <card:addressbook-home-set/>
            <cal:calendar-home-set/>
          </d:prop>
        </d:propfind>
    """.trimIndent()

    /**
     * Everything one Collection has to say about itself: what it is, what to call it, and its
     * colour. The colour is asked for under the name the calendar world settled on; a server that
     * answers with the address book's own spelling is read just as well.
     */
    private val COLLECTION_BODY = """
        <?xml version="1.0" encoding="utf-8"?>
        <d:propfind xmlns:d="DAV:" xmlns:i="http://apple.com/ns/ical/">
          <d:prop>
            <d:resourcetype/>
            <d:displayname/>
            <i:calendar-color/>
            <i:addressbook-color/>
          </d:prop>
        </d:propfind>
    """.trimIndent()

    /** The real walk, over the Account's own client and the §5 classifier. */
    fun discover(
        factory: DavHttpClientFactory,
        classifier: SyncErrorClassifier,
        davAccount: DavAccount,
    ): DiscoveryOutcome = walk(davAccount.baseUrl) { url, depth, body, label ->
        DavProbe.propfindFollowingRedirects(factory, classifier, davAccount, url, depth, body, label)
    }

    /**
     * The walk itself, over an injected probe.
     *
     * `completed` is true only when an enumeration ran to the end — a `Depth: 1` listing of a home
     * set, or the base URL read as a Collection — and every enumeration that was attempted did. A
     * partial walk returns no Collections at all rather than some, so that a listing which stopped
     * half way can never be merged as though the rest had disappeared.
     */
    fun walk(baseUrl: String, probe: DiscoveryProbe): DiscoveryOutcome {
        val notes = ArrayList<String>(6)
        val found = ArrayList<DiscoveredCollection>(4)
        val seen = HashSet<String>(4)
        var enumerated = 0
        var failed = 0

        val origin = baseUrl.toHttpUrlOrNull()
            ?: return DiscoveryOutcome(
                completed = false,
                collections = emptyList(),
                notes = listOf("base URL $baseUrl is not a URL, so no request was sent"),
            )

        // 1. Both well-known paths, at the origin as RFC 6764 puts them, so a base URL that is a
        //    sub-path does not move them. The second is asked even when the first answered, because a
        //    server may publish CardDAV and CalDAV at two different roots with a principal each — and
        //    the second principal is where the other half of the Collections is.
        val principals = LinkedHashSet<String>(2)
        for (protocol in WELL_KNOWN_PATHS) {
            probeStep(
                probe = probe,
                notes = notes,
                label = "/.well-known/$protocol",
                url = origin.newBuilder().encodedPath("/.well-known/$protocol").build().toString(),
                depth = 0,
                body = PRINCIPAL_BODY,
                missing = CURRENT_USER_PRINCIPAL,
                parse = WebDavXml::currentUserPrincipal,
                describe = { "current-user-principal $it" },
            )?.let { principals += it }
        }

        // 2. The base URL may be the principal itself — a server that serves DAV at its root has no
        //    well-known path to redirect from.
        if (principals.isEmpty()) {
            probeStep(
                probe = probe,
                notes = notes,
                label = "base URL as principal",
                url = baseUrl,
                depth = 0,
                body = PRINCIPAL_BODY,
                missing = CURRENT_USER_PRINCIPAL,
                parse = WebDavXml::currentUserPrincipal,
                describe = { "current-user-principal $it" },
            )?.let { principals += it }
        }

        // 3. The home sets, which name the Collections to enumerate. Keyed by the home set's URL, so
        //    one named by both principals is enumerated once.
        val homeSteps = LinkedHashMap<String, String>(2)
        for (principal in principals) {
            val homes = probeStep(
                probe = probe,
                notes = notes,
                label = "principal",
                url = principal,
                depth = 0,
                body = HOME_SET_BODY,
                missing = "home set",
                parse = WebDavXml::homeSets,
                describe = ::describeHomeSets,
            ) ?: continue
            homes.addressBook?.let { homeSteps.putIfAbsent(it, "address book home") }
            homes.calendar?.let { homeSteps.putIfAbsent(it, "calendar home") }
        }

        if (homeSteps.isEmpty()) {
            // The last fallback in §8's list: nothing named a home set, so the base URL is read as
            // one Collection — how an Account on a server that publishes no principal at all is
            // still discoverable.
            val itself = probeStep(
                probe = probe,
                notes = notes,
                label = "base URL as Collection",
                url = baseUrl,
                depth = 0,
                body = COLLECTION_BODY,
                missing = "address book or calendar",
                parse = WebDavXml::collections,
                describe = ::describeChildren,
            )
            val single = itself?.firstOrNull()
            if (single == null) {
                failed++
            } else {
                enumerated++
                if (seen.add(single.url)) found += single
            }
        } else {
            // The enumeration: one `Depth: 1` listing per home set, in the order the principals named
            // them. Each is listed separately because a server may publish them at unrelated paths.
            for ((home, label) in homeSteps) {
                val children = probeStep(
                    probe = probe,
                    notes = notes,
                    label = label,
                    url = home,
                    depth = 1,
                    body = COLLECTION_BODY,
                    missing = "address book or calendar",
                    parse = WebDavXml::collections,
                    describe = ::describeChildren,
                )
                if (children == null) {
                    failed++
                } else {
                    enumerated++
                    for (child in children) if (seen.add(child.url)) found += child
                }
            }
        }

        val completed = enumerated > 0 && failed == 0
        return DiscoveryOutcome(
            completed = completed,
            collections = if (completed) found.map { it.asCollection() } else emptyList(),
            notes = notes,
        )
    }

    /**
     * The id a Collection keeps for the whole life of the Account.
     *
     * It lands in `RawContacts.SYNC3` and is what ties a synced row to its Collection, so it is
     * derived from the Collection's URL path — what names one Collection on one server — and never
     * from the display name, which the user can change at the server, nor from anything random,
     * which would orphan every row of a Collection the next time it was enumerated. A trailing slash
     * is not a different Collection, so a URL typed with one and enumerated without one is the same
     * Collection and keeps the same rows.
     */
    fun collectionId(url: String): String {
        val parsed = url.toHttpUrlOrNull()
        // A URL that is not one cannot name a Collection; hashing it whole still keeps two of them
        // apart instead of handing both the same id.
        val key = parsed?.encodedPath?.trimEnd('/')?.ifEmpty { "/" } ?: url
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val id = StringBuilder(ID_PREFIX.length + ID_BYTES * 2)
        id.append(ID_PREFIX)
        for (index in 0 until ID_BYTES) {
            val byte = digest[index].toInt() and 0xFF
            id.append(HEX[byte shr 4]).append(HEX[byte and 0x0F])
        }
        return id.toString()
    }

    /**
     * One step of the walk: the request, what it read, and its line in the attempt log.
     *
     * The note is written here rather than at each call site because every step reports the same
     * facts — where it was sent, where a redirect carried it, the status, and what came back — and
     * §8 wants all of it, including for the steps that failed.
     *
     * @param missing the wording for a server that answered without reporting what was asked.
     * @return the parsed value, or null when the step did not produce one.
     */
    private fun <T> probeStep(
        probe: DiscoveryProbe,
        notes: MutableList<String>,
        label: String,
        url: String,
        depth: Int,
        body: String,
        missing: String,
        parse: (String, String) -> ReadResult<T?>,
        describe: (T) -> String,
    ): T? {
        val attempt = probe.propfind(url, depth, body, label)
        val evidence = attempt.outcome.evidence
        val status = evidence.httpStatus
        // Only a response that arrived and answered can be a multistatus; anything else is reported
        // by its class, which is what makes an HTML login page read as the proxy interference it is.
        val read = if (status != null && status in 200..299) {
            parse(evidence.body.orEmpty(), attempt.url)
        } else {
            null
        }
        val value = (read as? ReadResult.Read)?.value
        notes += buildString {
            append(label).append(": PROPFIND ").append(url).append(" (Depth ").append(depth).append(")")
            attempt.hops.forEach { append(" → ").append(it.status).append(" → ").append(it.location) }
            append(" → ")
            append(
                when {
                    value != null -> describe(value)
                    status == null -> attempt.outcome.error.summary
                    else -> "$status${redirectTarget(attempt)}, ${refusal(attempt, read, missing)}"
                }
            )
        }
        return value
    }

    /**
     * Where a redirect the walk did not follow points, verbatim from the response.
     *
     * A redirect the walk was carried by is already in the log as a hop; this is the one it refused —
     * into the proxy's login page, or off the Account's origin — and there the destination is the
     * fact that says which, and what to do about it.
     */
    private fun redirectTarget(attempt: DavAttempt): String {
        val evidence = attempt.outcome.evidence
        val status = evidence.httpStatus
        val location = evidence.locationHeader
        if (status == null || status !in 300..399 || location == null) return ""
        return " to $location"
    }

    /**
     * Why a step that got an answer did not get what it asked for.
     *
     * The classifier's reading comes first: it is what reports a proxy login redirect, an HTML body
     * or a `401` as the class that describes it. Where it reports no error at all — which is what it
     * says about the `2xx` a DAV server answers a `PROPFIND` with — the useful fact is either that
     * the body could not be read or that it reported nothing.
     */
    private fun refusal(attempt: DavAttempt, read: ReadResult<*>?, missing: String): String {
        val error = attempt.outcome.error
        val signalled = error.errorClass != ErrorClass.ORIGIN_REFUSED_INFO || error.davCondition != null
        return when {
            signalled -> error.summary
            read is ReadResult.Unreadable -> malformedResponse(attempt.outcome.evidence).summary
            else -> "the server reported no $missing"
        }
    }

    private fun describeHomeSets(homes: HomeSets): String = listOfNotNull(
        homes.addressBook?.let { "addressbook-home-set $it" },
        homes.calendar?.let { "calendar-home-set $it" },
    ).ifEmpty { listOf("no addressbook-home-set or calendar-home-set") }.joinToString(", ")

    private fun describeChildren(children: List<DiscoveredCollection>): String {
        if (children.isEmpty()) return "no address book or calendar"
        return listOfNotNull(
            children.count { it.type == CollectionType.ADDRESS_BOOK }
                .takeIf { it > 0 }?.let { count(it, "address book") },
            children.count { it.type == CollectionType.CALENDAR }
                .takeIf { it > 0 }?.let { count(it, "calendar") },
        ).joinToString(", ")
    }

    private fun count(amount: Int, noun: String): String = if (amount == 1) "1 $noun" else "$amount ${noun}s"

    private fun DiscoveredCollection.asCollection() = DavCollection(
        id = collectionId(url),
        url = url,
        type = type,
        displayName = displayName,
        color = color,
        // §8: a Collection that has just appeared on the server does not start writing into the
        // phone; the caller's merge keeps the selection of one that was already there.
        selected = false,
        available = true,
    )

    /**
     * Folds a completed enumeration into the stored selection.
     *
     * Every stored Collection survives — one the server no longer lists becomes unavailable rather
     * than deleted, because a listing that failed and a Collection that vanished are not the same
     * observation. A newly discovered Collection arrives **unselected**, so a calendar that appeared
     * on the server does not silently start writing into the phone.
     *
     * A **pinned** Collection is exempt from the retiring half. The user named it by URL precisely
     * because the walk does not reach it — it is in no home set — so its absence from a listing is
     * the expected result rather than news about the server. Retiring it would take the Collection
     * out of the sync, and with it the Account's schedule if it was the only one selected. Only the
     * sync's own answer for that URL may retire it.
     *
     * Only ever called with [DiscoveryOutcome.completed].
     */
    fun merge(existing: List<DavCollection>, discovered: List<DavCollection>): List<DavCollection> {
        val fresh = discovered.associateBy { it.id }
        val kept = existing.map { prior ->
            fresh[prior.id]?.copy(id = prior.id, selected = prior.selected, pinned = prior.pinned)
                ?: if (prior.pinned) prior else prior.copy(available = false)
        }
        val added = discovered
            .filter { entry -> existing.none { it.id == entry.id } }
            .map { it.copy(selected = false) }
        return kept + added
    }
}
