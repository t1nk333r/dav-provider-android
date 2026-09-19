package xyz.satr.davprovider.net

import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.satr.davprovider.core.ClientIdentity
import xyz.satr.davprovider.core.DavAccount

/**
 * What [ClientKeyManager] will and will not hand to a handshake, through the decision both alias
 * callbacks delegate to. A JVM test cannot stand up a live handshake, so the socket and engine
 * callbacks are covered only where they name a peer; the release rules themselves are all below.
 */
class ClientKeyManagerTest {

    @Test
    fun `the Account's origin is the only peer the identity is released to`() {
        // The origin an Account builds for a URL that names no port: the port is resolved to the one
        // its scheme implies, which is the port a handshake always reports.
        val origin = DavAccount(label = "server", baseUrl = "https://dav.invalid/root/").origin
        assertEquals("https://dav.invalid:443", origin)
        val manager = ClientKeyManager(ALIAS, origin) { identity() }

        assertEquals(ALIAS, manager.select(RSA_KEY_TYPES, Peer("dav.invalid", 443)))
        // Another host, another port, or no peer at all is never handed the identity: a redirect
        // somewhere else must not receive it, and a peer the platform cannot name cannot be checked.
        assertNull(manager.select(RSA_KEY_TYPES, Peer("elsewhere.invalid", 443)))
        assertNull(manager.select(RSA_KEY_TYPES, Peer("dav.invalid", 80)))
        assertNull(manager.select(RSA_KEY_TYPES, Peer("dav.invalid", 8443)))
        assertNull(manager.select(RSA_KEY_TYPES, null))
        assertNull(ClientKeyManager(ALIAS, null) { identity() }.select(RSA_KEY_TYPES, Peer("dav.invalid", 443)))
        assertNull(ClientKeyManager(ALIAS, "not an origin") { identity() }.select(RSA_KEY_TYPES, Peer("dav.invalid", 443)))
    }

    @Test
    fun `a port the Account names is compared as it is written`() {
        val origin = DavAccount(label = "server", baseUrl = "https://dav.invalid:8443/root/").origin
        val manager = ClientKeyManager(ALIAS, origin) { identity() }

        assertEquals(ALIAS, manager.select(RSA_KEY_TYPES, Peer("dav.invalid", 8443)))
        assertNull(manager.select(RSA_KEY_TYPES, Peer("dav.invalid", 443)))
    }

    @Test
    fun `only a key type the server asked for is answered`() {
        val manager = ClientKeyManager(ALIAS, "https://dav.invalid:443") { identity() }

        // No list is the server restricting nothing, and refusing there would lose the certificate.
        assertEquals(ALIAS, manager.select(null, PEER))
        assertEquals(ALIAS, manager.select(emptyArray(), PEER))
        assertEquals(ALIAS, manager.select(arrayOf("RSA"), PEER))
        // A server offering only PSS signature schemes advertises the same RSA key under this name.
        assertEquals(ALIAS, manager.select(arrayOf("EC", "RSASSA-PSS"), PEER))
        assertNull(manager.select(arrayOf("EC"), PEER))
        assertNull(manager.select(arrayOf("DSA"), PEER))
    }

    @Test
    fun `the identity is read for each handshake rather than held`() {
        var installed: ClientIdentity? = null
        val manager = ClientKeyManager(ALIAS, "https://dav.invalid:443") { installed }

        assertNull(manager.select(RSA_KEY_TYPES, PEER))
        installed = identity()
        assertEquals(ALIAS, manager.select(RSA_KEY_TYPES, PEER))
        // An import or a removal applies to the next handshake, with no client to rebuild.
        installed = null
        assertNull(manager.select(RSA_KEY_TYPES, PEER))
    }

    @Test
    fun `the key and chain are served only under the alias this manager handed out`() {
        val identity = identity()
        val manager = ClientKeyManager(ALIAS, "https://dav.invalid:443") { identity }

        assertEquals(ALIAS, manager.select(RSA_KEY_TYPES, PEER))
        assertSame(identity.chain, manager.getCertificateChain(ALIAS))
        assertSame(identity.privateKey, manager.getPrivateKey(ALIAS))
        assertNull(manager.getCertificateChain("some-other-alias"))
        assertNull(manager.getPrivateKey("some-other-alias"))
        // An alias query names no peer, so it cannot be scoped to the origin and answers nothing.
        assertNull(manager.getClientAliases("RSA", null))
    }

    @Test
    fun `a certificate is recorded as offered when the alias callback fires, even if it refuses`() {
        val manager = ClientKeyManager(ALIAS, "https://dav.invalid:443") { identity() }
        assertFalse(manager.certificateOffered)

        // No socket, so no peer: the identity is withheld, but the request did ask, and that is what
        // the flag answers.
        assertNull(manager.chooseClientAlias(RSA_KEY_TYPES, null, null))

        assertTrue(manager.certificateOffered)
    }

    @Test
    fun `the engine callback records the offer too`() {
        val manager = ClientKeyManager(ALIAS, "https://dav.invalid:443") { identity() }

        assertNull(manager.chooseEngineClientAlias(RSA_KEY_TYPES, null, null))

        assertTrue(manager.certificateOffered)
    }

    @Test
    fun `no server-side query is ever answered`() {
        val manager = ClientKeyManager(ALIAS, "https://dav.invalid:443") { identity() }

        assertNull(manager.chooseServerAlias("RSA", null, null))
        assertNull(manager.getServerAliases("RSA", null))
        assertNull(manager.chooseEngineServerAlias("RSA", null, null))
    }
}

private const val ALIAS = "client-certificate"
private val PEER = Peer("dav.invalid", 443)
private val RSA_KEY_TYPES = arrayOf("RSA")

/**
 * The manager passes the chain through untouched and only reads the key's algorithm, so a key pair
 * with no chain is as much identity as the decisions above need.
 */
private fun identity(): ClientIdentity = ClientIdentity(privateKey, emptyArray<X509Certificate>())

private val privateKey: PrivateKey by lazy {
    KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private
}
