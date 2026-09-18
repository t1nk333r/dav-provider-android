package xyz.satr.davprovider.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.satr.davprovider.core.CollectionType
import xyz.satr.davprovider.core.DavCollection
import xyz.satr.davprovider.core.ResponseEvidence
import xyz.satr.davprovider.error.SyncErrorClassifierImpl

/**
 * §8's discovery over canned bodies: the multistatus reader the enumeration needs, the id a
 * Collection keeps across runs, and the walk's own decisions — above all which outcome it may call
 * `completed`, because that is what decides whether a stored Collection is marked unavailable.
 *
 * Every response here goes through the shipped classifier, so a test that expects a credential
 * rejection or a web page to be named as such is exercising the same classification a sync would.
 * No server: the probe is the walk's own seam.
 */
class CollectionDiscoveryTest {

    private val classifier = SyncErrorClassifierImpl()

    private val base = "https://dav.invalid/dav/"
    private val principal = "https://dav.invalid/dav/user/"
    private val books = "https://dav.invalid/dav/user/books/"
    private val calendars = "https://dav.invalid/dav/user/cal/"

    // ------------------------------------------------------------ multistatus reading

    @Test
    fun `an enumeration keeps the address books and calendars and drops everything else`() {
        val body = multistatus(
            response("/dav/user/books/", collectionProperties("d:collection", displayName = "Books")),
            response("/dav/user/books/personal/", collectionProperties("c:addressbook", "Personal")),
            response("/dav/user/books/shared/", collectionProperties("c:addressbook")),
            response("/dav/user/cal/work/", collectionProperties("cal:calendar", "Work", "#1A2B3C")),
            // An item inside an address book, a plain folder, and a resource that claims to be both.
            response("/dav/user/books/personal/entry.vcf", ""),
            response("/dav/user/tmp/", "<d:resourcetype><d:collection/></d:resourcetype>"),
            response("/dav/user/both/", "<d:resourcetype><d:collection/><c:addressbook/><cal:calendar/></d:resourcetype>"),
        )

        val found = listing(body)

        assertEquals(
            listOf(
                "https://dav.invalid/dav/user/books/personal/",
                "https://dav.invalid/dav/user/books/shared/",
                "https://dav.invalid/dav/user/cal/work/",
            ),
            found.map { it.url },
        )
        assertEquals(
            listOf(CollectionType.ADDRESS_BOOK, CollectionType.ADDRESS_BOOK, CollectionType.CALENDAR),
            found.map { it.type },
        )
        assertEquals(listOf("Personal", null, "Work"), found.map { it.displayName })
        assertEquals(listOf<Int?>(null, null, 0xFF1A2B3C.toInt()), found.map { it.color })
    }

    @Test
    fun `an address book colour is read under its own name and opacity is dropped`() {
        val body = multistatus(
            response(
                "/dav/user/books/personal/",
                collectionProperties("c:addressbook", "Personal", "#AABBCCDD", colorName = "c:addressbook-color"),
            ),
            response("/dav/user/books/odd/", collectionProperties("c:addressbook", "Odd", "burnt orange")),
        )

        val found = listing(body)

        assertEquals(listOf<Int?>(0xFFAABBCC.toInt(), null), found.map { it.color })
    }

    @Test
    fun `a child href is resolved against the URL the request landed on`() {
        val body = multistatus(
            response("personal/", collectionProperties("c:addressbook")),
            response("/dav/user/cal/work/", collectionProperties("cal:calendar")),
        )

        val found = listing(body)

        assertEquals(
            listOf("https://dav.invalid/dav/user/books/personal/", "https://dav.invalid/dav/user/cal/work/"),
            found.map { it.url },
        )
    }

    @Test
    fun `a body that is not a multistatus is reported as unreadable, not as an empty answer`() {
        val webPage = "<!DOCTYPE html><html><body>Sign in to continue</body></html>"
        val davError = "<d:error xmlns:d=\"DAV:\"><d:valid-resourcetype/></d:error>"

        assertTrue(WebDavXml.collections(webPage, books) is ReadResult.Unreadable)
        assertTrue(WebDavXml.collections("", books) is ReadResult.Unreadable)
        assertTrue(WebDavXml.currentUserPrincipal(davError, base) is ReadResult.Unreadable)
    }

