package xyz.satr.davprovider.store

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import xyz.satr.davprovider.core.ACCOUNT_TYPE
import xyz.satr.davprovider.core.CredentialStore
import xyz.satr.davprovider.core.CredentialsUnreadableException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Credentials encrypted with an Android Keystore key, kept as ciphertext in the account's userdata
 * (ADR-0001).
 *
 * The account record therefore stays the single source of truth and the key never leaves the
 * Keystore, so a Credential can be replaced but never revealed. The threat model is a lost or
 * stolen device and the software on it, not an attacker with the device unlocked.
 *
 * One key serves the whole app instead of one key per Account: nothing in a key is account-specific,
 * and per-account aliases would only multiply the ways to lose one. The consequence, spelled out in
 * [AccountManagerAccountStore.delete], is that removing an Account must not delete the key, because
 * every other Account's Credentials are sealed with it.
 */
class KeystoreCredentialStore(context: Context) : CredentialStore {

    private val accountManager: AccountManager = AccountManager.get(context.applicationContext)

    private val keyLock = Any()
    private val indexLock = Any()

    /** Resolved once; the Keystore still does the crypto, this only skips re-reading the entry. */
    @Volatile private var resolvedKey: SecretKey? = null

    override fun put(account: Account, key: String, secret: String) {
        require(SEPARATOR !in key) { "A credential name cannot be indexed: $key" }
        val blob = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keyForEncryption())
            seal(cipher, secret.toByteArray(Charsets.UTF_8))
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("Could not encrypt a credential for ${account.name}", e)
        }
        accountManager.setUserData(account, userDataKey(key), Base64.encodeToString(blob, Base64.NO_WRAP))
        remember(account, key)
    }

    override fun get(account: Account, key: String): String? {
        val encoded = accountManager.getUserData(account, userDataKey(key)) ?: return null
        val blob = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw CredentialsUnreadableException(e)
        }
        if (blob.size <= IV_LENGTH) throw CredentialsUnreadableException(null)
        return try {
            // After a device-to-device restore the ciphertext comes back without the key that made
            // it, so there is no key to decrypt with: unreadable, not absent.
            val secretKey = resolvedKeyOrNull() ?: throw CredentialsUnreadableException(null)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey,
                GCMParameterSpec(TAG_LENGTH_BITS, blob, 0, IV_LENGTH),
            )
            String(cipher.doFinal(blob, IV_LENGTH, blob.size - IV_LENGTH), Charsets.UTF_8)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw CredentialsUnreadableException(e)
        } catch (e: AEADBadTagException) {
            throw CredentialsUnreadableException(e)
        } catch (e: UnrecoverableKeyException) {
            throw CredentialsUnreadableException(e)
        } catch (e: GeneralSecurityException) {
            // Whatever else the Keystore refuses here, it means the same thing to the user.
            throw CredentialsUnreadableException(e)
        }
    }

    override fun remove(account: Account, key: String) {
        accountManager.setUserData(account, userDataKey(key), null)
        forget(account, key)
    }

    /**
     * Removes every Credential of [account] and no other account's. The names are indexed beside
     * the ciphertext because userdata cannot be enumerated, and only that index makes an exact
     * removal possible. The Keystore key is app-wide and deliberately left alone.
     */
    override fun clear(account: Account) {
        synchronized(indexLock) {
            for (key in heldKeys(account)) accountManager.setUserData(account, userDataKey(key), null)
            accountManager.setUserData(account, INDEX_KEY, null)
        }
    }

    private fun remember(account: Account, key: String) {
        synchronized(indexLock) {
            val held = heldKeys(account)
            if (key in held) return
            accountManager.setUserData(account, INDEX_KEY, (held + key).joinToString(SEPARATOR))
        }
    }

    private fun forget(account: Account, key: String) {
        synchronized(indexLock) {
            val remaining = heldKeys(account).filterNot { it == key }
            accountManager.setUserData(
                account,
                INDEX_KEY,
                remaining.joinToString(SEPARATOR).ifEmpty { null },
            )
        }
    }

    private fun heldKeys(account: Account): List<String> =
        accountManager.getUserData(account, INDEX_KEY)
            ?.split(SEPARATOR)
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    private fun resolvedKeyOrNull(): SecretKey? = synchronized(keyLock) {
        resolvedKey ?: readKey()?.also { resolvedKey = it }
    }

    private fun keyForEncryption(): SecretKey = synchronized(keyLock) {
        resolvedKeyOrNull() ?: generateKey().also { resolvedKey = it }
    }

    private fun readKey(): SecretKey? = keyStore().getKey(ALIAS, null) as? SecretKey

    private fun generateKey(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_LENGTH_BITS)
                    // Fresh IV per encryption, chosen by the cipher: a repeated GCM IV is fatal.
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null as KeyStore.LoadStoreParameter?) }

    /**
     * `iv ‖ ciphertext ‖ tag`, which is exactly what the userdata string holds, Base64-encoded.
     * The IV is not secret but has to survive, and it is always [IV_LENGTH] bytes for GCM here.
     */
    private fun seal(cipher: Cipher, plaintext: ByteArray): ByteArray {
        val iv = cipher.iv
        check(iv.size == IV_LENGTH) { "Unexpected GCM IV length ${iv.size}" }
        val ciphertext = cipher.doFinal(plaintext)
        return ByteArray(iv.size + ciphertext.size).also {
            iv.copyInto(it)
            ciphertext.copyInto(it, iv.size)
        }
    }

    private fun userDataKey(key: String): String = SECRET_PREFIX + key

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val ALIAS = "$ACCOUNT_TYPE.credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_LENGTH_BITS = 256
        const val IV_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
        const val SECRET_PREFIX = "secret:"
        const val INDEX_KEY = "secretNames"
        const val SEPARATOR = "\n"
    }
}
