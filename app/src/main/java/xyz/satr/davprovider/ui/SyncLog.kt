package xyz.satr.davprovider.ui

import android.content.Context
import java.io.File
import java.time.Instant
import xyz.satr.davprovider.core.ErrorClass

/**
 * The capped ring buffer of recent attempts: newest last, oldest dropped, viewable and shareable.
 *
 * Redaction here is structural rather than a filter that a later edit could forget. There is no
 * parameter a header *value*, a password, a response body or an exception message could travel
 * in; the signature accepts an account label, a Collection id, the classifier's summary and §5's
 * status/method fields. Header *names* are a legitimate failure mode and are not secrets, so the
 * caller may phrase them into a summary.
 */
internal class SyncLog(context: Context) {

    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    fun append(
        account: String,
        collectionId: String?,
        summary: String,
        errorClass: ErrorClass?,
        httpStatus: Int?,
        method: String?,
    ) {
        val line = listOf(
            Instant.now().toString(),
            account,
            collectionId ?: "-",
            errorClass?.name ?: "-",
            httpStatus?.toString() ?: "-",
            method ?: "-",
            summary,
        ).joinToString(SEPARATOR)
        synchronized(LOCK) {
            val previous = if (file.exists()) file.readLines() else emptyList()
            file.writeText((previous + line).takeLast(MAX_ENTRIES).joinToString("\n", postfix = "\n"))
        }
    }

    fun read(): List<String> = synchronized(LOCK) {
        if (file.exists()) file.readLines() else emptyList()
    }

    fun clear() {
        synchronized(LOCK) { file.delete() }
    }

    /** Removing an Account removes its artifacts; the entries of the others stay. */
    fun forget(account: String) {
        synchronized(LOCK) {
            if (!file.exists()) return
            val kept = file.readLines().filterNot { it.split(SEPARATOR).getOrNull(1) == account }
            file.writeText(kept.joinToString("\n", postfix = if (kept.isEmpty()) "" else "\n"))
        }
    }

    private companion object {
        const val FILE_NAME = "sync-log.txt"
        const val MAX_ENTRIES = 200
        const val SEPARATOR = "  "
        val LOCK = Any()
    }
}
