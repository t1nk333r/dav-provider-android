package xyz.satr.davprovider.sync

import io.ktor.http.Url
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.satr.davprovider.core.ChangeKind
import xyz.satr.davprovider.core.LocalChange

/**
 * What step U decides before it sends anything.
 *
 * Three decisions, each of which no run can take back afterwards: the order the requests go in, the
 * key an accepted create is stored under, and what the server's answer means. The rest of the step
 * is a provider and a socket and is checked on a device; these three are decidable on the JVM, and
 * each has a failure mode that either duplicates an item or loses one.
 */
class UploadStepTest {

    private fun change(kind: ChangeKind, rowId: Long, key: String? = null) =
        LocalChange(rowId = rowId, kind = kind, key = key)

    @Test
    fun `deletions go first, then creates, then updates`() {
        // A deletion frees the name a create might take, and an update may refer to something a
        // create has just made. The order within each kind is the mapper's own, which is what makes
        // two runs of an unchanged Collection send the same sequence.
        val changes = listOf(
            change(ChangeKind.UPDATE, rowId = 1, key = "/books/main/one.vcf"),
            change(ChangeKind.CREATE, rowId = 2),
            change(ChangeKind.DELETE, rowId = 3, key = "/books/main/three.vcf"),
            change(ChangeKind.CREATE, rowId = 4),
            change(ChangeKind.UPDATE, rowId = 5, key = "/books/main/five.vcf"),
            change(ChangeKind.DELETE, rowId = 6, key = "/books/main/six.vcf"),
        )

        assertEquals(listOf(3L, 6L, 2L, 4L, 1L, 5L), uploadOrder(changes).map { it.rowId })
    }

    @Test
    fun `an accepted create is keyed by the path the server named`() {
        assertEquals(
            "/books/main/renamed.vcf",
            adoptKey(
                requestPath = "/books/main/minted.vcf",
                location = Url("https://dav.example/books/main/renamed.vcf"),
                collectionUrl = Url("https://dav.example/books/main/"),
            ),
        )
    }

    @Test
    fun `a create the server named nowhere keeps the path it was sent to`() {
        assertEquals(
            "/books/main/minted.vcf",
            adoptKey(
                requestPath = "/books/main/minted.vcf",
                location = null,
                collectionUrl = Url("https://dav.example/books/main/"),
            ),
        )
    }

    @Test
    fun `a Location outside the Collection is refused rather than adopted`() {
        // No listing of this Collection will ever name such a path, so a row keyed by it is a row
        // step 6 deletes on the next run, and the item the server just stored comes back as a
        // duplicate the run after that. Null says "keep the request path and stay dirty".
        val main = Url("https://dav.example/books/main/")
        val request = "/books/main/minted.vcf"

        assertNull(adoptKey(request, Url("https://elsewhere.example/books/main/x.vcf"), main))
        assertNull(adoptKey(request, Url("https://dav.example/books/other/x.vcf"), main))
        assertNull(adoptKey(request, main, main))
        assertNull(adoptKey(request, Url("https://dav.example/books/main"), main))
    }

    @Test
    fun `a Collection URL with no trailing slash still has members`() {
        // What the settings screen stores when the user pastes a URL. Reading it as a prefix without
        // a separator would refuse every create against such a Collection.
        assertEquals(
            "/books/main/x.vcf",
            adoptKey(
                requestPath = "/books/main/x.vcf",
                location = Url("https://dav.example/books/main/x.vcf"),
                collectionUrl = Url("https://dav.example/books/main"),
            ),
        )
    }

    @Test
    fun `a 2xx PUT is one to record`() {
        assertEquals(
            ChangeAction.MarkUploaded("\"7\"", Url("https://dav.example/books/main/x.vcf")),
            answerOf(PutAnswer.Stored(etag = "\"7\"", location = Url("https://dav.example/books/main/x.vcf"))),
        )
    }

    @Test
    fun `a 2xx DELETE and a 404 are the same answer`() {
        // The server does not have it, which is what the delete wanted: the tombstone is dropped and
        // the Collection is not failed over a row the server and the phone already agree about.
        assertEquals(ChangeAction.Purge, answerOf(DeleteAnswer.Gone))
    }

    @Test
    fun `a 412 reverts whichever verb it answered`() {
        // Server wins for both, and a create is not retried under a fresh name: the one realistic
        // 412 on a create is this app's own answer going missing, and a second name would duplicate
        // the item the server already stored.
        assertEquals(ChangeAction.Revert, answerOf(PutAnswer.PreconditionFailed))
        assertEquals(ChangeAction.Revert, answerOf(DeleteAnswer.PreconditionFailed))
    }

    @Test
    fun `a read-only Collection reverts edits but never a create`() {
        // Found on a device: a contact saved through "Save contact to" was made in the editor,
        // refused because the Collection was read-only, and had its DIRTY flag cleared with it.
        // There is no server copy of a create for the run's fetch to put back, so clearing the flag
        // does not revert the contact — it strands it, and no later run looks at it again.
        val changes = listOf(
            LocalChange(rowId = 1, kind = ChangeKind.DELETE, key = "/books/main/gone.vcf"),
            LocalChange(rowId = 2, kind = ChangeKind.CREATE),
            LocalChange(rowId = 3, kind = ChangeKind.UPDATE, key = "/books/main/edited.vcf"),
        )

        val revertible = revertibleOnRefusal(changes)

        assertEquals(listOf(1L, 3L), revertible.map { it.rowId })
    }

    @Test
    fun `a refusal of nothing but creates reverts nothing`() {
        val creates = listOf(LocalChange(rowId = 7, kind = ChangeKind.CREATE))

        assertTrue("a create stays dirty so a writable Collection can still take it",
            revertibleOnRefusal(creates).isEmpty())
    }
}
