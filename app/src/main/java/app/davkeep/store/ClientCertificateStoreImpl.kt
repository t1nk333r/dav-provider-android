package app.davkeep.store

import android.accounts.Account
import android.content.Context
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import app.davkeep.core.CertificateImportResult
import app.davkeep.core.ClientCertificateInfo
import app.davkeep.core.ClientCertificateStore
import app.davkeep.core.ClientIdentity
import app.davkeep.core.CredentialStore
import app.davkeep.core.CredentialsUnreadableException

/**
 * The CredentialStore names an imported identity is held under.
 *
 * Credentials live in the userdata of one Account, so these names are already scoped to it and
 * nothing has to be encoded into them. Both are written and removed together, and
 * [AccountStore.delete] clears them with the Account's other Credentials.
 */
internal object ImportedCertificateSecrets {
    /** Base64 of the re-wrapped archive. */
    const val ARCHIVE: String = "certificate:pkcs12"

    /** The passphrase that archive was wrapped under, never the one the user typed. */
    const val PASSPHRASE: String = "certificate:passphrase"
}

/**
 * Holds the Account's imported PKCS#12 identity, re-wrapped under a passphrase generated here.
 *
 * Two rules shape it. The passphrase the user typed is never persisted: it is commonly reused for
 * other things, and an archive this app now holds is no reason to keep it. And a stored copy that
 * will not open is deleted rather than reported, because after a device restore the ciphertext
 * comes back without the Keystore key that made it, and an Account that failed on every request
 * forever would be worse than one whose certificate has to be imported again.
 *
 * Nothing here reports what it observed: the caller decides whether to ask for the archive again.
 */
