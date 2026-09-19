package xyz.satr.davprovider.core

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A file the user picked is read whole or refused — half an archive is not a smaller archive.
 *
 * The picker offers every file type, so these bytes are whatever was picked or sent to the device,
 * and the read is the last thing before a parser sees them.
 */
class BoundedReadTest {

    @Test
    fun `a file under the ceiling comes back byte for byte`() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }

        assertArrayEquals(bytes, readBounded(ByteArrayInputStream(bytes), max = 1_000_000))
    }

    @Test
    fun `an empty file reads as empty rather than as absent`() {
        assertEquals(0, readBounded(ByteArrayInputStream(ByteArray(0)), max = 1_000_000).size)
    }

    @Test
    fun `a file over the ceiling is refused, and says so`() {
        val refused = assertThrows(IllegalStateException::class.java) {
            readBounded(ByteArrayInputStream(ByteArray(1_000_001)), max = 1_000_000)
        }

        assertTrue(
            "the refusal is what a screen shows, so it has to read like one",
            refused.message.orEmpty().contains("larger than"),
        )
    }
}