    @Test
    fun `a multistatus that describes no resource is not a listing`() {
        // A home set with nothing in it still lists itself, so a listing of zero resources is an
        // answer that went wrong — and an enumeration resting on it would mark stored Collections
        // unavailable.
        assertNull(read(WebDavXml.collections(multistatus(), books)))

        // A listing whose entries are all non-Collections is a listing, and reports none.
        val found = read(WebDavXml.collections(multistatus(response("/dav/user/books/", "<d:resourcetype><d:collection/></d:resourcetype>")), books))

        assertTrue(found!!.isEmpty())
    }

    @Test
    fun `the principal is the href inside current-user-principal, not the response's own href`() {
        val body = multistatus(
            response(
                "/dav/user/",
                "<d:resourcetype><d:collection/></d:resourcetype>" +
                    "<d:current-user-principal><d:href>/dav/user/principals/me/</d:href></d:current-user-principal>",
            ),
        )

        assertEquals(
            "https://dav.invalid/dav/user/principals/me/",
            read(WebDavXml.currentUserPrincipal(body, base)),
        )
    }

    @Test
    fun `a principal that names no user is nothing to follow`() {
        val body = multistatus(
            response("/dav/", "<d:current-user-principal><d:unauthenticated/></d:current-user-principal>"),
        )

        assertEquals(null, read(WebDavXml.currentUserPrincipal(body, base)))
    }

    @Test
    fun `both home sets are read from one answer, and a server may publish neither`() {
        val both = multistatus(
            response(
                "/dav/user/",
                "<c:addressbook-home-set><d:href>books/</d:href></c:addressbook-home-set>" +
                    "<cal:calendar-home-set><d:href>/dav/user/cal/</d:href></cal:calendar-home-set>",
            ),
        )
        val neither = multistatus(response("/dav/user/", "<d:resourcetype><d:collection/></d:resourcetype>"))

        assertEquals(
            HomeSets("https://dav.invalid/dav/user/books/", "https://dav.invalid/dav/user/cal/"),
            read(WebDavXml.homeSets(both, principal)),
        )
        assertEquals(HomeSets(null, null), read(WebDavXml.homeSets(neither, principal)))
    }

    // ------------------------------------------------------------ Collection ids

    @Test
    fun `a Collection keeps one id across runs, and a trailing slash is not a different Collection`() {
        val id = CollectionDiscovery.collectionId("https://dav.invalid/dav/user/cal/work/")

        assertEquals(id, CollectionDiscovery.collectionId("https://dav.invalid/dav/user/cal/work"))
        assertNotEquals(id, CollectionDiscovery.collectionId("https://dav.invalid/dav/user/cal/home/"))
        // Percent-encoding is part of the path a server named, so it is part of the id.
        assertNotEquals(
            CollectionDiscovery.collectionId("https://dav.invalid/dav/user/My%20Cal/"),
            CollectionDiscovery.collectionId("https://dav.invalid/dav/user/My+Cal/"),
        )
    }

    @Test
    fun `the id follows the URL, not the display name the server reported`() {
        val first = onlyCollection(collectionAtBaseUrl("Work"))
        val renamed = onlyCollection(collectionAtBaseUrl("Renamed on the server"))

        assertEquals(first.displayName, "Work")
        assertEquals("Renamed on the server", renamed.displayName)
        assertEquals(first.id, renamed.id)
        assertEquals(base, first.url)
    }

    // ------------------------------------------------------------ the walk

    @Test
    fun `the walk reads the principal from the well-known path and enumerates both home sets`() {
        val probe = probe { url ->
            when (url) {
                "https://dav.invalid/.well-known/carddav" -> answered(
                    url = base,
                    body = multistatus(response("/dav/user/", principalProperty())),
                    hops = listOf(RedirectHop(302, base)),
                )

                // A server may publish only one of the two well-known paths; the other is still
                // asked, and its 404 is part of the record.
                "https://dav.invalid/.well-known/caldav" -> answered(url, body = null, status = 404)
                principal -> answered(principal, multistatus(response("/dav/user/", homeSetProperties())))
                books -> answered(books, multistatus(response("/dav/user/books/personal/", collectionProperties("c:addressbook", "Personal"))))
                calendars -> answered(calendars, multistatus(response("/dav/user/cal/work/", collectionProperties("cal:calendar", "Work"))))
                else -> error("unexpected request: $url")
            }
        }

        val outcome = CollectionDiscovery.walk(base, probe)

        assertTrue(outcome.completed)
        assertEquals(
            listOf(CollectionType.ADDRESS_BOOK, CollectionType.CALENDAR),
            outcome.collections.map { it.type },
        )
        assertEquals(
            listOf("https://dav.invalid/dav/user/books/personal/", "https://dav.invalid/dav/user/cal/work/"),
            outcome.collections.map { it.url },
        )
        // §8: a Collection that has just appeared does not start writing into the phone.
        assertTrue(outcome.collections.none { it.selected })
        assertTrue(outcome.collections.all { it.available })
        assertEquals(
            listOf(
                "/.well-known/carddav (Depth 0)",
                "/.well-known/caldav (Depth 0)",
                "principal (Depth 0)",
                "address book home (Depth 1)",
                "calendar home (Depth 1)",
            ),
            probe.requested,
        )
        // The redirect the well-known path answered with is part of the record, not lost in it.
        assertTrue(outcome.notes.first().contains("302 → $base"))
        assertTrue(outcome.notes.first().contains("current-user-principal $principal"))
    }

