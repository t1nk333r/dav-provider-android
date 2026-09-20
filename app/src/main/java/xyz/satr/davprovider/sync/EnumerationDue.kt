package xyz.satr.davprovider.sync

/**
 * §8's "daily" half of re-enumeration, as the decision a run makes rather than a comparison buried
 * in it.
 *
 * Pure on purpose, and for the reason [SyncPreflight] is: what can be wrong here is the rule rather
 * than the clock, and a rule decided inside a run whose every path needs a device is a rule nothing
 * exercises. It is also the one part of [DailyEnumeration] that needs no `Context` — the decision
 * reads an instant, and only the walk itself talks to a server or a store.
 *
 * The instant is milliseconds since the epoch: the unit the Account's own state is kept in.
 */
internal object EnumerationDue {

    /**
     * §8's cadence: a day between walks of one Account.
     *
     * A duration rather than a calendar day, so a run at 03:00 and one at 23:00 the same evening are
     * not a day apart: what the walk waits for is the server's list going stale, and that is a fact
     * about elapsed time rather than about midnight.
     */
    const val DAY_MS: Long = 24L * 60 * 60 * 1000

    /**
     * Whether a walk is due.
     *
     * @param lastEnumeratedAt when the last walk of this Account ran to the end, or null when none
     *   this build knows of ever has — either way there is nothing to have gone stale.
     */
    fun decide(lastEnumeratedAt: Long?, now: Long): Boolean = when {
        // Never walked: an Account's Collections are unknown until one is, which is the whole reason
        // §8 promises a daily one.
        lastEnumeratedAt == null -> true

        // A stamp in the future is one this build cannot explain — a clock corrected backwards, or
        // userdata restored from a device whose clock ran ahead. Believing it would suppress every
        // walk until real time caught up with it, so it is read as "due" instead.
        lastEnumeratedAt > now -> true

        else -> now - lastEnumeratedAt >= DAY_MS
    }
}
