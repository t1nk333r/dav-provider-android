package app.davkeep.provider.calendar

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which existing row each component of a resource continues, and which rows are deleted.
 *
 * The write path appends its operations to one batch, so these decisions are made before anything is
 * written: a row handed to the wrong component is updated twice and then deleted by the same commit,
 * and a row nothing claims is removed even though the server still sends it. Both are quiet — the
 * resource looks written either way — which is why the decisions are a function of their own, over
 * rows a query already returned, and tested here.
 */
class CalendarRowPlanTest {

    @Test
    fun `a master and its exception keep the rows the resource already has`() {
        val existing = listOf(row(1, syncId = NAME), exception(2, instanceTime = 1_000L))

        val plan = rowPlan(existing, NAME, listOf(Occurrence(1_000L, allDay = false)))

        assertEquals(RowPlan(masterId = 1L, overrideIds = listOf(2L), doomed = emptyList()), plan)
    }

    @Test
    fun `an exception is matched on the occurrence it replaces, not only on the resource`() {
        val existing = listOf(row(1, syncId = NAME), exception(2, instanceTime = 1_000L), exception(3, instanceTime = 2_000L))

        val plan = rowPlan(existing, NAME, listOf(Occurrence(2_000L, allDay = false)))

        assertEquals(listOf(3L), plan.overrideIds)
    }

    /** The server dropped an occurrence, so its row is one nothing claims any more. */
    @Test
    fun `a row of an occurrence the resource no longer sends is doomed`() {
        val existing = listOf(row(1, syncId = NAME), exception(2, instanceTime = 1_000L))

        val plan = rowPlan(existing, NAME, listOf(Occurrence(2_000L, allDay = false)))

        assertEquals(RowPlan(masterId = 1L, overrideIds = listOf(null), doomed = listOf(2L)), plan)
    }

    /** An all-day occurrence and a timed one are different rows of the provider, not one updated. */
    @Test
    fun `an all-day exception does not take the timed row of the same occurrence`() {
        val existing = listOf(row(1, syncId = NAME), exception(2, instanceTime = 1_000L))

        val plan = rowPlan(existing, NAME, listOf(Occurrence(1_000L, allDay = true)))

        assertEquals(listOf(null), plan.overrideIds)
        assertEquals(listOf(2L), plan.doomed)
    }

    /**
     * Two components of one resource claiming the same occurrence is malformed input, and the second
     * must still not update the row the first continues — nor have it deleted, since it is used.
     */
    @Test
    fun `a row is never claimed twice`() {
        val existing = listOf(row(1, syncId = NAME), exception(2, instanceTime = 1_000L))

        val plan = rowPlan(
            existing,
            NAME,
            listOf(Occurrence(1_000L, allDay = false), Occurrence(1_000L, allDay = false)),
        )

        assertEquals(RowPlan(masterId = 1L, overrideIds = listOf(2L, null), doomed = emptyList()), plan)
    }

    /** A row carrying both identities belongs to the master, and is not an exception's to reuse. */
    @Test
    fun `the master's row is not handed to an exception of the same resource`() {
        val existing = listOf(
            row(1, syncId = NAME, originalSyncId = NAME, instanceTime = 1_000L),
        )

        val plan = rowPlan(existing, NAME, listOf(Occurrence(1_000L, allDay = false)))

        assertEquals(RowPlan(masterId = 1L, overrideIds = listOf(null), doomed = emptyList()), plan)
    }

    @Test
    fun `a resource with no rows yet has nothing to reuse and nothing to delete`() {
        val plan = rowPlan(emptyList(), NAME, listOf(Occurrence(1_000L, allDay = false)))

        assertEquals(RowPlan(masterId = null, overrideIds = listOf(null), doomed = emptyList()), plan)
    }

    /** `_SYNC_ID` has no unique index, so a second row can claim the same resource; it is doomed. */
    @Test
    fun `a duplicated master is doomed rather than written over twice`() {
        val existing = listOf(row(1, syncId = NAME), row(2, syncId = NAME))

        val plan = rowPlan(existing, NAME, emptyList())

        assertEquals(RowPlan(masterId = 1L, overrideIds = emptyList(), doomed = listOf(2L)), plan)
    }

    private fun exception(id: Long, instanceTime: Long, allDay: Boolean = false) =
        row(id, originalSyncId = NAME, instanceTime = instanceTime, allDay = allDay)

    private fun row(
        id: Long,
        syncId: String? = null,
        originalSyncId: String? = null,
        instanceTime: Long? = null,
        allDay: Boolean = false,
    ) = CalendarMapper.ExistingRow(
        id = id,
        syncId = syncId,
        originalSyncId = originalSyncId,
        originalInstanceTime = instanceTime,
        originalAllDay = allDay,
    )

    private companion object {
        const val NAME = "/dav/a.ics"
    }
}
