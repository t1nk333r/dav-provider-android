package xyz.satr.davprovider.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §8's re-enumeration cadence: the one walk per Account per day the spec promises and the app used
 * to reach only from a button.
 *
 * The decision is a pure function of two instants, and it is exercised here for the reason
 * [SyncPreflight.decide] is: what can be wrong is the rule rather than the clock. A rule read
 * backwards — or a stamp trusted when it cannot be explained — would either walk the server on every
 * run or stop walking altogether, and neither is visible in a passing build.
 */
class EnumerationDueTest {

    /** Any instant will do: every test states its distance from it rather than a wall clock. */
    private val now = 1_766_000_000_000L

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `an Account that has never been walked is due`() {
        // The state of a new Account, and of one upgraded from a build that kept no stamp at all.
        // §8 promises a daily enumeration, and unknown is not up to date.
        assertTrue(EnumerationDue.decide(lastEnumeratedAt = null, now = now))
    }

    @Test
    fun `a walk from a moment ago is not due`() {
        // The case the stamp exists for: the periodic run that follows a walk by minutes must not
        // walk the server again.
        assertFalse(EnumerationDue.decide(lastEnumeratedAt = now - 60_000, now = now))
    }

    @Test
    fun `a walk a second short of a day is not due`() {
        assertFalse(EnumerationDue.decide(lastEnumeratedAt = now - day + 1_000, now = now))
    }

    @Test
    fun `a walk exactly a day old is due`() {
        // The boundary is inclusive, so a walk is never held back beyond the day §8 promises: the
        // framework's own periodic granularity is fifteen minutes, and a second decides nothing.
        assertTrue(EnumerationDue.decide(lastEnumeratedAt = now - day, now = now))
    }

    @Test
    fun `a walk from days ago is due`() {
        // The phone was off, or nothing had asked it to sync: the server's list has had a week to
        // change, and the walk is what finds out.
        assertTrue(EnumerationDue.decide(lastEnumeratedAt = now - 7 * day, now = now))
    }

    @Test
    fun `a stamp in the future is due, not a reason to stop walking`() {
        // A clock corrected backwards, or userdata restored from a device whose clock ran ahead. A
        // stamp read as an instant would still be in the future tomorrow, so believing it would
        // suppress every walk until real time caught up with it.
        assertTrue(EnumerationDue.decide(lastEnumeratedAt = now + day, now = now))
    }

    @Test
    fun `a stamp of zero is due`() {
        // A value the epoch makes look like a walk that just happened to nothing but the arithmetic —
        // userdata an earlier build left, or a record written half way. It is the oldest possible
        // walk, and read the other way round it would stop this Account from ever enumerating again.
        assertTrue(EnumerationDue.decide(lastEnumeratedAt = 0L, now = now))
    }
}
