package xyz.satr.davprovider.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Contacts and calendars sync as two separate runs, each reporting only its own Collections. These
 * pin the rule that keeps the second from erasing the first: replacing the list made a calendar
 * that had just synced render as "not synced yet", because the contacts run that followed wrote a
 * list it was not in.
 */
class CollectionReportMergeTest {

    private fun report(id: String, outcome: CollectionOutcome = CollectionOutcome.OK) =
        CollectionReport(collectionId = id, outcome = outcome)

    @Test
    fun `a run keeps the Collections it did not report`() {
        val calendar = report("dav-calendar")
        val contacts = report("dav-contacts")

        val merged = mergeCollectionReports(listOf(calendar), listOf(contacts))

        assertEquals(listOf("dav-calendar", "dav-contacts"), merged.map { it.collectionId })
    }

    @Test
    fun `a later answer for the same Collection wins`() {
        val failed = report("dav-contacts", CollectionOutcome.FAILED)
        val ok = report("dav-contacts", CollectionOutcome.OK)

        val merged = mergeCollectionReports(listOf(failed), listOf(ok))

        assertEquals(1, merged.size)
        assertEquals(CollectionOutcome.OK, merged.single().outcome)
    }

    @Test
    fun `a Collection keeps its position so the list does not reshuffle`() {
        val merged = mergeCollectionReports(
            listOf(report("a"), report("b"), report("c")),
            listOf(report("b"), report("d")),
        )

        assertEquals(listOf("a", "b", "c", "d"), merged.map { it.collectionId })
    }

    @Test
    fun `the first run is stored as it came`() {
        val reported = listOf(report("a"), report("b"))

        assertEquals(reported, mergeCollectionReports(emptyList(), reported))
    }
}
