package xyz.satr.davprovider.ui

import android.content.Context
import java.io.File
import java.time.Instant
import xyz.satr.davprovider.core.ErrorClass

/**
 * The capped ring buffer of recent attempts: newest last, oldest dropped, viewable and shareable.
 *
 * Redaction here is structural rather than a filter that a later edit could forget. There is no
 * parameter a header *value*, a password or a request header could travel in: the signature accepts
 * an account label, a Collection's id and display name, the classifier's summary and §5's
 * status/method/condition fields. Header *names* are a legitimate failure mode and are not secrets,
 * so the caller may phrase them into a summary.
 *
 * The one free-text field that is not ours is [Entry.firstBodyLine]: the first line of a response
 * body, capped here at [MAX_BODY_LINE], kept because §5's Details expander already shows it to the
 * user verbatim and it is the only record of what the *server* said. It is the server's output, not
 * a thing the app chose to send, and it is the reason a rejected request can be told from a
 * rejected request the server answered.
 *
 * **The line format** is a version tag followed by `key=value` fields separated by tabs, with tabs,
 * newlines and backslashes escaped so no value can break the line. Keys are self-describing and a
 * reader keeps the ones it knows and ignores the rest, so a later build may add a field without
 * breaking this one. A line written by an earlier build — seven fields, two spaces apart — is read
 * as the fields it has, and a line that is neither is kept verbatim as [Kind.UNPARSED] rather than
 * dropped: a log that silently loses the entry someone needed is worse than one with a raw line in
 * it.
 */
internal class SyncLog(private val file: File) {

    constructor(context: Context) : this(File(context.applicationContext.filesDir, FILE_NAME))

    /**
     * What the user has to do with the entry: [ERROR] is a failure, [WARN] is something to notice
     * that is not one, [INFO] is a run or a Collection going as it should — including §5's class 3,
     * where the server answered and the app changed its method.
     */
    enum class Level { INFO, WARN, ERROR }

    /** [SYNC] is a run or a Collection's part of one; [DISCOVERY] is the walk that found them. */
    enum class Kind { SYNC, DISCOVERY, UNPARSED }

    /**
     * One attempt, as much as the caller could observe about it.
     *
     * [certificateOffered] is the highest-value field: the KeyChain alias callback either fired or
     * it did not, and that is what separates "the app never offered a certificate" from "the proxy
     * refused the one it got". It is recorded wherever the classifier observed it and stays unset
     * where nothing said — a run with no error carries no such fact, and inventing one would be the
     * ambiguity this field exists to remove.
     *
     * On an entry with no [collectionId] — a run — [written] and [deleted] are the run's totals.
     */
    data class Entry(
        val at: Instant?,
        val level: Level,
        val kind: Kind,
        /** The AccountManager account name, which is the Account's label; null when unreadable. */
        val account: String?,
        val summary: String,
        val collectionId: String? = null,
        val displayName: String? = null,
        val authority: String? = null,
        val errorClass: ErrorClass? = null,
        val httpStatus: Int? = null,
        val method: String? = null,
        val certificateOffered: Boolean? = null,
        val written: Int? = null,
        val deleted: Int? = null,
        /** Resources this run sent to the server, and edits it could not send. */
        val uploaded: Int? = null,
        val pending: Int? = null,
        val unchanged: Boolean? = null,
        val firstBodyLine: String? = null,
        val davCondition: String? = null,
        /** The failure's own words — see [logCause]. Null when the class said all there was. */
        val cause: String? = null,
    )

    /**
     * One run, or one Collection's part of it, without writing it.
     *
     * A run is one of these per Collection plus one of its own, so a reader never has to
     * reconstruct the run from its parts. The caller hands them all to [appendAll] together, because
     * writing here is a rewrite of the file rather than an append to it.
     */
    fun entry(
        account: String,
        collectionId: String?,
        summary: String,
        errorClass: ErrorClass?,
        httpStatus: Int?,
        method: String?,
        level: Level,
        authority: String? = null,
        displayName: String? = null,
        certificateOffered: Boolean? = null,
        written: Int? = null,
        deleted: Int? = null,
        uploaded: Int? = null,
        pending: Int? = null,
        unchanged: Boolean? = null,
        firstBodyLine: String? = null,
        davCondition: String? = null,
        cause: String? = null,
    ): Entry = Entry(
        at = Instant.now(),
        level = level,
        kind = Kind.SYNC,
        account = account,
        summary = summary,
        collectionId = collectionId,
        displayName = displayName,
        authority = authority,
        errorClass = errorClass,
        httpStatus = httpStatus,
        method = method,
        certificateOffered = certificateOffered,
        written = written,
        deleted = deleted,
        uploaded = uploaded,
        pending = pending,
        unchanged = unchanged,
        firstBodyLine = firstBodyLine,
        davCondition = davCondition,
        cause = cause,
    )

