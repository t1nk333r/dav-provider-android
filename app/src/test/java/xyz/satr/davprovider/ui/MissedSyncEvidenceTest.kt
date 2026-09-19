package xyz.satr.davprovider.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §8's missed-slot evidence, exercised without a device.
 *
 * This is the arithmetic the account card accuses Android with, so both directions of being wrong
 * matter: a count that grows on a schedule that is working would send someone to change a system
 * setting for no reason, and a count that stays small while slots keep passing would leave the
 * prompt that exists for exactly that case permanently unsaid.
 */
class MissedSyncEvidenceTest {

    private val fifteenMinutes = 15L * 60

    /**
     * Minutes after a fixed, non-zero point. Not zero, because zero is how the record says "no
     * automatic run recorded" — a test based at the epoch would be exercising that rule instead of
     * the arithmetic it means to.
     */
    private fun at(minutes: Long): Long = 1_700_000_000_000L + minutes * 60L * 1000L

    // ------------------------------------------------------------ the arithmetic

    @Test
    fun `a run inside its own interval is not late at all`() {
        assertEquals(0, missedSlotsAfter(at(0), at(10), fifteenMinutes, previousMissedSlots = 0))
    }

    @Test
    fun `a gap under the threshold is jitter and clears the count`() {
        // 29 minutes against a 15-minute interval: one slot boundary crossed, and the framework's
        // scheduler is allowed that much slack — a count that survived this would accumulate from
        // ordinary jitter until it accused a schedule that is working.
        assertEquals(0, missedSlotsAfter(at(0), at(29), fifteenMinutes, previousMissedSlots = 4))
    }

    @Test
    fun `a gap exactly at the threshold is one missed slot`() {
        // 30 minutes: the slot at 15 minutes passed unused, and the run at 30 minutes used its own.
        assertEquals(1, missedSlotsAfter(at(0), at(30), fifteenMinutes, previousMissedSlots = 0))
    }

    @Test
    fun `a gap just over the threshold is one missed slot`() {
        assertEquals(1, missedSlotsAfter(at(0), at(31), fifteenMinutes, previousMissedSlots = 0))
    }

    @Test
    fun `a gap of several intervals counts every slot it contains`() {
        // Two hours against fifteen minutes is eight slots, of which the run used the last: seven
        // went unused. Counting one per run instead would need seven runs to reach the threshold,
        // and those runs are exactly what a suppressed schedule does not produce.
        assertEquals(7, missedSlotsAfter(at(0), at(120), fifteenMinutes, previousMissedSlots = 0))
    }

    @Test
    fun `misses add up across runs until a punctual one clears them`() {
        val first = missedSlotsAfter(at(0), at(30), fifteenMinutes, previousMissedSlots = 0)
        val second = missedSlotsAfter(at(30), at(60), fifteenMinutes, previousMissedSlots = first)

        assertEquals(2, second)
        // The run that arrived on time is the evidence that the schedule recovered, and it is the
        // only thing that clears the count.
        assertEquals(0, missedSlotsAfter(at(60), at(70), fifteenMinutes, previousMissedSlots = second))
    }

    @Test
    fun `an account that has never had an automatic run has nothing to conclude`() {
        assertEquals(0, missedSlotsAfter(0L, at(600), fifteenMinutes, previousMissedSlots = 0))
    }

    @Test
    fun `a clock that moved backwards is not evidence of a missed slot`() {
        // Time can go backwards — a network time correction, a timezone database update — and the
        // gap it produces says nothing about the schedule either way.
        assertEquals(6, missedSlotsAfter(at(600), at(0), fifteenMinutes, previousMissedSlots = 6))
    }

    // ------------------------------------------------------------ what a run does to the record

    @Test
    fun `a manual run leaves the evidence exactly as it was`() {
        // The user's own "Sync now" happens whenever they ask. Counting it as the schedule firing
        // would let it erase a missed slot, and counting it as a gap would invent one.
        val previous = report(lastAutomaticAt = at(0), missedSlots = 3)

        val after = mergeRun(
            previous = previous,
            authority = "com.android.calendar",
            atMillis = at(600),
            status = AccountStatus.OK,
            summary = null,
            collections = emptyList(),
            automatic = false,
            intervalSeconds = fifteenMinutes,
        )

        assertEquals(at(0), after.lastAutomaticAt)
        assertEquals(3, after.missedSlots)
    }

