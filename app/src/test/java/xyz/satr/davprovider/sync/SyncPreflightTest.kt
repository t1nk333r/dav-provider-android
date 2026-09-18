package xyz.satr.davprovider.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §8's conditions on a run: the ones the app imposes, since the platform has no unmetered-only sync
 * setting of its own.
 *
 * The decision is a pure function of three facts — whether the user asked, whether the Account's
 * setting is on, and whether this authority has anything selected — plus the network. It is
 * exercised here without a device because the network answer is the seam and the rule is this
 * project's: a wrong `manual` or a wrong ordering would either spend the user's mobile data or
 * silently do nothing, and neither is visible in a passing build.
 */
class SyncPreflightTest {

    /** Any instant will do: the tests compare the delay, never the wall clock. */
    private val now = 1_766_000_000_000L

    private fun preflight(metered: Boolean) = SyncPreflight(network = { metered }, clock = { now })

    @Test
    fun `an automatic run on a metered network with the setting on is deferred`() {
        val decision = preflight(metered = true).decide(manual = false, unmeteredOnly = true, hasWork = true)

        assertTrue("expected a deferral, got $decision", decision is RunDecision.Defer)
    }

    @Test
    fun `a deferred run asks to be retried minutes ahead, not at the next tick`() {
        val deferral = preflight(metered = true).decide(manual = false, unmeteredOnly = true, hasWork = true)
        val until = (deferral as RunDecision.Defer).untilSeconds

        // §8's "a few minutes ahead": far enough not to spin, near enough that the user who fixes
        // their network does not wait for the interval.
        assertTrue("reported $until, now ${now / 1000}", until - now / 1000 in 60..(15 * 60).toLong())
    }

    @Test
    fun `a manual run is not deferred, and the network is never asked`() {
        // Throwing rather than answering is the point: §8 says a manual sync bypasses the constraint
        // entirely, so the check must not run at all — not merely be ignored once it has an answer.
        val preflight = SyncPreflight(
            network = { error("a manual run must not consult the network") },
            clock = { now },
        )

        assertEquals(
            RunDecision.Proceed,
            preflight.decide(manual = true, unmeteredOnly = true, hasWork = true),
        )
    }

    @Test
    fun `an unmetered network runs, whatever the setting`() {
        assertEquals(
            RunDecision.Proceed,
            preflight(metered = false).decide(manual = false, unmeteredOnly = true, hasWork = true),
        )
    }

    @Test
    fun `the setting off runs on a metered network`() {
        assertEquals(
            RunDecision.Proceed,
            preflight(metered = true).decide(manual = false, unmeteredOnly = false, hasWork = true),
        )
    }

    @Test
    fun `an Account with nothing selected does no work, and is not deferred`() {
        // Nothing is waiting for the network: there is nothing to sync over it, so a deferral would
        // only say the run had been put off. The manual case below is the other half of this.
        assertEquals(
            RunDecision.NothingToDo,
            preflight(metered = true).decide(manual = false, unmeteredOnly = true, hasWork = false),
        )
    }

    @Test
    fun `a manual run with nothing selected still runs`() {
        // The user asking deserves the real result — "nothing is selected" — rather than a silent
        // no-op, which is the complaint this app exists to answer.
        assertEquals(
            RunDecision.Proceed,
            preflight(metered = true).decide(manual = true, unmeteredOnly = false, hasWork = false),
        )
    }
}
