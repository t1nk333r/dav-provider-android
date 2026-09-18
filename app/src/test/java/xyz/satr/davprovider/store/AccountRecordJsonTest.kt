package xyz.satr.davprovider.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.satr.davprovider.core.ClientCertificateSource
import xyz.satr.davprovider.core.CollectionType
import xyz.satr.davprovider.core.DavAccount
import xyz.satr.davprovider.core.DavCollection
import xyz.satr.davprovider.core.DavHeader

/**
 * The record's shape: what an Account leaves in userdata, and what it deliberately does not.
 *
 * The JSON text either side of that shape is org.json's, so it is not exercised here; these are the
 * parts that could actually be got wrong, and every failure mode that would lose a field silently.
 */
class AccountRecordJsonTest {

    @Test
    fun `keeps every non-secret field of an Account`() {
        val account = DavAccount(
            label = "server",
            baseUrl = "https://dav.invalid/root/",
            headers = listOf(
                DavHeader("CF-Access-Client-Id", "id"),
                DavHeader("CF-Access-Client-Secret", "token"),
            ),
            certificate = ClientCertificateSource.KeyChainAlias("dav-client"),
            username = "user",
            password = "hunter2",
            collections = listOf(
                DavCollection(
                    id = "calendar-1",
                    url = "https://dav.invalid/root/cal/",
                    type = CollectionType.CALENDAR,
                    displayName = "Calendar",
                    color = 0xFF112233.toInt(),
                    selected = true,
                ),
                DavCollection(
                    id = "book-1",
                    url = "https://dav.invalid/root/book/",
                    type = CollectionType.ADDRESS_BOOK,
                    displayName = null,
                    color = null,
                ),
            ),
        )

        val decoded = AccountRecordJson.decode(AccountRecordJson.encode(account), account.label)

        assertEquals(account.label, decoded.label)
        assertEquals(account.baseUrl, decoded.baseUrl)
        assertEquals(account.certificate, decoded.certificate)
        assertEquals(account.username, decoded.username)
        assertEquals(account.collections, decoded.collections)
        assertEquals(account.headers.map { it.name }, decoded.headers.map { it.name })
    }

    @Test
    fun `keeps an imported certificate as a source of its own`() {
        val account = DavAccount(
            label = "server",
            baseUrl = "https://dav.invalid/root/",
            certificate = ClientCertificateSource.Imported,
        )

        val decoded = AccountRecordJson.decode(AccountRecordJson.encode(account), account.label)

        assertEquals(ClientCertificateSource.Imported, decoded.certificate)
    }

    @Test
    fun `refuses a certificate record it cannot interpret`() {
        val base = mapOf<String, Any?>("baseUrl" to "https://dav.invalid/")

        assertThrows(IllegalStateException::class.java) {
            AccountRecordJson.decode(base + ("certificate" to mapOf("source" to "pkcs11")), "server")
        }
        // A KeyChain selection with no alias names nothing the KeyChain could resolve.
        assertThrows(IllegalStateException::class.java) {
            AccountRecordJson.decode(base + ("certificate" to mapOf("source" to "keychain")), "server")
        }
    }

    @Test
    fun `keeps header names but never their values or the password`() {
        val secret = "a-token-that-must-not-be-stored"
        val account = DavAccount(
            label = "server",
            baseUrl = "https://dav.invalid/root/",
            headers = listOf(DavHeader("Authorization", secret)),
            password = secret,
        )

        val body = AccountRecordJson.encode(account)

        assertEquals(listOf("Authorization"), body["headerNames"])
        assertFalse(body.containsValue(secret))
        assertNull(body["password"])
    }

    @Test
    fun `reads absent optional fields as unset`() {
        val decoded = AccountRecordJson.decode(mapOf("baseUrl" to "https://dav.invalid/"), "server")

        assertEquals("https://dav.invalid/", decoded.baseUrl)
        assertNull(decoded.certificate)
        assertNull(decoded.username)
        assertNull(decoded.password)
        assertTrue(decoded.headers.isEmpty())
        assertTrue(decoded.collections.isEmpty())
    }

    @Test
    fun `refuses a record it cannot interpret rather than guessing`() {
        assertThrows(IllegalStateException::class.java) {
            AccountRecordJson.decode(emptyMap(), "server")
        }
        assertThrows(IllegalStateException::class.java) {
            AccountRecordJson.decode(
                mapOf(
                    "baseUrl" to "https://dav.invalid/",
                    "collections" to listOf(
                        mapOf(
                            "id" to "tasks-1",
                            "url" to "https://dav.invalid/root/tasks/",
                            "type" to "TASKS",
                        ),
                    ),
                ),
                "server",
            )
        }
    }

    /**
     * An Account saved before the certificate gained a source discriminator stored a bare
     * `certAlias`. Dropping it on upgrade would leave a configured Account quietly failing
     * handshakes with nothing on screen to explain why.
     */
    @Test
    fun `a record from an older build keeps its KeyChain certificate`() {
        val account = AccountRecordJson.decode(
            mapOf(
                "baseUrl" to "https://dav.invalid/root/",
                "certAlias" to "some-alias",
            ),
            "server",
        )

        assertEquals(ClientCertificateSource.KeyChainAlias("some-alias"), account.certificate)
    }
}
