package app.davkeep.ui

import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import app.davkeep.core.ErrorClass

/**
 * The log's own file format: what an entry means after a round trip, and what happens to a line
 * this build did not write.
 *
 * A log is read on a device that may have been upgraded from an older build and may hold a line
 * written by a different one, so the two directions that can silently lose the evidence someone
 * opened the log for — a field not surviving a write, and a foreign line taking the file down with
 * it — are the parts exercised here.
 */
class SyncLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `keeps every field of an entry it wrote`() {
        val log = SyncLog(folder.newFile("sync-log.txt"))

        log.append(
            account = "Personal",
            collectionId = "dav-2f1c4a",
            summary = "The server needs a username and password",
            errorClass = ErrorClass.ORIGIN_WANTS_CREDENTIALS,
            httpStatus = 401,
            method = "PROPFIND",
            level = SyncLog.Level.ERROR,
            authority = "com.android.calendar",
            displayName = "Work",
            certificateOffered = false,
            written = 3,
            deleted = 1,
            unchanged = false,
            firstBodyLine = "<error xmlns=\"DAV:\">\tneed-privileges",
            davCondition = "need-privileges",
            cause = "NullPointerException: Attempt to invoke virtual method on a null object reference at CalendarMapper:232",
        )

        val entry = log.read().single()
        assertEquals(SyncLog.Kind.SYNC, entry.kind)
        assertEquals(SyncLog.Level.ERROR, entry.level)
        assertEquals("Personal", entry.account)
        assertEquals("dav-2f1c4a", entry.collectionId)
        assertEquals("Work", entry.displayName)
        assertEquals("com.android.calendar", entry.authority)
        assertEquals("The server needs a username and password", entry.summary)
        assertEquals(ErrorClass.ORIGIN_WANTS_CREDENTIALS, entry.errorClass)
        assertEquals(401, entry.httpStatus)
        assertEquals("PROPFIND", entry.method)
        assertEquals(false, entry.certificateOffered)
        assertEquals(3, entry.written)
        assertEquals(1, entry.deleted)
        assertEquals(false, entry.unchanged)
        // A tab inside a value must not become the next field.
        assertEquals("<error xmlns=\"DAV:\">\tneed-privileges", entry.firstBodyLine)
        assertEquals("need-privileges", entry.davCondition)
        assertEquals(
            "NullPointerException: Attempt to invoke virtual method on a null object reference at CalendarMapper:232",
            entry.cause,
        )
    }

    /**
     * What "was a certificate offered" is worth: it is recorded where the classifier observed it,
     * and left unset where nothing said — a Collection that synced has no such fact.
     */
    @Test
    fun `records whether a certificate was offered only when it was observed`() {
        val log = SyncLog(folder.newFile("sync-log.txt"))
        log.append(
            account = "Personal",
            collectionId = "dav-1",
            summary = "The server is having trouble",
            errorClass = ErrorClass.SERVER_ERROR,
            httpStatus = 503,
            method = "PROPFIND",
            level = SyncLog.Level.ERROR,
            certificateOffered = true,
        )
        log.append(
            account = "Personal",
            collectionId = "dav-2",
            summary = "ok",
            errorClass = null,
            httpStatus = null,
            method = null,
            level = SyncLog.Level.INFO,
            certificateOffered = null,
            written = 2,
            deleted = 0,
        )

        val entries = log.read()
        assertEquals(true, entries[0].certificateOffered)
        assertNull(entries[1].certificateOffered)
        assertEquals(2, entries[1].written)
    }

    @Test
    fun `holds a discovery walk under its own kind`() {
        val log = SyncLog(folder.newFile("sync-log.txt"))
        log.appendDiscovery(
            account = "Personal",
            level = SyncLog.Level.WARN,
            notes = listOf(
                "/.well-known/caldav: PROPFIND https://example.invalid/.well-known/caldav (Depth 0) → 404",
                "base URL as Collection: PROPFIND https://example.invalid (Depth 0) → no address book or calendar",
            ),
        )

        val entries = log.read()
        assertEquals(2, entries.size)
        assertTrue(entries.all { it.kind == SyncLog.Kind.DISCOVERY })
        assertTrue(entries.all { it.level == SyncLog.Level.WARN })
        assertEquals("Personal", entries[0].account)
        assertTrue(entries[1].summary.startsWith("base URL as Collection"))
    }

    /**
     * The shape this build's predecessor wrote: seven fields, two spaces apart. Reading it as
     * nothing would lose the only record of runs that happened before the upgrade.
     */
    @Test
    fun `reads a line in the format this one replaced`() {
        val file = folder.newFile("sync-log.txt")
        file.writeText(
            "2026-01-02T03:04:05Z  Personal  dav-9f31  ORIGIN_WANTS_CREDENTIALS  401  PROPFIND  " +
                "The server needs a username and password\n",
        )

        val entry = SyncLog(file).read().single()
        assertEquals(SyncLog.Kind.SYNC, entry.kind)
        assertEquals(SyncLog.Level.ERROR, entry.level)
        assertEquals(Instant.parse("2026-01-02T03:04:05Z"), entry.at)
        assertEquals("Personal", entry.account)
        assertEquals("dav-9f31", entry.collectionId)
        assertEquals(ErrorClass.ORIGIN_WANTS_CREDENTIALS, entry.errorClass)
        assertEquals(401, entry.httpStatus)
        assertEquals("PROPFIND", entry.method)
        assertEquals("The server needs a username and password", entry.summary)
        // Fields that format never had stay unset rather than being invented.
        assertNull(entry.displayName)
        assertNull(entry.certificateOffered)
        assertNull(entry.written)
        assertNull(entry.unchanged)
    }

    /**
     * Class 3 was never a failure, and the old viewer showed it as one: an informational line read
     * back from an older build must not turn into an error.
     */
    @Test
    fun `reads a replaced-format informational line as information`() {
        val file = folder.newFile("sync-log.txt")
        file.writeText(
            "2026-01-02T03:04:05Z  Personal  dav-9f31  ORIGIN_REFUSED_INFO  403  REPORT  " +
                "this collection doesn't support fast sync — using a slower method\n",
        )

        assertEquals(SyncLog.Level.INFO, SyncLog(file).read().single().level)
    }

    @Test
    fun `keeps a line it cannot read without losing the rest`() {
        val file = folder.newFile("sync-log.txt")
        val readable = "v2\tlevel=INFO\tkind=SYNC\taccount=Personal\tsummary=ok"
        file.writeText("not a log line at all\n$readable\n\n")

        val entries = SyncLog(file).read()
        assertEquals(2, entries.size)
        assertEquals(SyncLog.Kind.UNPARSED, entries[0].kind)
        assertEquals("not a log line at all", entries[0].summary)
        assertNull(entries[0].account)
        assertEquals(SyncLog.Kind.SYNC, entries[1].kind)
        assertEquals("Personal", entries[1].account)
    }

    /** Removing an Account removes its entries — and only its entries. */
    @Test
    fun `forgets one account and keeps everything else`() {
        val file = folder.newFile("sync-log.txt")
        val log = SyncLog(file)
        log.append(
            account = "Personal", collectionId = "dav-1", summary = "ok", errorClass = null,
            httpStatus = null, method = null, level = SyncLog.Level.INFO,
        )
        log.append(
            account = "Work", collectionId = "dav-2", summary = "ok", errorClass = null,
            httpStatus = null, method = null, level = SyncLog.Level.INFO,
        )
        // A line this build cannot read names no Account, so it is not this Account's to remove.
        file.writeText(file.readText() + "an older build's line\n")

        log.forget("Personal")

        val entries = SyncLog(file).read()
        assertEquals(2, entries.size)
        assertEquals("Work", entries[0].account)
        assertEquals(SyncLog.Kind.UNPARSED, entries[1].kind)
    }

    /** The cap is what keeps a response body from filling the ring buffer. */
    @Test
    fun `caps the response body line it keeps`() {
        val log = SyncLog(folder.newFile("sync-log.txt"))
        log.append(
            account = "Personal", collectionId = null, summary = "ok", errorClass = null,
            httpStatus = null, method = null, level = SyncLog.Level.INFO,
            firstBodyLine = "x".repeat(400),
        )

        val body = log.read().single().firstBodyLine
        assertEquals(201, body?.length)
        assertTrue(body!!.endsWith("\u2026"))
    }

    /** Writes from a second thread, which is what a sync running behind the settings screen is. */
    @Test
    fun `keeps entries written concurrently`() {
        val log = SyncLog(folder.newFile("sync-log.txt"))
        val threads = 4
        val each = 25
        val pool = Executors.newFixedThreadPool(threads)
        try {
            (0 until threads).forEach { thread ->
                pool.execute {
                    repeat(each) { index ->
                        log.append(
                            account = "Personal", collectionId = "dav-$thread-$index", summary = "ok",
                            errorClass = null, httpStatus = null, method = null,
                            level = SyncLog.Level.INFO,
                        )
                    }
                }
            }
            pool.shutdown()
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals(threads * each, log.read().size)
    }
}