    @Test
    fun `the walk falls back to the base URL as the principal when no well-known path answers`() {
        val probe = probe { url ->
            when (url) {
                "https://dav.invalid/.well-known/carddav", "https://dav.invalid/.well-known/caldav" ->
                    answered(url, body = null, status = 404)

                base -> answered(base, multistatus(response("/dav/user/", principalProperty())))
                principal -> answered(principal, multistatus(response("/dav/user/", homeSetProperties())))
                books -> answered(books, multistatus(response("/dav/user/books/personal/", collectionProperties("c:addressbook"))))
                calendars -> answered(calendars, multistatus(response("/dav/user/cal/work/", collectionProperties("cal:calendar"))))
                else -> error("unexpected request: $url")
            }
        }

        val outcome = CollectionDiscovery.walk(base, probe)

        assertTrue(outcome.completed)
        assertEquals(2, outcome.collections.size)
        assertEquals(
            listOf(
                "/.well-known/carddav (Depth 0)",
                "/.well-known/caldav (Depth 0)",
                "base URL as principal (Depth 0)",
                "principal (Depth 0)",
                "address book home (Depth 1)",
                "calendar home (Depth 1)",
            ),
            probe.requested,
        )
        assertTrue(outcome.notes[0].contains("404, That path doesn't exist on the server"))
    }

    @Test
    fun `CardDAV and CalDAV published at two different roots are both discovered`() {
        val contactsPrincipal = "https://dav.invalid/contacts/me/"
        val calendarPrincipal = "https://dav.invalid/calendar/me/"
        val probe = probe { url ->
            when (url) {
                "https://dav.invalid/.well-known/carddav" -> answered(
                    contactsPrincipal,
                    multistatus(response("/contacts/me/", principalProperty("/contacts/me/"))),
                    hops = listOf(RedirectHop(302, contactsPrincipal)),
                )

                "https://dav.invalid/.well-known/caldav" -> answered(
                    calendarPrincipal,
                    multistatus(response("/calendar/me/", principalProperty("/calendar/me/"))),
                    hops = listOf(RedirectHop(302, calendarPrincipal)),
                )

                // Each principal publishes only its own protocol's home set, which is why both
                // well-known paths are read before any home set is.
                contactsPrincipal -> answered(
                    contactsPrincipal,
                    multistatus(
                        response(
                            "/contacts/me/",
                            "<c:addressbook-home-set><d:href>/contacts/me/books/</d:href></c:addressbook-home-set>",
                        ),
                    ),
                )

                calendarPrincipal -> answered(
                    calendarPrincipal,
                    multistatus(
                        response(
                            "/calendar/me/",
                            "<cal:calendar-home-set><d:href>/calendar/me/cals/</d:href></cal:calendar-home-set>",
                        ),
                    ),
                )

                "https://dav.invalid/contacts/me/books/" -> answered(
                    url,
                    multistatus(response("/contacts/me/books/personal/", collectionProperties("c:addressbook", "Personal"))),
                )

                "https://dav.invalid/calendar/me/cals/" -> answered(
                    url,
                    multistatus(response("/calendar/me/cals/work/", collectionProperties("cal:calendar", "Work"))),
                )

                else -> error("unexpected request: $url")
            }
        }

        val outcome = CollectionDiscovery.walk(base, probe)

        assertTrue(outcome.completed)
        assertEquals(
            listOf(CollectionType.ADDRESS_BOOK, CollectionType.CALENDAR),
            outcome.collections.map { it.type },
        )
        assertEquals(
            listOf("https://dav.invalid/contacts/me/books/personal/", "https://dav.invalid/calendar/me/cals/work/"),
            outcome.collections.map { it.url },
        )
        assertEquals(6, probe.requested.size)
    }

