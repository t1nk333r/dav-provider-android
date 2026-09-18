package xyz.satr.davprovider.sync

import at.bitfire.dav4jvm.ktor.DavAddressBook
import at.bitfire.dav4jvm.ktor.DavCalendar
import at.bitfire.dav4jvm.ktor.DavCollection as DavCollectionResource
import at.bitfire.dav4jvm.ktor.MultiStatusItem
import at.bitfire.dav4jvm.ktor.Response as DavResponse
import at.bitfire.dav4jvm.ktor.exception.DavException
import at.bitfire.dav4jvm.property.caldav.CalDAV
import at.bitfire.dav4jvm.property.caldav.CalendarData
import at.bitfire.dav4jvm.property.caldav.GetCTag
import at.bitfire.dav4jvm.property.carddav.AddressData
import at.bitfire.dav4jvm.property.webdav.GetETag
import at.bitfire.dav4jvm.property.webdav.ResourceType
import at.bitfire.dav4jvm.property.webdav.SyncToken
import at.bitfire.dav4jvm.property.webdav.WebDAV
import io.ktor.client.HttpClient
import io.ktor.http.Url
import xyz.satr.davprovider.core.CollectionType
import xyz.satr.davprovider.core.DavCollection
import xyz.satr.davprovider.core.RemoteItem

/** The DAV status with which `sync-collection` reports a member that is gone (RFC 6578). */
private const val REMOVED_STATUS = 404

/** Server answers that mean "I don't do sync-collection" when they carry no condition element. */
private val UNSUPPORTED_REPORT_STATUS = setOf(405, 501)

/**
 * One Collection's DAV access for one sync run.
 *
 * Every request builds a fresh [DavResource] at [location] and is collected before the next one
 * starts. dav4jvm rewrites a resource's location in place when it follows a redirect while the flows
 * it returns are lazy (dav4jvm#209), so the location is carried here instead: no two flows ever come
 * from one resource, and the redirect the server applied is what the next request uses.
 */
internal class CollectionSession(
    private val http: HttpClient,
    private val collection: DavCollection,
    /** Called before each DAV operation, so evidence never outlives the request that produced it. */
    private val onOperationStart: () -> Unit = {},
) {

    private var location: Url = Url(collection.url)

    /** The DAV method of the last request, for §5's evidence. */
    var lastMethod: String? = null
        private set

    /** §6 step 1: the cheap check. Returns the Collection's CTag, or null when it has none. */
    suspend fun ctag(): String? {
        onOperationStart()
        val resource = newResource()
        lastMethod = "PROPFIND"

        var ctag: String? = null
        // Depth 0 addresses exactly the Collection, so its own response is the one that answers.
        resource.propfind(0, CalDAV.GetCTag).collect { item ->
            if (item is MultiStatusItem.Response && item.response.isSuccess())
                ctag = item.response[GetCTag::class.java]?.cTag
        }

        location = resource.location
        return ctag
    }

    /**
     * §6 step 2: the listing.
     *
     * The flow is collected to its end, which is what [Members.completed] records — a listing that
     * was cut short must never be mistaken for a complete one.
     */
    suspend fun members(): Members {
        val members = Members()
        onOperationStart()
        val resource = newResource()
        lastMethod = "PROPFIND"

        resource.propfind(1, WebDAV.GetETag, WebDAV.ResourceType).collect { item ->
            if (item is MultiStatusItem.Response) members.add(item)
        }

        location = resource.location
        members.markCompleted()
        return members
    }

    /**
     * §6: the `sync-collection` REPORT that collapses steps 1 and 2 where it works.
     *
     * The first attempt *is* the support probe: `<supported-report/>` is the negative answer, so
     * detection costs nothing beyond the request. Returns null when the server refused, and throws
     * for every other failure — those are the Collection's failure, not a licence to try again.
     */
    suspend fun reportChanges(syncToken: String?): Report? {
        onOperationStart()
        val members = Members()
        var token: String? = null
        val resource = newResource()
        lastMethod = "REPORT"

        try {
            // Depth "1" — the members of this Collection, not of nested ones.
            resource.reportChanges(syncToken, false, null, WebDAV.GetETag, WebDAV.ResourceType)
                .collect { item ->
                    when (item) {
                        is MultiStatusItem.ExtraProperty ->
                            (item.property as? SyncToken)?.let { token = it.token }
                        is MultiStatusItem.Response -> members.add(item)
                    }
                }
        } catch (e: DavException) {
            if (e.refusesSyncCollection()) return null
            throw e
        }

        location = resource.location
        members.markCompleted()
        // Without a token the REPORT enumerated every member, which is a full listing.
        return Report(members = members, token = token, fullListing = syncToken == null)
    }

    /**
     * §6 step 3: one batch of bodies, requested by href.
     *
     * The responses are keyed exactly as the listing keyed them, because both derive the key from the
     * href with the same rule; a body that arrives under an href we did not ask for is ignored rather
     * than invented a row for.
     */
    suspend fun multiget(hrefs: List<Url>): Map<String, String> {
        val bodies = LinkedHashMap<String, String>()
        onOperationStart()
        val resource = newResource()
        lastMethod = "REPORT"

        val flow = when (resource) {
            is DavAddressBook -> resource.multiget(hrefs)
            is DavCalendar -> resource.multiget(hrefs)
            else -> error("${collection.type} has no multiget")
        }
        flow.collect { item ->
            if (item !is MultiStatusItem.Response) return@collect

            val response = item.response
            if (!response.isSuccess()) return@collect

            val key = keyOf(response)
            if (key == null) return@collect

            bodyOf(response)?.let { bodies[key] = it }
        }

        location = resource.location
        return bodies
    }

    private fun newResource(): DavCollectionResource = when (collection.type) {
        CollectionType.ADDRESS_BOOK -> DavAddressBook(http, location)
        CollectionType.CALENDAR -> DavCalendar(http, location)
    }

    private fun bodyOf(response: DavResponse): String? = when (collection.type) {
        CollectionType.ADDRESS_BOOK -> response[AddressData::class.java]?.card
        CollectionType.CALENDAR -> response[CalendarData::class.java]?.iCalendar
    }
}

