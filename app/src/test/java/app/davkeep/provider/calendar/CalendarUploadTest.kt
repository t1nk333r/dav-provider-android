package app.davkeep.provider.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import app.davkeep.core.ChangeKind
import app.davkeep.core.LocalChange

/**
 * Which rows are queued for upload, and which a completed listing may delete.
 *
 * Two tables that lose an edit rather than traffic when they are wrong. A tile table that mistakes a
 * tombstone for a resource to upload puts a deletion back on the server; a deletion sweep that
 * treats "no identity yet" as "nothing claims it" deletes exactly the event the phone created and
 * whose upload the server has not answered.
 */
class CalendarUploadTest {

    @Test
    fun `a tombstone is a deletion, under the name the resource had`() {
        val plan = pendingPlan(listOf(row(1, syncId = NAME, deleted = true, dirty = true, etag = "W/\"7\"")))

        assertEquals(listOf(ChangeKind.DELETE), plan.map { it.kind })
        assertEquals(NAME, plan.single().key)
        assertEquals("W/\"7\"", plan.single().etag)
    }

    /** Created and deleted before any run: the row goes, and no request is sent for it. */
    @Test
    fun `a tombstone with no name is purged without a request`() {
        val plan = pendingPlan(listOf(row(2, deleted = true, dirty = true)))

        assertEquals(listOf(ChangeKind.DELETE), plan.map { it.kind })
        assertEquals(null, plan.single().key)
    }

    @Test
    fun `a created event is a create with no key and keeps its minted UID`() {
        val plan = pendingPlan(listOf(row(3, uid = "minted", dirty = true)))

        assertEquals(listOf(ChangeKind.CREATE), plan.map { it.kind })
        assertEquals(null, plan.single().key)
        assertEquals("minted", plan.single().uid)
    }

    /** The resource is the unit of upload, so an edited override queues its master. */
    @Test
    fun `an edited override queues the master`() {
        val plan = pendingPlan(
            listOf(
                row(10, syncId = NAME, etag = "W/\"1\""),
                row(11, originalSyncId = NAME, instanceTime = 1_000L, dirty = true),
            ),
        )

        assertEquals(listOf(ChangeKind.UPDATE), plan.map { it.kind })
        assertEquals(10L, plan.single().rowId)
        assertEquals(NAME, plan.single().key)
        assertEquals("W/\"1\"", plan.single().etag)
    }

    /** An override the provider linked by id only still names its master through `ORIGINAL_ID`. */
    @Test
    fun `an override linked by id queues the master it points at`() {
        val plan = pendingPlan(
            listOf(
                row(20, syncId = NAME),
                row(21, originalId = 20L, instanceTime = 1_000L, deleted = true),
            ),
        )

        assertEquals(listOf(ChangeKind.UPDATE), plan.map { it.kind })
        assertEquals(20L, plan.single().rowId)
    }

    @Test
    fun `nothing pending is nothing to send`() {
        assertEquals(
            emptyList<Any>(),
            pendingPlan(
                listOf(
                    row(30, syncId = NAME, overrides = 1),
                    row(31, originalSyncId = NAME, instanceTime = 1_000L),
                ),
            ),
        )
    }

    /**
     * An override row deleted outright leaves no tombstone and nothing dirty. The master's count of
     * the overrides its text gave rows to is the only thing left that knows one is missing.
     */
    @Test
    fun `a vanished override queues the master`() {
        val plan = pendingPlan(
            listOf(
                row(32, syncId = NAME, etag = "W/\"3\"", overrides = 2),
                row(33, originalSyncId = NAME, instanceTime = 1_000L),
            ),
        )

        assertEquals(listOf(ChangeKind.UPDATE), plan.map { it.kind })
        assertEquals(32L, plan.single().rowId)
    }

