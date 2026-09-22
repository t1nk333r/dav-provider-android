package app.davkeep.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import app.davkeep.core.CollectionType
import app.davkeep.core.DavCollection

/**
 * The one decision §8's schedule is made of: whether an Account has anything to sync at all.
 *
 * [SyncScheduler]'s own calls are `ContentResolver` statics that write the framework's settings, so
 * they are not exercised here; what is worth pinning is the rule they are called by, because an
 * Account enabled for nothing is not a harmless extra — it is a wakeup, a run and a status line
 * every interval, for a question nobody asked.
 */
class SyncSchedulerTest {

    private fun collection(selected: Boolean, available: Boolean = true) = DavCollection(
        id = "a-collection",
        url = "https://dav.invalid/cal/",
        type = CollectionType.CALENDAR,
        displayName = "Calendar",
        color = null,
        selected = selected,
        available = available,
    )

    @Test
    fun `a Collection that is selected and available is something to sync`() {
        assertTrue(hasSyncableCollections(listOf(collection(selected = true))))
    }

    @Test
    fun `a selection among others is enough`() {
        assertTrue(
            hasSyncableCollections(
                listOf(
                    collection(selected = false),
                    collection(selected = false, available = false),
                    collection(selected = true),
                ),
            ),
        )
    }

    @Test
    fun `nothing selected is nothing to sync`() {
        assertFalse(hasSyncableCollections(emptyList()))
        assertFalse(hasSyncableCollections(listOf(collection(selected = false))))
    }

    @Test
    fun `a selected Collection the server no longer lists is nothing to sync`() {
        // §8: a Collection that disappeared is marked unavailable, not deleted. An Account that has
        // only those syncs exactly as little as one with nothing selected, and the schedule must say
        // the same thing the run's own filter does.
        assertFalse(hasSyncableCollections(listOf(collection(selected = true, available = false))))
    }
}
