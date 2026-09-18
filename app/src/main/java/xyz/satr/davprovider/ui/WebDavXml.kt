package xyz.satr.davprovider.ui

import android.util.Xml
import java.io.StringReader
import org.xmlpull.v1.XmlPullParser
import xyz.satr.davprovider.core.CollectionType

/** What a `PROPFIND Depth: 0` on one URL says about it. */
internal data class CollectionDescription(val type: CollectionType?, val displayName: String?)

/**
 * The minimum of a WebDAV multistatus the account UI needs to turn a pasted Collection URL into a
 * Collection: its `resourcetype` and its `displayname`.
 *
 * The sync engine does its own parsing with dav4jvm; this reader exists because the setup screen
 * has to answer one question about a single URL without an enumeration, and an honest "not a
 * Collection" beats guessing a type from a URL.
 */
internal object WebDavXml {

    fun collectionDescription(body: String?): CollectionDescription {
        if (body.isNullOrBlank()) return CollectionDescription(null, null)
        var inResourceType = false
        var inDisplayName = false
        var addressBook = false
        var calendar = false
        var displayName: String? = null
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(body))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (localName(parser.name)) {
                        "resourcetype" -> inResourceType = true
                        "displayname" -> inDisplayName = true
                        "addressbook" -> if (inResourceType) addressBook = true
                        "calendar" -> if (inResourceType) calendar = true
                    }

                    XmlPullParser.TEXT -> if (inDisplayName && displayName == null) {
                        displayName = parser.text?.trim()?.takeIf { it.isNotEmpty() }
                    }

                    XmlPullParser.END_TAG -> when (localName(parser.name)) {
                        "resourcetype" -> inResourceType = false
                        "displayname" -> inDisplayName = false
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            // A body that does not parse is not a Collection; the caller reports what it got.
            return CollectionDescription(null, null)
        }
        val type = when {
            addressBook && !calendar -> CollectionType.ADDRESS_BOOK
            calendar && !addressBook -> CollectionType.CALENDAR
            else -> null
        }
        return CollectionDescription(type, displayName)
    }

    /** Namespace prefixes are stripped: the type tags are only meaningful by local name here. */
    private fun localName(tag: String?): String = tag?.substringAfterLast(':').orEmpty()
}
