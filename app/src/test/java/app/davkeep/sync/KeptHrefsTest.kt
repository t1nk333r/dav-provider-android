package app.davkeep.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import app.davkeep.core.RemoteItem

/**
 * What step 6 is allowed to delete.
 *
 * This is the one decision in a sync whose mistake destroys rows rather than traffic, so the rule is
 * exercised on its own rather than through a run: [keptHrefs] is what stands between a delta listing
 * and the rows this same run just wrote.
 */
class KeptHrefsTest {

    private fun members(
        present: List<String> = emptyList(),
        removed: List<String> = emptyList(),
    ) = Members().apply {
        present.forEach { byKey[it] = RemoteItem(href = "https://dav.invalid$it", etag = "\"1\"") }
        this.removed += removed
        markCompleted()
    }

    private val local = setOf("/dav/user/cal/old-a.ics", "/dav/user/cal/old-b.ics")

    @Test
    fun `a delta keeps the members it just fetched, not only the rows it started with`() {
        // The shape that deleted its own additions: the listing removes one member and adds another,
        // while a row for a third stays put. `local` predates the write of the added member.
        val kept = keptHrefs(
            fullListing = false,
            local = local,
            members = members(
                present = listOf("/dav/user/cal/new.ics"),
                removed = listOf("/dav/user/cal/old-a.ics"),
            ),
        )

        assertEquals(
            setOf("/dav/user/cal/old-b.ics", "/dav/user/cal/new.ics"),
            kept,
        )
    }

    @Test
    fun `a delta deletes exactly what the server reported removed`() {
        val kept = keptHrefs(
            fullListing = false,
            local = setOf("/dav/user/cal/gone.ics", "/dav/user/cal/here.ics"),
            members = members(removed = listOf("/dav/user/cal/gone.ics")),
        )

        assertEquals(setOf("/dav/user/cal/here.ics"), kept)
    }

    @Test
    fun `a full listing keeps its members and abandons every local row it did not name`() {
        val kept = keptHrefs(
            fullListing = true,
            local = local,
            members = members(present = listOf("/dav/user/cal/new.ics")),
        )

        assertEquals(setOf("/dav/user/cal/new.ics"), kept)
    }
}
