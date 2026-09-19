package xyz.satr.davprovider.provider.calendar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Grouping the rows a batch touches by the resource each claims.
 *
 * One query per batch replaced one per resource, so this is where a resource is handed the rows it
 * used to look up for itself. Getting it wrong is quiet in both directions: a row not handed over is
 * written again as though it were new, and a row handed to the wrong resource is one that writer may
 * then delete as a duplicate.
 */
class CalendarRowsTest {

    @Test
    fun `a master and its overrides are handed to the resource they name`() {
        val master = row(1, syncId = "/dav/a.ics")
        val override = row(2, originalSyncId = "/dav/a.ics")

        val claimed = rowsByClaim(listOf(master, override), listOf("/dav/a.ics"))

        assertEquals(listOf(master, override), claimed["/dav/a.ics"])
    }

    @Test
    fun `a resource this batch does not name is left out`() {
        val other = row(3, syncId = "/dav/b.ics")

        assertEquals(emptyMap<String, List<CalendarMapper.ExistingRow>>(), rowsByClaim(listOf(other), listOf("/dav/a.ics")))
    }

    /** A row can claim two names, and the per-resource query would have returned it for both. */
    @Test
    fun `a row claiming two names appears under each`() {
        val both = row(4, syncId = "/dav/a.ics", originalSyncId = "/dav/b.ics")

        val claimed = rowsByClaim(listOf(both), listOf("/dav/a.ics", "/dav/b.ics"))

        assertEquals(listOf(both), claimed["/dav/a.ics"])
        assertEquals(listOf(both), claimed["/dav/b.ics"])
    }

    @Test
    fun `a row claiming nothing is dropped`() {
        val orphan = row(5)

        assertEquals(emptyMap<String, List<CalendarMapper.ExistingRow>>(), rowsByClaim(listOf(orphan), listOf("/dav/a.ics")))
    }

    /** The writer takes the first row claiming a name, so the order is what decides which one it reuses. */
    @Test
    fun `rows come back in id order however they were read`() {
        val later = row(9, syncId = "/dav/a.ics")
        val earlier = row(2, syncId = "/dav/a.ics")

        val claimed = rowsByClaim(listOf(later, earlier), listOf("/dav/a.ics"))

        assertEquals(listOf(earlier, later), claimed["/dav/a.ics"])
    }

    private fun row(id: Long, syncId: String? = null, originalSyncId: String? = null) =
        CalendarMapper.ExistingRow(
            id = id,
            syncId = syncId,
            originalSyncId = originalSyncId,
            originalInstanceTime = null,
            originalAllDay = false,
        )
}
