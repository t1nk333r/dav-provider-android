package xyz.satr.davprovider.ui

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
import xyz.satr.davprovider.core.CollectionType
import xyz.satr.davprovider.core.DavAccount
import xyz.satr.davprovider.core.DavCollection
import xyz.satr.davprovider.core.DavHeader

/**
 * Every Account in one passphrase-encrypted file, secrets included, through the system file picker.
 *
 * The on-device store is deliberately not portable — ADR-0001 wraps credentials in a Keystore key
 * that a device-to-device restore does not carry over — so this file is the copy that can be
 * carried to another device, and it is the reason the passphrase exists rather than the Keystore.
 *
 * The envelope is plain JSON so the reader can refuse an unknown `formatVersion` *before* being
 * asked for a passphrase; nothing is guessed from a file, and an unknown version is refused whole.
 * The payload names the key-derivation algorithm and its iteration count so that raising the count
 * later stays readable.
 */
internal object AccountExport {

    const val FORMAT_VERSION = 1

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

    fun encode(accounts: List<DavAccount>, passphrase: CharArray): String {
        val salt = randomBytes(SALT_BYTES)
        val iv = randomBytes(IV_BYTES)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, ITERATIONS), GCMParameterSpec(TAG_BITS, iv))
        val sealed = cipher.doFinal(payload(accounts).toByteArray(Charsets.UTF_8))
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

    fun decode(file: String, passphrase: CharArray): List<DavAccount> {
        val envelope = JSONObject(file)
        val version = envelope.optInt(KEY_FORMAT_VERSION, -1)
        if (version != FORMAT_VERSION) throw UnknownFormatVersionException(version)
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
        return accounts(JSONObject(String(cleartext, Charsets.UTF_8)))
    }

    private fun payload(accounts: List<DavAccount>): String {
        val array = JSONArray()
        accounts.forEach { account ->
            array.put(
                JSONObject().apply {
                    put(KEY_LABEL, account.label)
                    put(KEY_BASE_URL, account.baseUrl)
                    account.certAlias?.let { put(KEY_CERT_ALIAS, it) }
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

    private fun accounts(root: JSONObject): List<DavAccount> {
        val array = root.optJSONArray(KEY_ACCOUNTS) ?: JSONArray()
        val accounts = ArrayList<DavAccount>(array.length())
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
                )
            }
            accounts += DavAccount(
                label = account.getString(KEY_LABEL),
                baseUrl = account.getString(KEY_BASE_URL),
                headers = headerList,
                certAlias = account.optString(KEY_CERT_ALIAS).takeIf { it.isNotEmpty() },
                username = account.optString(KEY_USERNAME).takeIf { it.isNotEmpty() },
                password = account.optString(KEY_PASSWORD).takeIf { it.isNotEmpty() },
                collections = collectionList,
            )
        }
        return accounts
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
    private const val KEY_CERT_ALIAS = "certAlias"
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
}
