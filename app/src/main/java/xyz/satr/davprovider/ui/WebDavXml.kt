package xyz.satr.davprovider.ui

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXParseException
import xyz.satr.davprovider.core.CollectionType

/** What a `PROPFIND Depth: 0` on one URL says about it. */
internal data class CollectionDescription(val type: CollectionType?, val displayName: String?)

/**
 * The home sets a principal reported. The two are independent: a server may publish only one, and
 * one that publishes neither has nothing for the enumeration step to list.
 */
internal data class HomeSets(val addressBook: String?, val calendar: String?)

/** One child of a home set, as the server described it. [url] is absolute. */
internal data class DiscoveredCollection(
    val url: String,
    val type: CollectionType,
    val displayName: String?,
    val color: Int?,
)

/**
 * A body that was read, or the fact that it could not be — §5 keeps "the server reported nothing"
 * apart from "the response could not be understood", and so does discovery's attempt log.
 */
internal sealed interface ReadResult<out T> {
    /** The body parsed. [value] is what it said, which may be nothing at all. */
    data class Read<T>(val value: T) : ReadResult<T>

    /** A response arrived and its body is not a multistatus this reader can use: class 11. */
    data object Unreadable : ReadResult<Nothing>
}

/**
 * The minimum of a WebDAV multistatus this app reads for itself: a resource's `resourcetype`, its
 * `displayname`, its colour, and the `href`s that carry a principal or a home set.
 *
 * The sync engine does its own parsing with dav4jvm; this reader exists because the setup screen has
 * to answer one question about a single URL without an enumeration, and because §8's walk has to
 * report what each step was answered with. An honest "not a Collection" beats guessing a type from a
 * URL, and an unreadable body is reported as unreadable rather than as an empty answer.
 *
 * Namespace prefixes are deliberately not resolved: the DAV tags are matched by local name, because
 * a server that gets a prefix or a declaration subtly wrong still describes its Collections
 * correctly, and refusing its answer over the prefix would lose a working server.
 */
internal object WebDavXml {

    private const val CURRENT_USER_PRINCIPAL = "current-user-principal"
    private const val ADDRESS_BOOK_HOME_SET = "addressbook-home-set"
    private const val CALENDAR_HOME_SET = "calendar-home-set"
    private const val HREF = "href"
    private const val MULTISTATUS = "multistatus"
    private const val RESPONSE = "response"
    private const val RESOURCETYPE = "resourcetype"

    /**
     * No standard property carries an address book's colour, and servers disagree about the
     * namespace for both, so the local name is what is matched: whichever of the two the server
     * answered with is the one that is read.
     */
    private val COLOR_PROPERTIES = setOf("calendar-color", "addressbook-color")

    fun collectionDescription(body: String?): CollectionDescription {
        val root = parse(body)?.takeIf { isDavRoot(it) } ?: return CollectionDescription(null, null)
        val response = responses(root).firstOrNull() ?: return CollectionDescription(null, null)
        val properties = propertiesOf(response)
        return CollectionDescription(properties.type, properties.displayName)
    }

    /** The principal a `current-user-principal` answer names, absolute, or null when it names none. */
    fun currentUserPrincipal(body: String?, baseUrl: String): ReadResult<String?> =
        withRoot(body) { root -> hrefOf(root, CURRENT_USER_PRINCIPAL, baseUrl) }

    fun homeSets(body: String?, baseUrl: String): ReadResult<HomeSets> = withRoot(body) { root ->
        HomeSets(
            addressBook = hrefOf(root, ADDRESS_BOOK_HOME_SET, baseUrl),
            calendar = hrefOf(root, CALENDAR_HOME_SET, baseUrl),
        )
    }

    /**
     * The children of a home set that are address books or calendars, in the order the server listed
     * them.
     *
     * - [ReadResult.Unreadable] — the body is not a multistatus at all;
     * - `Read(null)` — a multistatus that describes no resource, which is not a listing: a home set
     *   with nothing under it still lists itself. Treating it as an enumeration that ran to the end
     *   would let a mangled or ACL-restricted answer mark every stored Collection unavailable;
     * - `Read(list)` — a listing, whose list may be empty because the home set really has no
     *   Collections in it, or because none of them is one this app syncs.
     */
    fun collections(body: String?, baseUrl: String): ReadResult<List<DiscoveredCollection>?> =
        withRoot(body) { root ->
            val listed = responses(root)
            if (listed.isEmpty()) return@withRoot null
            listed.mapNotNull { response ->
                val properties = propertiesOf(response)
                val type = properties.type ?: return@mapNotNull null
                val href = properties.href ?: return@mapNotNull null
                val url = resolveUrl(baseUrl, href) ?: return@mapNotNull null
                DiscoveredCollection(url, type, properties.displayName, properties.color)
            }
        }

    private inline fun <T> withRoot(body: String?, read: (Element) -> T): ReadResult<T> {
        val root = parse(body) ?: return ReadResult.Unreadable
        if (!isDavRoot(root)) return ReadResult.Unreadable
        return ReadResult.Read(read(root))
    }

