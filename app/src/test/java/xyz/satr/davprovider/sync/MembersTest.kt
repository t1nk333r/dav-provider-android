package xyz.satr.davprovider.sync

import at.bitfire.dav4jvm.Error
import at.bitfire.dav4jvm.Property
import at.bitfire.dav4jvm.ktor.MultiStatusItem
import at.bitfire.dav4jvm.ktor.Response
import at.bitfire.dav4jvm.property.webdav.WebDAV
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a listing has to earn before it may be believed.
 *
 * `completed` is the one flag that licenses deletion: the engine deletes every row the listing did
 * not name. A server that answers with only part of a Collection does so in a perfectly well-formed
 * multistatus, so the truncation marker is the only thing separating "these are the members" from
 * "these are some of the members" — and reading it wrongly in the permissive direction deletes the
 * remainder of the user's address book.
 */
class MembersTest {

    private val collection = Url("https://dav.example/books/main/")

    @Test
    fun `a listing the server did not cut short is completed`() {
        val members = Members()
        members.add(member("https://dav.example/books/main/a.vcf"))
        members.markCompleted()

        assertFalse(members.truncated)
        assertTrue(members.completed)
        assertEquals(setOf("/books/main/a.vcf"), members.byKey.keys)
    }

    @Test
    fun `a 507 in the multistatus withdraws the licence to delete`() {
        // The shape RFC 4918 gives it: the members the server did send, then a response of its own
        // saying the rest were held back.
        val members = Members()
        members.add(member("https://dav.example/books/main/a.vcf"))
        members.add(truncation())
        members.markCompleted()

        assertTrue(members.truncated)
        assertFalse(
            "a truncated listing must never be completed: the engine deletes what it does not name",
            members.completed,
        )
    }

    @Test
    fun `the members a truncated listing did send are still usable`() {
        // Truncation is not corruption. What arrived is real and worth writing; what is withheld is
        // the licence to conclude anything about the rest.
        val members = Members()
        members.add(member("https://dav.example/books/main/a.vcf"))
        members.add(member("https://dav.example/books/main/b.vcf"))
        members.add(truncation())

        assertEquals(listOf("/books/main/a.vcf", "/books/main/b.vcf"), members.byKey.keys.toList())
    }

    @Test
    fun `the condition counts even under a status that does not`() {
        // Some servers report the limit as a condition on an otherwise ordinary response rather
        // than as a 507, and the two mean the same thing.
        val members = Members()
        members.add(
            response(
                href = collection.toString(),
                status = HttpStatusCode.OK,
                errors = listOf(Error(Property.Name(WebDAV.NS_WEBDAV, "number-of-matches-within-limits"))),
            ),
        )
        members.markCompleted()

        assertTrue(members.truncated)
        assertFalse(members.completed)
    }

    @Test
    fun `clearing the truncation lets the next page answer for itself`() {
        // Paging: the run follows the token a truncated answer carries, and the page that stops
        // setting the flag is the one that finished the listing.
        val members = Members()
        members.add(truncation())
        members.clearTruncated()
        members.add(member("https://dav.example/books/main/a.vcf"))
        members.markCompleted()

        assertTrue(members.completed)
    }

    private fun member(href: String) =
        response(href, HttpStatusCode.OK, relation = Response.HrefRelation.MEMBER)

    private fun truncation() =
        response(collection.toString(), HttpStatusCode.InsufficientStorage)

    private fun response(
        href: String,
        status: HttpStatusCode,
        relation: Response.HrefRelation = Response.HrefRelation.OTHER,
        errors: List<Error>? = null,
    ) = MultiStatusItem.Response(
        Response(
            requestedUrl = collection,
            href = Url(href),
            status = status,
            propstat = emptyList(),
            error = errors,
            newLocation = null,
        ),
        relation,
    )
}