    @Test
    fun `an automatic run that arrived on time clears the count`() {
        val after = mergeRun(
            previous = report(lastAutomaticAt = at(0), missedSlots = 5),
            authority = "com.android.calendar",
            atMillis = at(20),
            status = AccountStatus.OK,
            summary = null,
            collections = emptyList(),
            automatic = true,
            intervalSeconds = fifteenMinutes,
        )

        assertEquals(0, after.missedSlots)
        assertEquals(at(20), after.lastAutomaticAt)
    }

    @Test
    fun `a deferred automatic run counts as the schedule firing`() {
        // §8's unmetered-only setting holds a run back, and the framework asking anyway proves the
        // schedule is working. Counting the deferral as a miss would have the card accuse Android
        // of suppressing syncs that this app's own setting deferred.
        val previous = report(lastAutomaticAt = at(0), missedSlots = 4)

        val deferred = mergeDeferral(previous, atMillis = at(20), intervalSeconds = fifteenMinutes)

        assertEquals(at(20), deferred.lastAutomaticAt)
        assertEquals(0, deferred.missedSlots)
        assertTrue(deferred.deferred)

        // The next one is measured from the deferral, not from the run before it: a setting that
        // holds every run back must not accumulate a gap that reads as a suppressed schedule.
        val again = mergeDeferral(deferred, atMillis = at(25), intervalSeconds = fifteenMinutes)

        assertEquals(at(25), again.lastAutomaticAt)
        assertEquals(0, again.missedSlots)
    }

    @Test
    fun `a run keeps the dismissal and the record it does not own`() {
        val previous = report(lastAutomaticAt = at(0), missedSlots = 4, dismissed = true).copy(
            summary = "503 from the server",
            collections = listOf(CollectionReport("a-collection", CollectionOutcome.FAILED)),
            authorities = mapOf(
                "com.android.calendar" to AuthorityReport(AccountStatus.FAILED, "503 from the server"),
            ),
        )

        val after = mergeRun(
            previous = previous,
            authority = "com.android.contacts",
            atMillis = at(600),
            status = AccountStatus.OK,
            summary = null,
            collections = listOf(CollectionReport("another-collection", CollectionOutcome.OK)),
            automatic = true,
            intervalSeconds = fifteenMinutes,
        )

        assertTrue(after.batteryPromptDismissed)
        // The previous authority's verdict and the Collections it reported survive a run by the
        // other one; only the run's own authority speaks for this run.
        assertEquals("503 from the server", after.authorities["com.android.calendar"]?.summary)
        assertEquals(
            listOf("a-collection", "another-collection"),
            after.collections.map { it.collectionId },
        )
    }

    // ------------------------------------------------------------ whether to offer the exemption

    @Test
    fun `the prompt appears only once there is a pattern of missed slots`() {
        assertFalse(batteryExemptionAdvised(report(missedSlots = 2), alreadyExempt = false))
        assertTrue(batteryExemptionAdvised(report(missedSlots = 3), alreadyExempt = false))
    }

    @Test
    fun `an account with nothing recorded is never prompted`() {
        assertFalse(batteryExemptionAdvised(report = null, alreadyExempt = false))
        assertFalse(batteryExemptionAdvised(report(missedSlots = 0), alreadyExempt = false))
    }

    @Test
    fun `a dismissed prompt never comes back, however many slots are missed`() {
        assertFalse(batteryExemptionAdvised(report(missedSlots = 40, dismissed = true), alreadyExempt = false))
    }

    @Test
    fun `an app that is already exempt is not asked again`() {
        // §8: a row asking for something already granted asks the user to fix what is not wrong.
        assertFalse(batteryExemptionAdvised(report(missedSlots = 40), alreadyExempt = true))
    }

    private fun report(
        lastAutomaticAt: Long = 0L,
        missedSlots: Int = 0,
        dismissed: Boolean = false,
    ) = AccountReport(
        status = AccountStatus.OK,
        lastSyncAt = lastAutomaticAt,
        collections = emptyList(),
        lastAutomaticAt = lastAutomaticAt,
        missedSlots = missedSlots,
        batteryPromptDismissed = dismissed,
    )
}
