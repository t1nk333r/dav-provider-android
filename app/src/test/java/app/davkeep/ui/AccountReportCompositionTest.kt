package app.davkeep.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contacts and calendars run separately, and each writes only its own verdict. These pin the rule
 * that the card reports the worst of them rather than whichever happened to run last: a failing
 * calendar was displayed as "All Collections synced" whenever the contacts run succeeded after it,
 * so the headline contradicted the Collection line printed underneath it.
 */
class AccountReportCompositionTest {

    private fun report(
        status: AccountStatus,
        vararg authorities: Pair<String, AuthorityReport>,
    ) = AccountReport(
        status = status,
        lastSyncAt = 0L,
        collections = emptyList(),
        authorities = authorities.toMap(),
    )

    private fun verdict(status: AccountStatus, summary: String? = null) = AuthorityReport(status, summary)

    @Test
    fun `a failed calendar is not hidden by a contacts run that came after it`() {
        val composed = report(
            status = AccountStatus.OK,
            "com.android.calendar" to verdict(AccountStatus.FAILED),
            "com.android.contacts" to verdict(AccountStatus.OK),
        ).composedStatus

        assertEquals(AccountStatus.FAILED, composed)
    }

    /** The same two runs in the other order must read the same, which is what the bug broke. */
    @Test
    fun `the order the authorities ran in does not change the verdict`() {
        val calendarFirst = report(
            status = AccountStatus.OK,
            "com.android.calendar" to verdict(AccountStatus.FAILED),
            "com.android.contacts" to verdict(AccountStatus.OK),
        ).composedStatus
        val contactsFirst = report(
            status = AccountStatus.OK,
            "com.android.contacts" to verdict(AccountStatus.OK),
            "com.android.calendar" to verdict(AccountStatus.FAILED),
        ).composedStatus

        assertEquals(calendarFirst, contactsFirst)
    }

    /** A failure must clear when its own authority recovers, or the card would lie the other way. */
    @Test
    fun `an authority that recovers stops being reported as failed`() {
        val recovered = report(
            status = AccountStatus.OK,
            "com.android.calendar" to verdict(AccountStatus.OK),
            "com.android.contacts" to verdict(AccountStatus.OK),
        )

        assertEquals(AccountStatus.OK, recovered.composedStatus)
    }

    @Test
    fun `a partial run outranks a clean one but not a failure`() {
        val partialAndOk = report(
            status = AccountStatus.OK,
            "com.android.calendar" to verdict(AccountStatus.PARTIAL),
            "com.android.contacts" to verdict(AccountStatus.OK),
        )
        val failedAndPartial = report(
            status = AccountStatus.OK,
            "com.android.calendar" to verdict(AccountStatus.FAILED),
            "com.android.contacts" to verdict(AccountStatus.PARTIAL),
        )

        assertEquals(AccountStatus.PARTIAL, partialAndOk.composedStatus)
        assertEquals(AccountStatus.FAILED, failedAndPartial.composedStatus)
    }

    /**
     * A record written before the per-authority verdicts existed carries only the top-level fields.
     * Decoding one is not an error, and it must keep rendering what it has.
     */
    @Test
    fun `a record with no per-authority verdicts falls back to its own status`() {
        val legacy = AccountReport(
            status = AccountStatus.PARTIAL,
            lastSyncAt = 0L,
            collections = emptyList(),
            summary = "the run stopped early",
        )

        assertEquals(AccountStatus.PARTIAL, legacy.composedStatus)
        assertEquals("the run stopped early", legacy.composedSummary)
    }

    @Test
    fun `the reported reason is the failing authority's, not the last run's`() {
        val composed = report(
            status = AccountStatus.OK,
            "com.android.contacts" to verdict(AccountStatus.OK, summary = null),
            "com.android.calendar" to verdict(AccountStatus.FAILED, summary = "503 from the server"),
        )

        assertEquals("503 from the server", composed.composedSummary)
    }

    @Test
    fun `a clean run carries no reason to show`() {
        val composed = report(
            status = AccountStatus.OK,
            "com.android.contacts" to verdict(AccountStatus.OK),
            "com.android.calendar" to verdict(AccountStatus.OK),
        )

        assertNull(composed.composedSummary)
    }
}
