package xyz.satr.davprovider.ui

import android.accounts.Account
import android.content.Context
import xyz.satr.davprovider.core.AccountStore
import xyz.satr.davprovider.core.ClientCertificateStore
import xyz.satr.davprovider.core.CredentialStore
import xyz.satr.davprovider.core.DavHttpClientFactory
import xyz.satr.davprovider.core.SyncErrorClassifier
import xyz.satr.davprovider.error.SyncErrorClassifierImpl
import xyz.satr.davprovider.net.DavHttpClientFactoryImpl
import xyz.satr.davprovider.store.AccountManagerAccountStore
import xyz.satr.davprovider.store.ClientCertificateStoreImpl
import xyz.satr.davprovider.store.ImportedCertificateSecrets
import xyz.satr.davprovider.store.KeystoreCredentialStore

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
     * @throws xyz.satr.davprovider.core.CredentialsUnreadableException after a device restore.
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
