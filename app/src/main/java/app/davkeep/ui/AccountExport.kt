package app.davkeep.ui

import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject
import app.davkeep.core.ClientCertificateSource
import app.davkeep.core.CollectionType
import app.davkeep.core.DavAccount
import app.davkeep.core.DavCollection
import app.davkeep.core.DavHeader

/**
 * Every Account in one passphrase-encrypted file, secrets included, through the system file picker.
 *
 * The on-device store is deliberately not portable — ADR-0001 wraps credentials in a Keystore key
 * that a device-to-device restore does not carry over — so this file is the copy that can be
 * carried to another device, and it is the reason the passphrase exists rather than the Keystore.
 *
 * The certificate is carried as far as it can be. An **imported** identity travels whole: the
 * re-wrapped archive and the passphrase that opens it are written beside the Account, so a restored
 * device can present it without the file being picked and opened again. A **KeyChain** identity
 * travels as the alias alone, because that is all this app ever holds — the key stays in the system
 * Keystore of the device that has it, so the alias is restored but resolves to nothing after a
 * restore, which is precisely the failure the imported route exists to fix. What is *not* carried
 * anywhere is the user's own file passphrase: it is used once, at import, and never stored.
 *
 * The envelope is plain JSON so the reader can refuse an unknown `formatVersion` *before* being
 * asked for a passphrase; nothing is guessed from a file, and an unknown version is refused whole.
 * The payload names the key-derivation algorithm and its iteration count so that raising the count
 * later stays readable.
 */
internal object AccountExport {

    /** Written by this build. Raised from 1 when the imported identity joined the payload. */
    const val FORMAT_VERSION = 2

    /**
     * Versions this build reads. v1 predates the imported identity, so its Accounts simply have no
     * certificate of their own; reading it is not a guess. Refusing it would strand the export a
     * restored device depends on, which is the one file that must survive an upgrade.
     */
    private val READABLE_VERSIONS = setOf(1, FORMAT_VERSION)

    /**
     * The app-held half of an imported identity, verbatim as the CredentialStore holds it: the
     * archive this app re-wrapped, and the passphrase that opens that copy of it. Both rank with a
     * password, so both only ever appear inside the encrypted payload.
     */
    data class ImportedCertificate(val archive: String, val passphrase: String)

    /** One Account, and the imported identity that has to be put back beside it on restore. */
    data class Entry(val account: DavAccount, val importedCertificate: ImportedCertificate?)

    /**
     * PBKDF2-HMAC-SHA1 is the strongest PBKDF2 this app's minimum platform ships; its security
     * here rests on HMAC, and the iteration count, not the hash, is what costs an attacker.
     */
    private const val KDF = "PBKDF2WithHmacSHA1"
    private const val ITERATIONS = 210_000
    private const val CIPHER = "AES/GCM/NoPadding"
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12

    /** Written by a format this build does not know. [version] is what the file declared. */
    class UnknownFormatVersionException(val version: Int) :
        Exception("Unknown export format version $version")

    /** Wrong passphrase or a file altered after it was written; GCM cannot tell the two apart. */
    class WrongPassphraseException(cause: Throwable?) :
        Exception("The passphrase does not open this file", cause)

