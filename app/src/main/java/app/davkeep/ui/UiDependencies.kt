package app.davkeep.ui

import android.accounts.Account
import android.content.Context
import app.davkeep.core.AccountStore
import app.davkeep.core.ClientCertificateStore
import app.davkeep.core.CredentialStore
import app.davkeep.core.DavHttpClientFactory
import app.davkeep.core.SyncErrorClassifier
import app.davkeep.error.SyncErrorClassifierImpl
import app.davkeep.net.DavHttpClientFactoryImpl
import app.davkeep.store.AccountManagerAccountStore
import app.davkeep.store.ClientCertificateStoreImpl
import app.davkeep.store.ImportedCertificateSecrets
import app.davkeep.store.KeystoreCredentialStore

/**
 * Where the UI resolves the shared implementations, so that changing one is a change in one file.
 *
 * The UI uses these strictly through the frozen interfaces; the concrete classes — and the names
 * under which the imported identity's archive is held — appear here and nowhere else in this
 * package. Every one of them takes only an application context and is cheap to construct, so call
 * sites construct what they need.
 */
internal object UiDependencies {

    fun accountStore(context: Context): AccountStore =
        AccountManagerAccountStore(context.applicationContext)

    fun httpClientFactory(context: Context): DavHttpClientFactory =
        DavHttpClientFactoryImpl(context.applicationContext)

    fun errorClassifier(): SyncErrorClassifier = SyncErrorClassifierImpl()

    fun clientCertificateStore(context: Context): ClientCertificateStore =
        ClientCertificateStoreImpl(context.applicationContext)

    fun credentialStore(context: Context): CredentialStore =
        KeystoreCredentialStore(context.applicationContext)

    /**
     * The app-held half of an Account's imported identity, for the export: the re-wrapped archive
     * and the passphrase that opens it. Null when the Account's identity is not an imported one.
     *
     * @throws app.davkeep.core.CredentialsUnreadableException after a device restore.
     */
    fun importedCertificate(context: Context, account: Account): AccountExport.ImportedCertificate? {
        val credentials = credentialStore(context)
        val archive = credentials.get(account, ImportedCertificateSecrets.ARCHIVE) ?: return null
        val passphrase = credentials.get(account, ImportedCertificateSecrets.PASSPHRASE) ?: return null
        return AccountExport.ImportedCertificate(archive, passphrase)
    }

    /** Puts back what [importedCertificate] read, so a restored Archive resolves to an identity. */
    fun restoreImportedCertificate(
        context: Context,
        account: Account,
        certificate: AccountExport.ImportedCertificate,
    ) {
        val credentials = credentialStore(context)
        credentials.put(account, ImportedCertificateSecrets.ARCHIVE, certificate.archive)
        credentials.put(account, ImportedCertificateSecrets.PASSPHRASE, certificate.passphrase)
    }
}
