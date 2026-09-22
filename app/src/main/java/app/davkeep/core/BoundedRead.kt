package app.davkeep.core

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * The most a picked file may hold.
 *
 * A PKCS#12 archive is a certificate, a key and a chain; an account export is JSON with a handful of
 * secrets. Both are kilobytes, and neither has any business being megabytes.
 */
const val MAX_PICKED_FILE_BYTES: Int = 8 * 1024 * 1024

/**
 * Reads a file the user picked, refusing one larger than [max] before it is held.
 *
 * The picker offers every file type on purpose — a `.p12` has no MIME type a picker can be trusted
 * with — so the file is whatever was picked, or whatever was sent to the device. Everything
 * downstream parses these bytes into a certificate store or a JSON document, and this is the only
 * bound before a parser sees them. A file that is too large is refused rather than truncated: half
 * an archive is not a smaller archive.
 *
 * @throws IllegalStateException when the file is larger than [max], with a message a screen can show.
 */
fun readBounded(input: InputStream, max: Int = MAX_PICKED_FILE_BYTES): ByteArray {
    val bytes = ByteArrayOutputStream()
    val chunk = ByteArray(64 * 1024)
    while (true) {
        val read = input.read(chunk)
        if (read < 0) break
        bytes.write(chunk, 0, read)
        // Checked as it grows and not after: a file past the ceiling is never held, which is the
        // whole of what the ceiling is for.
        check(bytes.size() <= max) { "the file is larger than ${max / (1024 * 1024)} MB" }
    }
    return bytes.toByteArray()
}