    @Test
    fun `an enumeration that fails leaves the outcome incomplete and yields no Collections`() {
        val probe = probe { url ->
            when (url) {
                "https://dav.invalid/.well-known/carddav" ->
                    answered(url, multistatus(response("/dav/user/", principalProperty())))

                "https://dav.invalid/.well-known/caldav" -> answered(url, body = null, status = 404)
                principal -> answered(principal, multistatus(response("/dav/user/", homeSetProperties())))
                books -> answered(books, multistatus(response("/dav/user/books/personal/", collectionProperties("c:addressbook"))))
                calendars -> answered(calendars, body = null, status = 500)
                else -> error("unexpected request: $url")
            }
        }

        val outcome = CollectionDiscovery.walk(base, probe)

        assertFalse(outcome.completed)
        // Not even the address book that was listed: a partial enumeration may never be merged, or
        // the half that failed would look like a set of Collections that had disappeared.
        assertTrue(outcome.collections.isEmpty())
        assertTrue(outcome.notes.any { it.contains("The server is having trouble") })
        assertEquals(5, probe.requested.size)
    }

    @Test
    fun `a home set that lists no resource at all leaves the outcome incomplete`() {
        val outcome = CollectionDiscovery.walk(base, probe { url ->
            when (url) {
                "https://dav.invalid/.well-known/carddav" ->
                    answered(url, multistatus(response("/dav/user/", principalProperty())))

                "https://dav.invalid/.well-known/caldav" -> answered(url, body = null, status = 404)
                principal -> answered(principal, multistatus(response("/dav/user/", homeSetProperties())))
                books -> answered(books, multistatus())
                calendars -> answered(calendars, multistatus(response("/dav/user/cal/work/", collectionProperties("cal:calendar"))))
                else -> error("unexpected request: $url")
            }
        })

        assertFalse(outcome.completed)
        assertTrue(outcome.collections.isEmpty())
        assertTrue(outcome.notes.any { it.contains("the server reported no address book or calendar") })
    }

    @Test
    fun `a redirect into the proxy's login page is reported as its class and is no Collection`() {
        val login = "https://dav.invalid/cdn-cgi/access/login?redirect_url=%2Fdav%2F"
        val probe = probe { url -> answered(url, body = null, status = 302, location = login) }

        val outcome = CollectionDiscovery.walk(base, probe)

        assertFalse(outcome.completed)
        assertTrue(outcome.collections.isEmpty())
        assertTrue(outcome.notes.all { it.contains("Access denied by the proxy — the token is wrong or expired") })
        // Where the proxy wants to send the request is part of the record: it names the proxy as the
        // thing that refused, and no request ever goes there.
        assertTrue(outcome.notes.first().contains("302 to $login"))
    }

    @Test
    fun `a web page at the base URL is reported for what it is, never as a Collection`() {
        val probe = probe { url ->
            when (url) {
                "https://dav.invalid/.well-known/carddav", "https://dav.invalid/.well-known/caldav",
                base,
                -> answered(
                    url,
                    body = "<!DOCTYPE html><html><body>Sign in</body></html>",
                    contentType = "text/html",
                )

                else -> error("unexpected request: $url")
            }
        }

        val outcome = CollectionDiscovery.walk(base, probe)

        assertFalse(outcome.completed)
        assertTrue(outcome.collections.isEmpty())
        assertTrue(outcome.notes.any { it.contains("Something between the app and the server changed the response") })
    }

    @Test
    fun `a base URL that is itself a calendar yields exactly one Collection`() {
        val outcome = CollectionDiscovery.walk(base, probe { url ->
            if (url == base) {
                answered(url, multistatus(response("/dav/user/overview/", collectionProperties("cal:calendar", "Overview"))))
            } else {
                answered(url, body = null, status = 404)
            }
        })

        assertTrue(outcome.completed)
        assertEquals(1, outcome.collections.size)
        assertEquals(CollectionType.CALENDAR, outcome.collections.single().type)
        assertEquals("https://dav.invalid/dav/user/overview/", outcome.collections.single().url)
        assertFalse(outcome.collections.single().selected)
    }