    @Test
    fun `a vanished override on a master owed to the restore is left to the restore`() {
        // No ETag means a revert gave the resource back: the restore rewrites its rows and count
        // from the server, and queueing it would send the phone's stale text under a fresh ETag.
        val plan = pendingPlan(
            listOf(
                row(32, syncId = NAME, etag = null, overrides = 2),
                row(33, originalSyncId = NAME, instanceTime = 1_000L),
            ),
        )

        assertEquals(emptyList<LocalChange>(), plan)
    }

    @Test
    fun `deletions come first, then creates, then updates`() {
        val plan = pendingPlan(
            listOf(
                row(5, dirty = true, uid = "minted"),
                row(1, syncId = NAME, deleted = true),
                row(9, syncId = NAME, dirty = true),
            ),
        )

        assertEquals(listOf(1L, 5L, 9L), plan.map { it.rowId })
        assertEquals(listOf(ChangeKind.DELETE, ChangeKind.CREATE, ChangeKind.UPDATE), plan.map { it.kind })
    }

    /** The invariant that made this a function of its own: a pending edit is never swept away. */
    @Test
    fun `a dirty row with no identity is never deleted`() {
        val doomed = doomedRows(
            listOf(CalendarMapper.ExistingRow(id = 7, syncId = null, originalSyncId = null, dirty = true)),
            emptySet(),
        )

        assertEquals(emptyList<Long>(), doomed)
    }

    @Test
    fun `a tombstone survives a listing that does not name it`() {
        val doomed = doomedRows(
            listOf(
                CalendarMapper.ExistingRow(id = 8, syncId = NAME, originalSyncId = null, dirty = true, deleted = true),
                CalendarMapper.ExistingRow(id = 9, syncId = OTHER, originalSyncId = null, deleted = true),
            ),
            keepHrefs = setOf(NAME),
        )

        assertEquals(emptyList<Long>(), doomed)
    }

    @Test
    fun `a row the listing named survives, and an anonymous clean one does not`() {
        val doomed = doomedRows(
            listOf(
                CalendarMapper.ExistingRow(id = 1, syncId = NAME, originalSyncId = null),
                CalendarMapper.ExistingRow(id = 2, syncId = null, originalSyncId = NAME),
                CalendarMapper.ExistingRow(id = 3, syncId = null, originalSyncId = null),
                CalendarMapper.ExistingRow(id = 4, syncId = OTHER, originalSyncId = null),
            ),
            keepHrefs = setOf(NAME),
        )

        assertEquals(listOf(3L, 4L), doomed)
    }

    /**
     * What a revert leaves: `DIRTY` and `DELETED` cleared and the ETag nulled, on a row that still
     * carries the resource's name. Nothing else is asked for by href before the listing.
     */
    @Test
    fun `a reverted resource is a clean master with no ETag`() {
        val owed = revertedPlan(
            listOf(
                row(40, syncId = NAME),
                row(41, originalSyncId = NAME, instanceTime = 1_000L),
                row(42, syncId = OTHER, etag = "W/\"7\""),
                row(43, dirty = true, uid = "minted"),
            ),
        )

        assertEquals(setOf(NAME), owed)
    }

    /** A resource with an unsent edit on it is step U's; fetching over it would discard the edit. */
    @Test
    fun `a resource with a dirty override is not fetched over`() {
        val owed = revertedPlan(
            listOf(
                row(40, syncId = NAME),
                row(41, originalSyncId = NAME, instanceTime = 1_000L, dirty = true),
            ),
        )

        assertEquals(emptySet<String>(), owed)
    }

    private fun row(
        id: Long,
        syncId: String? = null,
        originalSyncId: String? = null,
        originalId: Long? = null,
        instanceTime: Long? = null,
        dirty: Boolean = false,
        deleted: Boolean = false,
        etag: String? = null,
        uid: String? = null,
        overrides: Int? = null,
    ) = QueueRow(
        id = id,
        syncId = syncId,
        originalSyncId = originalSyncId,
        originalId = originalId,
        dirty = dirty,
        deleted = deleted,
        etag = etag,
        uid = uid,
        overrides = overrides,
    )

    private companion object {
        const val NAME = "/dav/a.ics"
        const val OTHER = "/dav/b.ics"
    }
}
