package xyz.satr.davprovider.net

import android.content.Context
import android.security.KeyChain
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xyz.satr.davprovider.core.ClientIdentity

/**
 * The Account's client certificate is gone: the certificate was deleted, the grant revoked, or the
 * Keystore key was dropped by a device restore. Declared here rather than in the shared contracts
 * because it is a fact about the KeyChain, not about sync.
 *
 * [alias] is carried — and the Account keeps it — so the user can see which selection is broken and
 * reselect it. Clearing the alias would turn a recoverable misconfiguration into an Account that
 * looks unconfigured.
 */
class ClientCertificateUnavailableException(val alias: String, cause: Throwable?) :
    Exception("The selected certificate is no longer available — reselect it.", cause)

/**
 * The alias an imported identity is offered under. Nothing outside this manager ever sees it: the
 * archive is read here, so there is no name for anything else to resolve.
 */
internal const val IMPORTED_ALIAS = "imported-client"

/**
 * Reads the Account's key pair out of the KeyChain.
 *
 * Eager on purpose: a certificate that cannot be read must fail where it can be reported, not as an
 * opaque TLS handshake failure half way through a sync. The calls are a blocking round trip to the
 * KeyChain service, so this must not run on the main thread, and reading once here is also what
 * keeps that round trip off every handshake.
 *
 * @throws ClientCertificateUnavailableException when [alias] resolves to nothing usable.
 */
internal fun loadKeyChainKeyManager(context: Context, alias: String, origin: String?): ClientKeyManager {
    val privateKey = try {
        KeyChain.getPrivateKey(context, alias)
    } catch (e: Exception) {
        throw ClientCertificateUnavailableException(alias, e)
    }
    val chain = try {
        KeyChain.getCertificateChain(context, alias)
    } catch (e: Exception) {
        throw ClientCertificateUnavailableException(alias, e)
    }
    if (privateKey == null || chain.isNullOrEmpty()) throw ClientCertificateUnavailableException(alias, null)
    val identity = ClientIdentity(privateKey, chain)
    return ClientKeyManager(alias, origin) { identity }
}

/**
 * Presents the Account's client identity to the TLS stack — to its own origin and to nothing else.
 *
 * The identity is a lookup rather than a value, so an archive imported or removed while the app is
 * running takes effect on the next handshake instead of only after the HTTP client is rebuilt. It
 * is read inside the callback because that is the only place the peer is known: configuration can
 * say which host the Account was set up for, but only the handshake says who is on the other end,
 * and a redirect to another host must never be handed the Account's identity.
 */
internal class ClientKeyManager(
    /** Handed back to the TLS stack as the chosen alias; it names nothing outside this manager. */
    private val alias: String,
    /** The `scheme://host:port` this identity may be released to. Null refuses every peer. */
    private val origin: String?,
    private val identity: () -> ClientIdentity?,
) : X509ExtendedKeyManager() {

    private val offered = AtomicBoolean(false)

    /** The identity of the handshake in flight, so its chain and key queries read the archive once. */
    private val chosen = AtomicReference<ClientIdentity?>(null)

    /**
     * Whether the TLS stack asked for a client alias, set from inside the callback and never inferred
     * from configuration: "a certificate is configured" and "a certificate was offered to the server"
     * are different claims, and only the second one is evidence.
     */
    val certificateOffered: Boolean get() = offered.get()

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? {
        // The observation is the callback firing, so the flag is set on entry, before deciding.
        offered.set(true)
        return select(keyType, peerOf(socket))
    }

    // OkHttp hands TLS to a socket, but an engine-based handshake must not silently lose the
    // certificate, and the platform default returns null there.
    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? {
        offered.set(true)
        return select(keyType, peerOf(engine))
    }

    /**
     * The decision, given the peer the handshake is with. Takes the peer as an argument so the two
     * rules that carry the risk — release to this origin only, answer only for a key type the server
     * asked for — are readable and testable without a live handshake.
     */
    fun select(keyTypes: Array<out String>?, peer: Peer?): String? {
        val identity = identity() ?: return null
        if (!originAllows(origin, peer)) return null
        if (!accepts(keyTypes, identity.privateKey)) return null
        chosen.set(identity)
        return alias
    }

    // An alias query carries no peer, so it cannot be scoped to the origin, and an unscoped answer
    // is the one thing this manager must not give. Client authentication goes through the callbacks
    // above, which do carry the peer.
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == this.alias) chosen.get()?.chain else null

    override fun getPrivateKey(alias: String?): PrivateKey? =
        if (alias == this.alias) chosen.get()?.privateKey else null

    // One Account is one client, so there is no server side to answer for.
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun chooseEngineServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = null
}