    /**
     * Writes one entry, for a caller that has exactly one.
     *
     * A run wants [appendAll]: this is a rewrite of the whole file per call, which is what a run's
     * worth of them was doing.
     */
    fun append(
        account: String,
        collectionId: String?,
        summary: String,
        errorClass: ErrorClass?,
        httpStatus: Int?,
        method: String?,
        level: Level,
        authority: String? = null,
        displayName: String? = null,
        certificateOffered: Boolean? = null,
        written: Int? = null,
        deleted: Int? = null,
        unchanged: Boolean? = null,
        firstBodyLine: String? = null,
        davCondition: String? = null,
        cause: String? = null,
    ) {
        appendAll(
            listOf(
                entry(
                    account = account,
                    collectionId = collectionId,
                    summary = summary,
                    errorClass = errorClass,
                    httpStatus = httpStatus,
                    method = method,
                    level = level,
                    authority = authority,
                    displayName = displayName,
                    certificateOffered = certificateOffered,
                    written = written,
                    deleted = deleted,
                    unchanged = unchanged,
                    firstBodyLine = firstBodyLine,
                    davCondition = davCondition,
                    cause = cause,
                ),
            ),
        )
    }

    /**
     * Writes a run's entries in one pass.
     *
     * The file is read and rewritten per call — a ring buffer that trims has no append — so writing
     * one entry per Collection meant C+1 rewrites of a two-hundred-line file, all on the sync
     * thread. The reporter holds the whole run in hand, which is what makes one call enough.
     */
    fun appendAll(entries: List<Entry>) {
        write(entries.map { encode(it) })
    }

    /**
     * One discovery walk, as the walk itself recorded it: a line per request with what was asked,
     * where it was sent and what came back.
     *
     * Discovery is the one thing in the app whose answer is a *sequence*, and a sequence shown once
     * in a dialog and then lost is why "couldn't find CalDAV or CardDAV services" used to be the
     * whole story. Each note becomes its own entry so it can be filtered and read on its own; the
     * walk's outcome is the level, because whether it completed is the fact the notes are read for.
     *
     * The notes are the walk's own summaries — URLs, statuses, the classifier's wording. Header
     * values and passwords are added by the HTTP layer and never appear in them, and none of this
     * signature is a place they could ride in.
     */
    fun appendDiscovery(account: String, level: Level, notes: List<String>) {
        val at = Instant.now()
        write(
            notes.map { note ->
                encode(
                    Entry(
                        at = at,
                        level = level,
                        kind = Kind.DISCOVERY,
                        account = account,
                        summary = note,
                    ),
                )
            },
        )
    }

    fun read(): List<Entry> = synchronized(LOCK) {
        if (!file.exists()) emptyList() else file.readLines().mapNotNull { decode(it) }
    }

    fun clear() {
        synchronized(LOCK) { file.delete() }
    }

