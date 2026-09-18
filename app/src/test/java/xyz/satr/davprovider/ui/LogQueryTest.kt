package xyz.satr.davprovider.ui

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen's question, asked of entries instead of a screen: the search, the three filters and the
 * direction, composed.
 *
 * These four are the reason the log moved off a dialog — a reader narrows to one Account's errors
 * and then searches inside that — so what is pinned here is that they compose rather than replace
 * each other, and that the two things a narrowing must never do (hide a line this build cannot read,
 * or answer a search that matched nothing with everything) do not happen.
 */
class LogQueryTest {

    @Test
    fun `the direction is a view of the same entries, newest first by default`() {
        val entries = listOf(entry("oldest", at = T0), entry("middle", at = T1), entry("newest", at = T2))

        assertEquals(
            listOf("newest", "middle", "oldest"),
            LogQuery().select(entries).map { it.summary },
        )
        assertEquals(
            listOf("oldest", "middle", "newest"),
            LogQuery(newestFirst = false).select(entries).map { it.summary },
        )
    }

    @Test
    fun `a search composes with a level, a kind and an account`() {
        val entries = listOf(
            entry("The server timed out", level = ERROR, kind = SYNC, account = "Personal"),
            entry("timed out while finding Collections", level = ERROR, kind = DISCOVERY, account = "Personal"),
            entry("timed out reading a Collection", level = WARN, kind = SYNC, account = "Personal"),
            entry("The server timed out", level = ERROR, kind = SYNC, account = "Work"),
            entry("The server refused the request", level = ERROR, kind = SYNC, account = "Personal"),
        )

        val narrowed = LogQuery(level = ERROR, kind = SYNC, account = "Personal", search = "TIMED OUT")

        assertEquals(listOf("The server timed out"), narrowed.select(entries).map { it.summary })
        assertEquals(
            listOf("The server timed out"),
            narrowed.copy(newestFirst = false).select(entries).map { it.summary },
        )

        // The same three filters without the search still answer with both of that Account's errors,
        // newest first: dropping the search widens the question rather than changing it.
        assertEquals(
            listOf("The server refused the request", "The server timed out"),
            LogQuery(level = ERROR, kind = SYNC, account = "Personal").select(entries).map { it.summary },
        )
    }

    @Test
    fun `search reaches the fields a failure is described by, ignoring case`() {
        val described = entry(
            summary = "summary-token",
            account = "account-token",
            displayName = "collection-token",
            collectionId = "id-token",
            davCondition = "condition-token",
            firstBodyLine = "<error> body-token",
        )

        listOf(
            "summary-token",
            "account-token",
            "collection-token",
            "id-token",
            "condition-token",
            "body-token",
        ).forEach { token ->
            assertEquals(
                "searching for \"$token\" does not reach it",
                listOf("summary-token"),
                LogQuery(search = token.uppercase()).select(listOf(described)).map { it.summary },
            )
        }
    }

    @Test
    fun `a line this build cannot read is shown unless a kind filter asks for something else`() {
        val readable = entry("a run", at = T0)
        val unreadable = SyncLog.Entry(
            at = null,
            level = WARN,
            kind = SyncLog.Kind.UNPARSED,
            account = null,
            summary = "a line nobody can read",
        )
        val entries = listOf(readable, unreadable)

        assertEquals(
            listOf("a line nobody can read", "a run"),
            LogQuery().select(entries).map { it.summary },
        )
        assertEquals(listOf("a run"), LogQuery(kind = SyncLog.Kind.SYNC).select(entries).map { it.summary })
        assertEquals(
            listOf("a line nobody can read"),
            LogQuery(level = WARN).select(entries).map { it.summary },
        )
    }

    @Test
    fun `a search that matches nothing answers with nothing, and a blank one is no search`() {
        val entries = listOf(entry("The server timed out"), entry("The server refused the request"))
        val inOrder = listOf("The server timed out", "The server refused the request")

        assertTrue(LogQuery(search = "no such text in this log").select(entries).isEmpty())
        assertEquals(inOrder, LogQuery(search = "   ", newestFirst = false).select(entries).map { it.summary })
    }

    private fun entry(
        summary: String,
        at: Instant = T0,
        level: SyncLog.Level = SyncLog.Level.INFO,
        kind: SyncLog.Kind = SyncLog.Kind.SYNC,
        account: String? = "Personal",
        displayName: String? = null,
        collectionId: String? = null,
        davCondition: String? = null,
        firstBodyLine: String? = null,
    ) = SyncLog.Entry(
        at = at,
        level = level,
        kind = kind,
        account = account,
        summary = summary,
        displayName = displayName,
        collectionId = collectionId,
        davCondition = davCondition,
        firstBodyLine = firstBodyLine,
    )

    private companion object {
        val T0: Instant = Instant.parse("2026-01-01T09:00:00Z")
        val T1: Instant = Instant.parse("2026-01-01T09:05:00Z")
        val T2: Instant = Instant.parse("2026-01-01T09:10:00Z")

        val WARN = SyncLog.Level.WARN
        val ERROR = SyncLog.Level.ERROR
        val SYNC = SyncLog.Kind.SYNC
        val DISCOVERY = SyncLog.Kind.DISCOVERY
    }
}