    /**
     * A DAV answer is a multistatus, so any other document — a proxy's login page, a server's own
     * error document — is unreadable rather than an answer that mentioned nothing.
     */
    private fun isDavRoot(root: Element): Boolean {
        val name = localName(root)
        return name == MULTISTATUS || name == RESPONSE
    }

    /**
     * Server-supplied XML, so entities and doctypes are refused the way the error classifier refuses
     * them: not every parser feature exists on every platform, and a body that will not parse must
     * come back as this reader's own "did not parse" rather than as a crash on a background thread.
     */
    private fun parse(body: String?): Element? {
        if (body.isNullOrBlank()) return null
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { isExpandEntityReferences = false }
                runCatching { isXIncludeAware = false }
            }
            val builder = factory.newDocumentBuilder().apply {
                setErrorHandler(object : ErrorHandler {
                    override fun warning(exception: SAXParseException) = Unit
                    override fun error(exception: SAXParseException) = Unit
                    override fun fatalError(exception: SAXParseException): Unit = throw exception
                })
            }
            builder.parse(InputSource(StringReader(body))).documentElement
        } catch (e: Exception) {
            null
        }
    }

    /** A body that is one `<response>` rather than a multistatus is read as the one response it is. */
    private fun responses(root: Element): List<Element> {
        if (localName(root) == RESPONSE) return listOf(root)
        val found = ArrayList<Element>(4)
        var child = root.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE && localName(child as Element) == RESPONSE) {
                found += child
            }
            child = child.nextSibling
        }
        return found
    }

    private class DavProperties {
        var href: String? = null
        var displayName: String? = null
        var color: Int? = null
        var addressBook = false
        var calendar = false

        /** A resource that claims to be both is not one Collection, so it is not offered as one. */
        val type: CollectionType?
            get() = when {
                addressBook && !calendar -> CollectionType.ADDRESS_BOOK
                calendar && !addressBook -> CollectionType.CALENDAR
                else -> null
            }
    }

    private fun propertiesOf(response: Element): DavProperties {
        val properties = DavProperties()
        var child = response.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE && localName(child as Element) == HREF) {
                // The response's own href is the only one at this level; the hrefs inside a property
                // belong to that property, which is why the search below is confined to one element.
                properties.href = text(child)
            }
            child = child.nextSibling
        }
        child = response.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) read(child as Element, properties)
            child = child.nextSibling
        }
        return properties
    }

    /**
     * Depth-first, because a resource's properties sit inside its `propstat`s — and a property the
     * server does not implement comes back empty in a `404` propstat, which reads the same whether
     * or not the propstat's own status is consulted.
     */
    private fun read(element: Element, into: DavProperties) {
        val name = localName(element)
        when {
            name == RESOURCETYPE -> {
                var child = element.firstChild
                while (child != null) {
                    if (child.nodeType == Node.ELEMENT_NODE) when (localName(child as Element)) {
                        "addressbook" -> into.addressBook = true
                        "calendar" -> into.calendar = true
                    }
                    child = child.nextSibling
                }
                return
            }

            name == "displayname" -> {
                if (into.displayName == null) into.displayName = text(element)
                return
            }

            name in COLOR_PROPERTIES -> {
                if (into.color == null) into.color = color(text(element))
                return
            }
        }
        var child = element.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) read(child as Element, into)
            child = child.nextSibling
        }
    }

    /** The `href` inside the first element of [name], resolved against the URL [baseUrl]. */
    private fun hrefOf(root: Element, name: String, baseUrl: String): String? {
        val container = firstDescendant(root, name) ?: return null
        val href = firstDescendant(container, HREF)?.let { text(it) } ?: return null
        return resolveUrl(baseUrl, href)
    }

    private fun firstDescendant(root: Element, name: String): Element? {
        var child = root.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                val element = child as Element
                if (localName(element) == name) return element
                firstDescendant(element, name)?.let { return it }
            }
            child = child.nextSibling
        }
        return null
    }

    /**
     * The colour properties are Apple's `#RRGGBB` and `#RRGGBBAA`; anything else is reported as no
     * colour rather than guessed at. Appended opacity is dropped: the value is drawn behind a
     * Collection's name, and a translucent background is not what a server means by it.
     */
    private fun color(value: String?): Int? {
        val digits = value?.trim()?.removePrefix("#") ?: return null
        val rgb = when (digits.length) {
            6 -> digits
            8 -> digits.substring(0, 6)
            3 -> buildString(6) { digits.forEach { append(it).append(it) } }
            else -> return null
        }
        val parsed = rgb.toLongOrNull(radix = 16) ?: return null
        return parsed.toInt() or 0xFF000000.toInt()
    }

    /** The text of an element, trimmed; null when it is empty, so an empty answer stays empty. */
    private fun text(element: Element): String? =
        element.textContent?.trim()?.takeIf { it.isNotEmpty() }

    /** Namespace prefixes are stripped: the tags are only meaningful by local name here. */
    private fun localName(element: Element): String =
        (element.localName ?: element.tagName)?.substringAfterLast(':').orEmpty()
}
