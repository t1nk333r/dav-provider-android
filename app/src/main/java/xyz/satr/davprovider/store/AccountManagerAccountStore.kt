package xyz.satr.davprovider.store

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import xyz.satr.davprovider.core.ACCOUNT_TYPE
import xyz.satr.davprovider.core.AccountStore
import xyz.satr.davprovider.core.CredentialStore
import xyz.satr.davprovider.core.CredentialsUnreadableException
import xyz.satr.davprovider.core.DavAccount
import xyz.satr.davprovider.core.DavCollection
import xyz.satr.davprovider.core.DavHeader
import xyz.satr.davprovider.ui.CollectionSelectionWriter

/**
 * The names an Account's Credentials are held under in a [CredentialStore].
 *
 * A header value is keyed by position rather than by name so that two headers sharing one name —
 * which the record keeps as two names — keep two values as well.
 */
internal object AccountSecrets {

    const val PASSWORD: String = "password"

    fun header(index: Int): String = "header:$index"
}

/**
 * The Account record of spec §4: one AccountManager account per Account, its non-secret fields as
 * JSON in that account's userdata, its Credentials in [credentials].
 *
 * The userdata half is not a convenience. AccountManager is where the sync framework and
 * ContactsProvider2 look for the account, and the account's existence there is what keeps the
 * providers from deleting its rows, so the record has to live with the account rather than beside it.
 */
class AccountManagerAccountStore(
    context: Context,
    private val credentials: CredentialStore = KeystoreCredentialStore(context),
) : AccountStore, CollectionSelectionWriter {

    private val accountManager: AccountManager = AccountManager.get(context.applicationContext)

    /**
     * Every Account, for enumeration.
     *
     * Reading Credentials is not needed to list Accounts and is deliberately not attempted here, so
     * this cannot fail after a device restore and the settings screen still shows every Account.
     * What it returns carries the header *names* with empty values and a null password — that is
     * enough to display and edit an Account, but call [load] for one you are about to use, because
     * [save] is total and would take those empty values for the truth.
     *
     * An account with no record at all — a save interrupted before its last step — has no baseUrl
     * to report and is left out; writing the record with [save] picks it up again. A record that
     * cannot be read throws rather than disappearing from the list.
     */
    override fun list(): List<DavAccount> =
        accountManager.getAccountsByType(ACCOUNT_TYPE).mapNotNull { readRecord(it) }

    /**
     * The Account with its Credentials, ready to use.
     *
     * @throws CredentialsUnreadableException when Credentials are stored but cannot be decrypted —
     * after a device restore the Keystore key is gone, and reporting that as an authentication
     * failure is the opacity this app exists to remove (spec §5 class 5).
     */
    override fun load(account: Account): DavAccount? {
        val record = readRecord(account) ?: return null
        return record.copy(
            headers = record.headers.mapIndexed { index, header ->
                DavHeader(header.name, headerSecret(account, index))
            },
            password = credentials.get(account, AccountSecrets.PASSWORD),
        )
    }

    /**
     * Creates the AccountManager account if it does not exist yet, then writes the record and its
     * Credentials.
     *
     * Registration comes first because ContactsProvider2 deletes the groups and raw contacts of any
     * ACCOUNT_TYPE/ACCOUNT_NAME that is not registered in AccountManager (spec §7), and this store
     * is the only thing that registers one. The record itself is written last: it names the
     * Credentials, so a half-written Account must read back as one whose Credentials cannot be read
     * — never as one with no Credentials, nor with an empty header value that fails as an
     * authentication error somewhere else entirely.
     */
    override fun save(davAccount: DavAccount) {
        val account = davAccount.androidAccount
        val previous = readRecord(account)
        if (accountManager.getAccountsByType(ACCOUNT_TYPE).none { it.name == davAccount.label }) {
            check(accountManager.addAccountExplicitly(account, null, null)) {
                "Could not register account ${davAccount.label}"
            }
        }
        writeSecrets(account, davAccount)
        writeRecord(account, davAccount)
        pruneSecrets(account, previous, davAccount)
    }

    /**
     * Reaps what this app owns for [account]: its Credentials and, with the account, the userdata
     * that holds the ciphertext and the record.
     *
     * The provider reaps its own rows, and its sync state lives with them. The Keystore key is
     * per app rather than per Account — it is shared by every Account, so deleting it here would
     * destroy the others' Credentials and is exactly what this method must not do.
     */
    override fun delete(account: Account) {
        // Cleared while the account still exists: once it is gone, its secrets can no longer be named.
        credentials.clear(account)
        accountManager.removeAccountExplicitly(account)
    }

    /**
     * Rewrites the Collection selections of an existing Account and nothing else, so the settings
     * screen cannot endanger Credentials it was never able to display (ADR-0001).
     */
    override fun saveCollections(account: Account, collections: List<DavCollection>) {
        val record = readRecord(account)
            ?: throw IllegalStateException("No account record exists for ${account.name}")
        writeRecord(account, record.copy(collections = collections))
    }

    private fun readRecord(account: Account): DavAccount? {
        val encoded = accountManager.getUserData(account, AccountRecordJson.USER_DATA_KEY)
            ?: return null
        return AccountRecordJson.fromJson(encoded, account.name)
    }

    private fun writeRecord(account: Account, davAccount: DavAccount) {
        accountManager.setUserData(
            account,
            AccountRecordJson.USER_DATA_KEY,
            AccountRecordJson.toJson(davAccount),
        )
    }

    private fun writeSecrets(account: Account, davAccount: DavAccount) {
        davAccount.password?.let { credentials.put(account, AccountSecrets.PASSWORD, it) }
        davAccount.headers.forEachIndexed { index, header ->
            credentials.put(account, AccountSecrets.header(index), header.value)
        }
    }

    /** Drops exactly the Credentials the Account no longer has: they can be replaced, never revealed. */
    private fun pruneSecrets(account: Account, previous: DavAccount?, updated: DavAccount) {
        if (updated.password == null) credentials.remove(account, AccountSecrets.PASSWORD)
        for (index in updated.headers.size until (previous?.headers?.size ?: 0)) {
            credentials.remove(account, AccountSecrets.header(index))
        }
    }

    private fun headerSecret(account: Account, index: Int): String =
        // The record lists this header, so a missing value is unreadable Credentials — not an
        // empty header, which would be sent and misread as the server rejecting a credential.
        credentials.get(account, AccountSecrets.header(index))
            ?: throw CredentialsUnreadableException(null)
}
