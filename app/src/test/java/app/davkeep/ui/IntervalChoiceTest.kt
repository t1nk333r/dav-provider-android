package app.davkeep.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.davkeep.sync.SyncPreferences

/**
 * The intervals §8 offers, and what they are called.
 *
 * The list and the names have to agree: an interval the chooser offers but nothing names would
 * render as another option's text, and the user's answer would then be a different schedule from
 * the one they picked.
 */
class IntervalChoiceTest {

    @Test
    fun `no offered interval is below the interval the framework honours`() {
        // The framework clamps a periodic sync to fifteen minutes, so a shorter option would be a
        // promise the platform does not keep: the row would say fifteen minutes and read "5".
        assertTrue(
            SyncPreferences.OFFERED_INTERVAL_SECONDS.all { it >= SyncPreferences.MIN_INTERVAL_SECONDS },
        )
    }

    @Test
    fun `the default interval is one the chooser offers`() {
        // Otherwise a fresh Account's row would show an interval the chooser cannot select, and
        // opening the chooser would leave nothing checked for the interval already in force.
        assertTrue(
            SyncPreferences.DEFAULT_INTERVAL_SECONDS in SyncPreferences.OFFERED_INTERVAL_SECONDS,
        )
    }

    @Test
    fun `every offered interval has a name of its own`() {
        val labels = SyncPreferences.OFFERED_INTERVAL_SECONDS.map { intervalLabelResource(it) }

        assertTrue(labels.none { it == null })
        assertEquals(labels.size, labels.toSet().size)
    }

    /**
     * An interval nothing offers is still the interval the Account is on, so it is named rather than
     * replaced with the default: the card must never describe a schedule the framework does not have.
     */
    @Test
    fun `an interval nothing offers falls back to minutes`() {
        assertNull(intervalLabelResource(12L * 60 * 60))
        assertNotNull(intervalLabelResource(SyncPreferences.DEFAULT_INTERVAL_SECONDS))
    }
}