    /**
     * Removing an Account removes its artifacts; the entries of the others stay.
     *
     * Lines are matched by the account they name and written back as they came, so an entry this
     * build cannot read survives it: an unknown format is no reason to delete someone's log.
     */
    fun forget(account: String) {
        synchronized(LOCK) {
            if (!file.exists()) return
            val kept = file.readLines().filterNot { decode(it)?.account == account }
            file.writeText(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"))
        }
    }

    private fun write(lines: List<String>) {
        if (lines.isEmpty()) return
        synchronized(LOCK) {
            val previous = if (file.exists()) file.readLines() else emptyList()
            file.writeText((previous + lines).takeLast(MAX_ENTRIES).joinToString("\n", postfix = "\n"))
        }
    }

    private fun encode(entry: Entry): String = buildString {
        append(VERSION)
        for ((key, value) in fieldsOf(entry)) {
            if (value == null) continue
            append(TAB).append(key).append('=').append(escape(value))
        }
    }

    /** A run entry and a Collection entry differ only in which of these are set. */
    private fun fieldsOf(entry: Entry): List<Pair<String, String?>> = listOf(
        KEY_AT to entry.at?.toString(),
        KEY_LEVEL to entry.level.name,
        KEY_KIND to entry.kind.name,
        KEY_ACCOUNT to entry.account,
        KEY_COLLECTION to entry.collectionId,
        KEY_NAME to entry.displayName,
        KEY_AUTHORITY to entry.authority,
        KEY_CLASS to entry.errorClass?.name,
        KEY_STATUS to entry.httpStatus?.toString(),
        KEY_METHOD to entry.method,
        KEY_CERTIFICATE to entry.certificateOffered?.let { yesNo(it) },
        KEY_WRITTEN to entry.written?.toString(),
        KEY_DELETED to entry.deleted?.toString(),
        // Written only when a run had something to send, so a read-only Collection's line is
        // unchanged from the build before uploads existed.
        KEY_UPLOADED to entry.uploaded?.takeIf { it > 0 || (entry.pending ?: 0) > 0 }?.toString(),
        KEY_PENDING to entry.pending?.takeIf { it > 0 }?.toString(),
        KEY_UNCHANGED to entry.unchanged?.let { yesNo(it) },
        // The one value that comes from the server, and the only one long enough to matter.
        KEY_BODY to entry.firstBodyLine?.let { clip(it, MAX_BODY_LINE) },
        KEY_CONDITION to entry.davCondition,
        KEY_CAUSE to entry.cause?.let { clip(it, MAX_CAUSE_CHARS) },
        KEY_SUMMARY to entry.summary,
    )

    private fun decode(line: String): Entry? {
        val text = line.removeSuffix("\r")
        if (text.isBlank()) return null
        if (!text.startsWith(VERSION + TAB)) return legacy(text) ?: unparsed(text)

        val fields = HashMap<String, String>(24)
        for (field in text.substring(VERSION.length + 1).split(TAB)) {
            val separator = field.indexOf('=')
            // A field without a key is not one this format wrote; skipping it keeps the rest usable.
            if (separator > 0) fields[field.substring(0, separator)] = unescape(field.substring(separator + 1))
        }
        val summary = fields[KEY_SUMMARY] ?: return unparsed(text)
        return Entry(
            at = fields[KEY_AT]?.let { instantOrNull(it) },
            // An unknown level was written by a later build; INFO is the reading that invents least.
            level = fields[KEY_LEVEL]?.let { name -> Level.entries.firstOrNull { it.name == name } } ?: Level.INFO,
            kind = fields[KEY_KIND]?.let { name -> Kind.entries.firstOrNull { it.name == name } } ?: Kind.SYNC,
            account = fields[KEY_ACCOUNT],
            summary = summary,
            collectionId = fields[KEY_COLLECTION],
            displayName = fields[KEY_NAME],
            authority = fields[KEY_AUTHORITY],
            errorClass = fields[KEY_CLASS]?.let { name -> ErrorClass.entries.firstOrNull { it.name == name } },
            httpStatus = fields[KEY_STATUS]?.toIntOrNull(),
            method = fields[KEY_METHOD],
            certificateOffered = fields[KEY_CERTIFICATE]?.let { yesNoOrNull(it) },
            written = fields[KEY_WRITTEN]?.toIntOrNull(),
            deleted = fields[KEY_DELETED]?.toIntOrNull(),
            uploaded = fields[KEY_UPLOADED]?.toIntOrNull(),
            pending = fields[KEY_PENDING]?.toIntOrNull(),
            unchanged = fields[KEY_UNCHANGED]?.let { yesNoOrNull(it) },
            firstBodyLine = fields[KEY_BODY],
            davCondition = fields[KEY_CONDITION],
            cause = fields[KEY_CAUSE],
        )
    }

    /**
     * The format this one replaced: timestamp, account, Collection id, error class, status, method
     * and summary, two spaces apart, with `-` for anything unset.
     *
     * Its level is derived the way that build's viewer showed it — an error class that is not the
     * informational one is a failure — and the fields it never had stay unset rather than being
     * filled with a guess.
     */
    private fun legacy(line: String): Entry? {
        val fields = line.split(SEPARATOR)
        if (fields.size < LEGACY_FIELDS) return null
        val errorClass = fields[3].takeIf { it != DASH }?.let { name -> ErrorClass.entries.firstOrNull { it.name == name } }
        return Entry(
            at = instantOrNull(fields[0]),
            level = when {
                errorClass == null || errorClass == ErrorClass.ORIGIN_REFUSED_INFO -> Level.INFO
                else -> Level.ERROR
            },
            kind = Kind.SYNC,
            account = fields[1].takeIf { it != DASH },
            summary = fields.drop(LEGACY_FIELDS - 1).joinToString(SEPARATOR),
            collectionId = fields[2].takeIf { it != DASH },
            errorClass = errorClass,
            httpStatus = fields[4].takeIf { it != DASH }?.toIntOrNull(),
            method = fields[5].takeIf { it != DASH },
        )
    }

    /** A line nobody can read is still a line the user wrote a sync to produce. */
    private fun unparsed(line: String) = Entry(
        at = null,
        level = Level.WARN,
        kind = Kind.UNPARSED,
        account = null,
        summary = line,
    )

    private companion object {
        const val FILE_NAME = "sync-log.txt"
        const val MAX_ENTRIES = 200

        /** §5 shows the first body line verbatim; a body is not always one line long. */
        const val MAX_BODY_LINE = 200

        /**
         * A failure's own words, which name the class and the parser that gave up. Longer than the
         * body line because a cause chain is several exceptions deep and the innermost is the one
         * worth reading.
         */
        const val MAX_CAUSE_CHARS = 400

        const val VERSION = "v2"
        const val TAB = '\t'
        const val SEPARATOR = "  "
        const val LEGACY_FIELDS = 7
        const val DASH = "-"
        const val ELLIPSIS = "\u2026"

        const val KEY_AT = "at"
        const val KEY_LEVEL = "level"
        const val KEY_KIND = "kind"
        const val KEY_ACCOUNT = "account"
        const val KEY_COLLECTION = "collection"
        const val KEY_NAME = "name"
        const val KEY_AUTHORITY = "authority"
        const val KEY_CLASS = "class"
        const val KEY_STATUS = "status"
        const val KEY_METHOD = "method"
        const val KEY_CERTIFICATE = "certificate"
        const val KEY_WRITTEN = "written"
        const val KEY_DELETED = "deleted"
        const val KEY_UPLOADED = "uploaded"
        const val KEY_PENDING = "pending"
        const val KEY_UNCHANGED = "unchanged"
        const val KEY_BODY = "body"
        const val KEY_CONDITION = "condition"
        const val KEY_CAUSE = "cause"
        const val KEY_SUMMARY = "summary"

        val LOCK = Any()

        fun escape(value: String): String = value
            .replace("\\", "\\\\")
            .replace("\t", "\\t")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        fun unescape(value: String): String {
            if ('\\' !in value) return value
            val out = StringBuilder(value.length)
            var index = 0
            while (index < value.length) {
                val character = value[index]
                if (character != '\\' || index + 1 == value.length) {
                    out.append(character)
                    index++
                    continue
                }
                index++
                out.append(
                    when (value[index]) {
                        't' -> '\t'
                        'n' -> '\n'
                        'r' -> '\r'
                        else -> value[index]
                    },
                )
                index++
            }
            return out.toString()
        }

        fun yesNo(value: Boolean): String = if (value) "yes" else "no"

        fun yesNoOrNull(value: String): Boolean? = when (value) {
            "yes" -> true
            "no" -> false
            else -> null
        }

        fun instantOrNull(value: String): Instant? = try {
            Instant.parse(value)
        } catch (e: java.time.format.DateTimeParseException) {
            null
        }

        fun clip(text: String, max: Int): String = if (text.length <= max) text else text.take(max) + ELLIPSIS
    }
}

/** How deep a cause chain is worth reading before it stops saying anything new. */
private const val MAX_CAUSE_DEPTH = 5

/** This app's own classes, which is what a stack frame is looked for by — a frame inside a library
 * says which library was running, not which call the app made. */
private const val APP_PACKAGE = "xyz.satr.davprovider"

/**
 * A thrown failure in the log's own words: the exception classes from the outside in, each with the
 * message it carried.
 *
 * The chain is the point, not the outermost class. What class a failure gets is decided by reading
 * that chain — [xyz.satr.davprovider.sync.ErrorMapping] tells a body that could not be parsed from a
 * connection that went away by which exceptions are in it — so an entry that recorded the class
 * alone left those two looking identical in the log, which is exactly how a dropped event and a
 * dropped connection came to read the same.
 *
 * Simple class names, because the package in front of `XmlPullParserException` adds nothing to it.
 * Null when there is no cause, so the field is absent rather than empty on the entries that have
 * nothing to add.
 */
internal fun logCause(cause: Throwable?): String? {
    if (cause == null) return null
    var deepest: Throwable? = null
    val text = buildString {
        var level: Throwable? = cause
        var depth = 0
        while (level != null && depth < MAX_CAUSE_DEPTH) {
            if (depth > 0) append(" <- ")
            append(level.javaClass.simpleName)
            val message = level.message?.trim()
            if (!message.isNullOrEmpty()) append(": ").append(message)
            deepest = level
            val next = level.cause
            level = if (next === level) null else next
            depth++
        }
    }
    // Where it happened, for the failures that were not supposed to happen at all. An exception this
    // code did not anticipate is a bug in the app, and the frame is what turns "couldn't be
    // understood" into a line someone can act on; the classes above it only say what was being done.
    val origin = deepest?.stackTrace?.firstOrNull { it.className.startsWith(APP_PACKAGE) }
    return (if (origin == null) text else "$text at ${origin.className.substringAfterLast('.')}:${origin.lineNumber}")
        .ifEmpty { null }
}