    /**
     * [certificates] holds the app-held half of each imported identity, keyed by Account label —
     * the same key the file's Accounts are keyed on, so an archive cannot land on another Account.
     */
    fun encode(
        accounts: List<DavAccount>,
        certificates: Map<String, ImportedCertificate>,
        passphrase: CharArray,
    ): String {
        val salt = randomBytes(SALT_BYTES)
        val iv = randomBytes(IV_BYTES)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, ITERATIONS), GCMParameterSpec(TAG_BITS, iv))
        val sealed = cipher.doFinal(payload(accounts, certificates).toByteArray(Charsets.UTF_8))
        return JSONObject().apply {
            put(KEY_FORMAT_VERSION, FORMAT_VERSION)
            put(KEY_KDF, KDF)
            put(KEY_ITERATIONS, ITERATIONS)
            put(KEY_CIPHER, CIPHER)
            put(KEY_SALT, base64(salt))
            put(KEY_IV, base64(iv))
            put(KEY_PAYLOAD, base64(sealed))
        }.toString(2)
    }

    fun decode(file: String, passphrase: CharArray): List<Entry> {
        val envelope = JSONObject(file)
        val version = envelope.optInt(KEY_FORMAT_VERSION, -1)
        if (version !in READABLE_VERSIONS) throw UnknownFormatVersionException(version)
        if (envelope.optString(KEY_KDF) != KDF || envelope.optString(KEY_CIPHER) != CIPHER) {
            throw UnknownFormatVersionException(version)
        }
        val salt = unBase64(envelope.getString(KEY_SALT))
        val iv = unBase64(envelope.getString(KEY_IV))
        val sealed = unBase64(envelope.getString(KEY_PAYLOAD))
        val iterations = envelope.optInt(KEY_ITERATIONS, ITERATIONS)
        val cleartext = try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(sealed)
        } catch (e: AEADBadTagException) {
            throw WrongPassphraseException(e)
        }
        return accounts(JSONObject(String(cleartext, Charsets.UTF_8)), version)
    }

    private fun payload(accounts: List<DavAccount>, certificates: Map<String, ImportedCertificate>): String {
        val array = JSONArray()
        accounts.forEach { account ->
            array.put(
                JSONObject().apply {
                    put(KEY_LABEL, account.label)
                    put(KEY_BASE_URL, account.baseUrl)
                    account.certificate?.let { put(KEY_CERTIFICATE, certificate(it)) }
                    certificates[account.label]?.let { importedCertificate ->
                        put(KEY_CERTIFICATE_ARCHIVE, importedCertificate.archive)
                        put(KEY_CERTIFICATE_PASSPHRASE, importedCertificate.passphrase)
                    }
                    account.username?.let { put(KEY_USERNAME, it) }
                    // The secret fields are the point of the passphrase.
                    account.password?.let { put(KEY_PASSWORD, it) }
                    put(
                        KEY_HEADERS,
                        JSONArray().apply {
                            account.headers.forEach { header ->
                                put(JSONObject().apply {
                                    put(KEY_HEADER_NAME, header.name)
                                    put(KEY_HEADER_VALUE, header.value)
                                })
                            }
                        },
                    )
                    put(
                        KEY_COLLECTIONS,
                        JSONArray().apply {
                            account.collections.forEach { collection ->
                                put(
                                    JSONObject().apply {
                                        put(KEY_COLLECTION_ID, collection.id)
                                        put(KEY_COLLECTION_URL, collection.url)
                                        put(KEY_COLLECTION_TYPE, collection.type.name)
                                        collection.displayName?.let { put(KEY_DISPLAY_NAME, it) }
                                        collection.color?.let { put(KEY_COLOR, it) }
                                        put(KEY_SELECTED, collection.selected)
                                        put(KEY_AVAILABLE, collection.available)
                                        put(KEY_PINNED, collection.pinned)
                                    },
                                )
                            }
                        },
                    )
                },
            )
        }
        return JSONObject().apply {
            put(KEY_FORMAT_VERSION, FORMAT_VERSION)
            put(KEY_ACCOUNTS, array)
        }.toString()
    }

    private fun accounts(root: JSONObject, version: Int): List<Entry> {
        val array = root.optJSONArray(KEY_ACCOUNTS) ?: JSONArray()
        val entries = ArrayList<Entry>(array.length())
        for (index in 0 until array.length()) {
            val account = array.getJSONObject(index)
            // Header order is position, not name: two headers may share a name.
            val headers = account.optJSONArray(KEY_HEADERS) ?: JSONArray()
            val headerList = ArrayList<DavHeader>(headers.length())
            for (position in 0 until headers.length()) {
                val header = headers.getJSONObject(position)
                headerList += DavHeader(header.getString(KEY_HEADER_NAME), header.optString(KEY_HEADER_VALUE))
            }
            val collections = account.optJSONArray(KEY_COLLECTIONS) ?: JSONArray()
            val collectionList = ArrayList<DavCollection>(collections.length())
            for (position in 0 until collections.length()) {
                val collection = collections.getJSONObject(position)
                collectionList += DavCollection(
                    id = collection.getString(KEY_COLLECTION_ID),
                    url = collection.getString(KEY_COLLECTION_URL),
                    type = CollectionType.valueOf(collection.getString(KEY_COLLECTION_TYPE)),
                    displayName = collection.optString(KEY_DISPLAY_NAME).takeIf { it.isNotEmpty() },
                    color = if (collection.has(KEY_COLOR)) collection.getInt(KEY_COLOR) else null,
                    selected = collection.optBoolean(KEY_SELECTED, false),
                    available = collection.optBoolean(KEY_AVAILABLE, true),
                    pinned = collection.optBoolean(KEY_PINNED, false),
                )
            }
            entries += Entry(
                account = DavAccount(
                    label = account.getString(KEY_LABEL),
                    baseUrl = account.getString(KEY_BASE_URL),
                    headers = headerList,
                    certificate = certificate(account, version),
                    username = account.optString(KEY_USERNAME).takeIf { it.isNotEmpty() },
                    password = account.optString(KEY_PASSWORD).takeIf { it.isNotEmpty() },
                    collections = collectionList,
                ),
                importedCertificate = importedCertificate(account),
            )
        }
        return entries
    }

    /**
     * The Account's certificate as the payload declares it.
     *
     * A v1 file predates the imported identity and named a KeyChain alias under its own key, so that
     * key is read for a v1 file — dropping it would silently turn a configured Account into an
     * unconfigured one. The field is not a guess: it is what this app itself wrote at version 1.
     */
    private fun certificate(account: JSONObject, version: Int): ClientCertificateSource? {
        val fields = account.optJSONObject(KEY_CERTIFICATE)
        if (fields == null) {
            if (version >= FORMAT_VERSION) return null
            return account.optString(KEY_LEGACY_CERT_ALIAS).takeIf { it.isNotEmpty() }
                ?.let { ClientCertificateSource.KeyChainAlias(it) }
        }
        val source = fields.optString(KEY_SOURCE)
        return when (source) {
            SOURCE_KEYCHAIN -> ClientCertificateSource.KeyChainAlias(
                fields.optString(KEY_CERT_ALIAS).takeIf { it.isNotEmpty() }
                    ?: error("A KeyChain certificate with no alias"),
            )

            SOURCE_IMPORTED -> ClientCertificateSource.Imported
            // Never guessed: an identity is either one this build knows or the file is refused.
            else -> error("Unknown certificate source \"$source\"")
        }
    }

    private fun importedCertificate(account: JSONObject): ImportedCertificate? {
        val archive = account.optString(KEY_CERTIFICATE_ARCHIVE)
        val passphrase = account.optString(KEY_CERTIFICATE_PASSPHRASE)
        if (archive.isEmpty() && passphrase.isEmpty()) return null
        // Half an archive is not half an identity: both halves are written, or neither is.
        require(archive.isNotEmpty() && passphrase.isNotEmpty()) { "An imported certificate is missing a half" }
        return ImportedCertificate(archive, passphrase)
    }

    private fun certificate(source: ClientCertificateSource): JSONObject = JSONObject().apply {
        when (source) {
            is ClientCertificateSource.KeyChainAlias -> {
                put(KEY_SOURCE, SOURCE_KEYCHAIN)
                put(KEY_CERT_ALIAS, source.alias)
            }

            ClientCertificateSource.Imported -> put(KEY_SOURCE, SOURCE_IMPORTED)
        }
    }

    private fun key(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        val secret = try {
            SecretKeyFactory.getInstance(KDF).generateSecret(spec)
        } finally {
            spec.clearPassword()
        }
        return SecretKeySpec(secret.encoded, "AES")
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun unBase64(text: String): ByteArray = Base64.getDecoder().decode(text)

    private const val KEY_FORMAT_VERSION = "formatVersion"
    private const val KEY_KDF = "kdf"
    private const val KEY_ITERATIONS = "iterations"
    private const val KEY_CIPHER = "cipher"
    private const val KEY_SALT = "salt"
    private const val KEY_IV = "iv"
    private const val KEY_PAYLOAD = "payload"
    private const val KEY_ACCOUNTS = "accounts"
    private const val KEY_LABEL = "label"
    private const val KEY_BASE_URL = "baseUrl"
    private const val KEY_CERTIFICATE = "certificate"
    private const val KEY_SOURCE = "source"
    private const val SOURCE_KEYCHAIN = "keychain"
    private const val SOURCE_IMPORTED = "imported"
    private const val KEY_CERT_ALIAS = "alias"
    /** What version 1 called a KeyChain alias, before an Account could hold an identity of its own. */
    private const val KEY_LEGACY_CERT_ALIAS = "certAlias"
    private const val KEY_CERTIFICATE_ARCHIVE = "certificateArchive"
    private const val KEY_CERTIFICATE_PASSPHRASE = "certificatePassphrase"
    private const val KEY_USERNAME = "username"
    private const val KEY_PASSWORD = "password"
    private const val KEY_HEADERS = "headers"
    private const val KEY_HEADER_NAME = "name"
    private const val KEY_HEADER_VALUE = "value"
    private const val KEY_COLLECTIONS = "collections"
    private const val KEY_COLLECTION_ID = "id"
    private const val KEY_COLLECTION_URL = "url"
    private const val KEY_COLLECTION_TYPE = "type"
    private const val KEY_DISPLAY_NAME = "displayName"
    private const val KEY_COLOR = "color"
    private const val KEY_SELECTED = "selected"
    private const val KEY_AVAILABLE = "available"
    private const val KEY_PINNED = "pinned"
}
