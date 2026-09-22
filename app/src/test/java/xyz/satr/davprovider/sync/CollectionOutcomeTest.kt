package xyz.satr.davprovider.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.satr.davprovider.core.ErrorClass
import xyz.satr.davprovider.core.SyncError

/**
 * When a Collection counts as finished.
 *
 * This flag is what the account card reads as Partial and what the log line reads as an error, so it
 * is the one place where "no request failed" and "the Collection is done" are told apart. A change
 * still on the phone is not done, whether or not the listing was perfect; a conflict is done,
 * because the row was given up and the same run fetched the server's version onto it.
 */
class CollectionOutcomeTest {

    private fun outcome(
        missing: Int = 0,
        truncated: Boolean = false,
        pending: Int = 0,
        conflicts: List<String> = emptyList(),
        errorClass: ErrorClass? = null,
    ) = CollectionOutcome(
        collectionId = "books",
        error = errorClass?.let {
            SyncError(
                errorClass = it,
                summary = "summary",
                httpStatus = null,
                firstBodyLine = null,
                certificateOffered = false,
                requestMethod = null,
            )
        },
        pending = pending,
        conflicts = conflicts,
        missing = missing,
        truncated = truncated,
    )

    @Test
    fun `a change that did not leave the phone leaves the Collection unfinished`() {
        // Neither the listing nor the card may read as "synced" while the phone is still holding
        // something the user changed.
        assertTrue(outcome(pending = 1).failed)
    }

    @Test
    fun `a conflict is reported without failing the Collection`() {
        // Nothing is left to retry, and nothing about the server is unknown: the difference between
        // "come back" and "this is what the server has".
        assertFalse(outcome(conflicts = listOf("/books/main/x.vcf")).failed)
    }

    @Test
    fun `an informational class still does not fail a Collection`() {
        // §5 class 3 is the server answering; the read-only backstop's replacement must not turn it
        // into a failure now that writes exist as well.
        assertFalse(outcome(errorClass = ErrorClass.ORIGIN_REFUSED_INFO).failed)
    }

    @Test
    fun `a listing that left something unfetched still fails the Collection`() {
        assertTrue(outcome(missing = 1).failed)
        assertTrue(outcome(truncated = true).failed)
    }
}