/** The peer a handshake is with, as the TLS session reports it. */
internal data class Peer(val host: String, val port: Int)

/**
 * Whether [origin] — an Account's own `scheme://host:port` — names [peer].
 *
 * The port is compared, not just the host: another port on the same host is another origin as far
 * as this decision goes. It is also what tells `https://host` from `http://host`, since the origin
 * now carries the port its scheme implies.
 */
internal fun originAllows(origin: String?, peer: Peer?): Boolean {
    if (origin == null || peer == null) return false
    val expected = origin.toHttpUrlOrNull() ?: return false
    val host = expected.host.trim('[', ']')
    return host.equals(peer.host.trim('[', ']'), ignoreCase = true) && expected.port == peer.port
}

/**
 * Whether the identity's key is one the server asked for.
 *
 * An empty list is the server not restricting the key type, and refusing there would lose the
 * certificate on servers that simply ask for whatever the client has.
 */
internal fun accepts(keyTypes: Array<out String>?, key: PrivateKey): Boolean {
    if (keyTypes.isNullOrEmpty()) return true
    return keyTypes.any { type ->
        val asked = type.uppercase(Locale.ROOT)
        asked == keyTypeOf(key.algorithm) || (asked == PSS && keyTypeOf(key.algorithm) == RSA)
    }
}

private const val RSA = "RSA"
private const val PSS = "RSASSA-PSS"

/**
 * JSSE names key types by algorithm; RSA and EC are what a client certificate holds in practice.
 * A server that offers only PSS signature schemes — TLS 1.3 commonly does — advertises the very
 * same RSA key as `RSASSA-PSS`, so that name is answered by [accepts] and not mapped here.
 */
private fun keyTypeOf(algorithm: String): String = when (algorithm.uppercase(Locale.ROOT)) {
    RSA -> RSA
    "EC", "ECDSA" -> "EC"
    "DSA" -> "DSA"
    else -> algorithm.uppercase(Locale.ROOT)
}

/**
 * The peer of a client-authentication handshake, or null when the platform will not name it.
 *
 * Read from the handshake session rather than the socket: the session outlives the handshake and
 * can be reported for a connection that is already established, while a null answer here can be
 * refused honestly — releasing an identity to a peer we cannot name is what the origin rule exists
 * to prevent.
 */
private fun peerOf(socket: Socket?): Peer? {
    val session = try {
        (socket as? SSLSocket)?.handshakeSession
    } catch (e: UnsupportedOperationException) {
        null
    }
    return peerOf(session)
}

private fun peerOf(engine: SSLEngine?): Peer? {
    val session = try {
        engine?.handshakeSession
    } catch (e: UnsupportedOperationException) {
        null
    }
    return peerOf(session)
}

private fun peerOf(session: SSLSession?): Peer? {
    val host = session?.peerHost ?: return null
    val port = session.peerPort
    if (port <= 0) return null
    return Peer(host, port)
}

/** A socket factory that offers [keyManager] and verifies the server with [trustManager]. */
internal fun sslSocketFactory(keyManager: X509KeyManager, trustManager: X509TrustManager): SSLSocketFactory {
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(arrayOf<KeyManager>(keyManager), arrayOf<TrustManager>(trustManager), null)
    return sslContext.socketFactory
}

/**
 * The platform default trust manager. Installing a KeyManager must not weaken server verification,
 * and the server's own certificate is not something an Account configures.
 */
internal fun platformTrustManager(): X509TrustManager {
    val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
    factory.init(null as KeyStore?)
    return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
}