/** One `sync-collection` REPORT that ran to completion. */
internal class Report(
    val members: Members,
    /** The token to send next time, or null when the server issued none. */
    val token: String?,
    /** True when the request enumerated every member, because no sync token was sent. */
    val fullListing: Boolean,
)

/**
 * The members one request reported, and whether that request ran to completion.
 *
 * [completed] is the licence to delete: a listing cut short by an I/O error or a cancelled sync says
 * nothing about the members it never reached.
 */
internal class Members {

    /** href path -> member, in the order the server reported them. */
    val byKey = LinkedHashMap<String, RemoteItem>()

    /** Members the server explicitly reported as gone; `sync-collection` reports removals this way. */
    val removed = LinkedHashSet<String>()

    var completed = false
        private set

    fun markCompleted() {
        completed = true
    }

    fun add(item: MultiStatusItem.Response) {
        val response = item.response

        if (response.status?.value == REMOVED_STATUS) {
            keyOf(response)?.let { removed += it }
            return
        }
        if (!response.isSuccess()) return

        // The Collection itself, and anything outside it, is not a member.
        if (item.relation != DavResponse.HrefRelation.MEMBER) return

        // Nor is a nested Collection, which would otherwise be treated as an item of its parent.
        if (response[ResourceType::class.java]?.types?.contains(WebDAV.Collection) == true) return

        val key = keyOf(response) ?: return
        byKey[key] = RemoteItem(href = response.href.toString(), etag = response[GetETag::class.java]?.eTag)
    }
}

/**
 * The identity of a member, and the key every mapper sees: the href's path exactly as the server
 * returned it, with its percent-encoding, and without scheme, host or a normalisation of our own.
 *
 * The path is what stays stable when a server redirects, changes scheme or answers through a proxy,
 * and it is the one part of the href its own `<href>` element fully determines.
 *
 * Null for a href with no path of its own — the Collection root — which is never a member.
 */
private fun keyOf(response: DavResponse): String? =
    response.href.encodedPath.takeIf { it.isNotEmpty() && it != "/" }

/**
 * True when the server answered the REPORT with "I don't support that".
 *
 * The condition is the documented answer; a refused method is the same statement in a blunter form.
 * Some servers send the condition without an XML content type, in which case dav4jvm's exception
 * carries the body excerpt but no parsed error element.
 */
private fun DavException.refusesSyncCollection(): Boolean =
    errors.any { it.name == WebDAV.SupportedReport } ||
        responseExcerpt?.contains("supported-report") == true ||
        statusCode in UNSUPPORTED_REPORT_STATUS