class ClientCertificateStoreImpl(
    context: Context,
    private val credentials: CredentialStore = KeystoreCredentialStore(context),
) : ClientCertificateStore {

    private val random = SecureRandom()

    /**
     * Reads the archive with the passphrase the user supplied, then stores it under one of ours.
     *
     * The order matters: nothing is written until the user's own passphrase has been shown to open
     * the archive, so a failed import cannot replace an identity that works.
     */
    override fun import(account: Account, pkcs12: ByteArray, passphrase: CharArray): CertificateImportResult =
        when (val content = Pkcs12.read(pkcs12, passphrase)) {
            is Pkcs12Content.Identity -> {
                val generated = Pkcs12.newPassphrase(random)
                try {
                    val archive = Base64.encodeToString(Pkcs12.rewrap(content, generated), Base64.NO_WRAP)
                    credentials.put(account, ImportedCertificateSecrets.ARCHIVE, archive)
                    credentials.put(account, ImportedCertificateSecrets.PASSPHRASE, generated.concatToString())
                } finally {
                    // Our own passphrase is in the Keystore-sealed record now; this copy is not.
                    generated.fill('\u0000')
                }
                CertificateImportResult.Success(Pkcs12.describe(content.chain, System.currentTimeMillis()))
            }

            Pkcs12Content.WrongPassphrase -> CertificateImportResult.WrongPassphrase
            Pkcs12Content.NotAPkcs12 -> CertificateImportResult.NotAPkcs12
            Pkcs12Content.NoPrivateKey -> CertificateImportResult.NoPrivateKey
        }

    /**
     * What the stored archive says about itself. An expired certificate is reported as expired and
     * still returned: refusing it here would take the server's decision away from the server.
     */
    override fun info(account: Account): ClientCertificateInfo? =
        when (val content = readStored(account)) {
            null -> null
            is Pkcs12Content.Identity -> Pkcs12.describe(content.chain, System.currentTimeMillis())
            Pkcs12Content.WrongPassphrase, Pkcs12Content.NotAPkcs12, Pkcs12Content.NoPrivateKey ->
                forgetUnreadable(account)
        }

    override fun identity(account: Account): ClientIdentity? =
        when (val content = readStored(account)) {
            null -> null
            is Pkcs12Content.Identity -> ClientIdentity(content.privateKey, content.chain)
            Pkcs12Content.WrongPassphrase, Pkcs12Content.NotAPkcs12, Pkcs12Content.NoPrivateKey ->
                forgetUnreadable(account)
        }

    override fun remove(account: Account) {
        credentials.remove(account, ImportedCertificateSecrets.ARCHIVE)
        credentials.remove(account, ImportedCertificateSecrets.PASSPHRASE)
    }

    /**
     * The stored archive read back, or null when none is installed.
     *
     * A pair with only one half in place — or one the Keystore can no longer open, which is what a
     * restore from another device leaves behind — reads back as an unusable archive rather than as
     * "no certificate", so the caller drops both records instead of leaving half an identity behind.
     */
    private fun readStored(account: Account): Pkcs12Content? {
        val archive = secret(account, ImportedCertificateSecrets.ARCHIVE)
        val passphrase = secret(account, ImportedCertificateSecrets.PASSPHRASE)
        if (archive is Secret.Absent && passphrase is Secret.Absent) return null
        if (archive !is Secret.Value || passphrase !is Secret.Value) return Pkcs12Content.NotAPkcs12
        val bytes = try {
            Base64.decode(archive.text, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            return Pkcs12Content.NotAPkcs12
        }
        val chars = passphrase.text.toCharArray()
        return try {
            Pkcs12.read(bytes, chars)
        } finally {
            chars.fill('\u0000')
        }
    }

    /** What the CredentialStore had to say: nothing stored, a value, or a value that will not open. */
    private fun secret(account: Account, key: String): Secret = try {
        credentials.get(account, key)?.let { Secret.Value(it) } ?: Secret.Absent
    } catch (e: CredentialsUnreadableException) {
        Secret.Unreadable
    }

    /**
     * Drops a copy that will not open. Keeping it would fail every request from now on, and the one
     * thing the user can do about it — import the archive again — needs those records gone.
     */
    private fun forgetUnreadable(account: Account): Nothing? {
        remove(account)
        return null
    }

    private sealed interface Secret {
        data class Value(val text: String) : Secret
        data object Absent : Secret
        data object Unreadable : Secret
    }
}

/** What a PKCS#12 holds, before anything is said to the user about it. */
internal sealed interface Pkcs12Content {
    /** The first key entry of the archive, with the chain that goes with it. */
    data class Identity(val privateKey: PrivateKey, val chain: Array<X509Certificate>) : Pkcs12Content

    /** Well-formed DER that would not load: PKCS#12 integrity is a MAC over the passphrase. */
    data object WrongPassphrase : Pkcs12Content

    /** Not a DER SEQUENCE, or not a PKCS#12 at all. */
    data object NotAPkcs12 : Pkcs12Content

    /** An archive of certificates, or a key entry with no chain: nothing can authenticate with it. */
    data object NoPrivateKey : Pkcs12Content
}

/**
 * Reading and re-writing PKCS#12 archives, in terms no Android type appears in, so that the
 * discrimination between a wrong passphrase and the wrong file can be exercised off-device.
 */
internal object Pkcs12 {

    /** The first byte of every DER SEQUENCE, and so the first byte of every PKCS#12. */
    private const val DER_SEQUENCE = 0x30.toByte()

    private const val TYPE = "PKCS12"

    /** The alias the re-wrapped archive uses: the user's aliases name nothing here. */
    private const val ALIAS = "client"

    private const val PASSPHRASE_LENGTH = 32
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /**
     * The archive's first key entry, or why there is none.
     *
     * The structural check comes first and is deliberately not a passphrase judgement: a file that
     * is not DER cannot be a PKCS#12 whatever passphrase is tried, and telling the user their
     * passphrase was wrong would send them hunting for a typo in a file of the wrong kind.
     */
    fun read(archive: ByteArray, passphrase: CharArray): Pkcs12Content {
        if (archive.isEmpty() || archive[0] != DER_SEQUENCE) return Pkcs12Content.NotAPkcs12
        val keyStore = try {
            KeyStore.getInstance(TYPE).apply { load(ByteArrayInputStream(archive), passphrase) }
        } catch (e: IOException) {
            // Well-formed DER that will not load: the only thing that can be wrong by now is the
            // passphrase, because the archive's integrity check is a MAC over it.
            return Pkcs12Content.WrongPassphrase
        } catch (e: GeneralSecurityException) {
            return Pkcs12Content.NotAPkcs12
        }
        return keyStore.identity(passphrase)
    }

    /**
     * Rebuilds the archive under [passphrase]. It is rebuilt rather than copied, so nothing else
     * the user's file carried — a second identity, attributes, a weak MAC — is carried into the
     * copy this app is then responsible for.
     */
    fun rewrap(identity: Pkcs12Content.Identity, passphrase: CharArray): ByteArray {
        val keyStore = KeyStore.getInstance(TYPE).apply { load(null, passphrase) }
        keyStore.setKeyEntry(ALIAS, identity.privateKey, passphrase, identity.chain)
        return ByteArrayOutputStream().use { out ->
            keyStore.store(out, passphrase)
            out.toByteArray()
        }
    }

    /**
     * A fresh passphrase for an archive this app stores.
     *
     * Printable ASCII, because the PKCS#12 encoders on both the platform and the JDK refuse
     * anything else and would refuse to write the archive at all. Thirty-two characters of a
     * 64-symbol alphabet is 192 bits, which is far past what a MAC over a locally stored archive
     * could be attacked with.
     */
    fun newPassphrase(random: SecureRandom): CharArray =
        CharArray(PASSPHRASE_LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }

    /**
     * What the archive says about itself, taken from the leaf certificate — the one a server sees.
     * Expiry is reported, never enforced: only the server knows whether it still accepts one.
     */
    fun describe(chain: Array<X509Certificate>, now: Long): ClientCertificateInfo {
        val leaf = chain.first()
        return ClientCertificateInfo(
            subject = leaf.subjectX500Principal.name,
            issuer = leaf.issuerX500Principal.name,
            notAfter = leaf.notAfter.time,
            expired = leaf.notAfter.time <= now,
        )
    }

    /**
     * The first key entry wins: an archive can hold several identities, and the user chose this
     * file, not an alias inside it.
     */
    private fun KeyStore.identity(passphrase: CharArray): Pkcs12Content {
        val alias = aliases().toList().firstOrNull { isKeyEntry(it) } ?: return Pkcs12Content.NoPrivateKey
        val key = try {
            getKey(alias, passphrase)
        } catch (e: GeneralSecurityException) {
            // The MAC verified, so this is not the passphrase's fault: the entry itself is unusable.
            return Pkcs12Content.NotAPkcs12
        }
        if (key !is PrivateKey) return Pkcs12Content.NoPrivateKey
        val chain = getCertificateChain(alias)?.filterIsInstance<X509Certificate>().orEmpty()
        if (chain.isEmpty()) return Pkcs12Content.NoPrivateKey
        return Pkcs12Content.Identity(key, chain.toTypedArray())
    }
}
