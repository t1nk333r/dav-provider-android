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
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

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
 * Reads the Account's key pair out of the KeyChain.
 *
 * Eager on purpose: a certificate that cannot be read must fail where it can be reported, not as an
 * opaque TLS handshake failure half way through a sync. The calls are a blocking round trip to the
 * KeyChain service, so this must not run on the main thread.
 *
 * @throws ClientCertificateUnavailableException when [alias] resolves to nothing usable.
 */
internal fun loadKeyChainKeyManager(context: Context, alias: String): KeyChainKeyManager {
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
    return KeyChainKeyManager(alias, privateKey, chain)
}

/**
 * Presents the Account's KeyChain key pair to the TLS stack. The private key stays where it was:
 * it is fetched per client and never persisted by the app.
 */
internal class KeyChainKeyManager(
    private val alias: String,
    private val privateKey: PrivateKey,
    private val chain: Array<X509Certificate>,
) : X509ExtendedKeyManager() {

    private val offered = AtomicBoolean(false)

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
        return if (matches(keyType)) alias else null
    }

    // OkHttp hands TLS to a socket, but an engine-based handshake must not silently lose the
    // certificate, and the platform default returns null there.
    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = chooseClientAlias(keyType, issuers, null)

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        if (matches(keyType)) arrayOf(alias) else null

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        if (alias == this.alias) chain else null

    override fun getPrivateKey(alias: String?): PrivateKey? =
        if (alias == this.alias) privateKey else null

    // One Account is one client, so there is no server side to answer for.
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun chooseEngineServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = null

    /** A server asking for a key type we do not hold must not be handed this certificate. */
    private fun matches(keyTypes: Array<out String>?): Boolean =
        keyTypes.isNullOrEmpty() || keyTypes.any { it.uppercase(Locale.ROOT) == keyTypeOf(privateKey.algorithm) }

    private fun matches(keyType: String?): Boolean =
        keyType == null || keyType.uppercase(Locale.ROOT) == keyTypeOf(privateKey.algorithm)
}

/** JSSE names key types by algorithm; RSA and EC are what the KeyChain holds in practice. */
private fun keyTypeOf(algorithm: String): String = when (algorithm.uppercase(Locale.ROOT)) {
    "RSA" -> "RSA"
    "EC", "ECDSA" -> "EC"
    "DSA" -> "DSA"
    else -> algorithm
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