    @Test
    fun `a Collection the server left unnamed is shown by its URL's last segment`() {
        val outcome = CollectionDiscovery.walk(base, probe { url ->
            if (url == base) {
                answered(url, multistatus(response("/dav/user/overview/", collectionProperties("cal:calendar"))))
            } else {
                answered(url, body = null, status = 404)
            }
        })

        val collection = onlyCollection(outcome)

        assertNull(collection.displayName)
        assertEquals("overview", collectionTitle(collection))
    }

    @Test
    fun `a base URL that is not a Collection ends the walk without one`() {
        val outcome = CollectionDiscovery.walk(base, probe { url ->
            when (url) {
                "https://dav.invalid/.well-known/carddav", "https://dav.invalid/.well-known/caldav" ->
                    answered(url, body = null, status = 404)

                else -> answered(url, multistatus(response("/dav/", collectionProperties("d:collection"))))
            }
        })

        assertFalse(outcome.completed)
        assertTrue(outcome.collections.isEmpty())
        assertTrue(outcome.notes.any { it.contains("no address book or calendar") })
    }

    // ------------------------------------------------------------ fixtures

    private fun onlyCollection(outcome: DiscoveryOutcome): DavCollection {
        assertTrue("expected a completed walk", outcome.completed)
        return outcome.collections.single()
    }

    /** A server whose base URL is itself a calendar, enumerated through §8's last step. */
    private fun collectionAtBaseUrl(displayName: String): DiscoveryOutcome =
        CollectionDiscovery.walk(base, probe { url ->
            if (url == base) {
                answered(url, multistatus(response("/dav/", collectionProperties("cal:calendar", displayName))))
            } else {
                answered(url, body = null, status = 404)
            }
        })

    private class RecordingProbe(private val answer: (String) -> DavAttempt) : DiscoveryProbe {
        val requested = ArrayList<String>()

        override fun propfind(url: String, depth: Int, body: String, label: String): DavAttempt {
            requested += "$label (Depth $depth)"
            return answer(url)
        }
    }

    private fun probe(answer: (String) -> DavAttempt) = RecordingProbe(answer)

    /** A response body the classifier reads, so the class a step is reported under is the shipped one. */
    private fun answered(
        url: String,
        body: String?,
        status: Int = 207,
        contentType: String? = "application/xml; charset=utf-8",
        location: String? = null,
        hops: List<RedirectHop> = emptyList(),
    ): DavAttempt {
        val evidence = ResponseEvidence(
            httpStatus = status,
            locationHeader = location,
            wwwAuthenticate = null,
            contentType = contentType,
            body = body,
            requestMethod = DavProbe.METHOD,
            certificateOffered = false,
        )
        return DavAttempt(url, hops, ProbeOutcome("discovery", classifier.classify(evidence), evidence))
    }

    private fun <T> read(result: ReadResult<T>): T = when (result) {
        is ReadResult.Read -> result.value
        ReadResult.Unreadable -> throw AssertionError("expected a readable multistatus")
    }

    /** The Collections of one listing; fails when the server described no resource at all. */
    private fun listing(body: String?): List<DiscoveredCollection> {
        val found = read(WebDavXml.collections(body, books))
        return found ?: throw AssertionError("expected a listing")
    }

    private fun multistatus(vararg responses: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:carddav\"" +
            " xmlns:cal=\"urn:ietf:params:xml:ns:caldav\" xmlns:i=\"http://apple.com/ns/ical/\">" +
            responses.joinToString("") +
            "</d:multistatus>"

    /** One `<response>` of a multistatus, whose `propstat` carries [properties] verbatim. */
    private fun response(href: String, properties: String): String =
        "<d:response><d:href>$href</d:href><d:propstat><d:prop>$properties</d:prop>" +
            "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"

    private fun collectionProperties(
        type: String,
        displayName: String? = null,
        color: String? = null,
        colorName: String = "i:calendar-color",
    ): String = "<d:resourcetype><d:collection/><$type/></d:resourcetype>" +
        (displayName?.let { "<d:displayname>$it</d:displayname>" } ?: "") +
        (color?.let { "<$colorName>$it</$colorName>" } ?: "")

    private fun principalProperty(href: String = "/dav/user/"): String =
        "<d:current-user-principal><d:href>$href</d:href></d:current-user-principal>"

    private fun homeSetProperties(): String =
        "<c:addressbook-home-set><d:href>/dav/user/books/</d:href></c:addressbook-home-set>" +
            "<cal:calendar-home-set><d:href>/dav/user/cal/</d:href></cal:calendar-home-set>"
}
