package xyz.satr.davprovider.ui

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Intent
import android.content.SyncStatusObserver
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.Executors
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xyz.satr.davprovider.R
import xyz.satr.davprovider.core.CredentialsUnreadableException
import xyz.satr.davprovider.core.DavAccount
import xyz.satr.davprovider.core.DavCollection
import xyz.satr.davprovider.core.SyncError
import xyz.satr.davprovider.error.credentialsUnreadable

/**
 * The launcher, and the per-Account screen the platform opens from an account's sync settings.
 *
 * Every Account is listed with its own status and its own per-Collection breakdown: one broken
 * Collection reports itself and nothing else. Nothing here syncs on its own — "Sync now" and
 * "Sync anyway" are the only ways a sync starts from this screen, and both are the user asking.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var accountsContainer: LinearLayout
    private lateinit var emptyState: TextView
    private lateinit var settingsScroll: ScrollView

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var statusObserver: Any? = null

    /**
     * The passphrase typed for an export, held only while the file picker is open. The document is
     * never written before the user has chosen where it goes.
     */
    private var pendingExportPassphrase: String? = null

    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val passphrase = pendingExportPassphrase
            pendingExportPassphrase = null
            if (uri != null && passphrase != null) writeExport(uri, passphrase)
        }

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) askImportPassphrase(uri)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* the answer is the user's */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        UiSyncWiring.install(this)

        accountsContainer = findViewById(R.id.accounts_container)
        emptyState = findViewById(R.id.settings_empty)
        settingsScroll = findViewById(R.id.settings_scroll)
        findViewById<Button>(R.id.add_account).setOnClickListener {
            startActivity(Intent(this, AccountSetupActivity::class.java))
        }
        findViewById<Button>(R.id.view_log).setOnClickListener { showLog() }
        findViewById<Button>(R.id.export_accounts).setOnClickListener { exportAccounts() }
        findViewById<Button>(R.id.import_accounts).setOnClickListener { importAccounts() }

        // Sync start/finish and pending changes are the only signals the platform exposes for
        // "something happened"; the status itself lives with the account record.
        statusObserver = ContentResolver.addStatusChangeListener(
            ContentResolver.SYNC_OBSERVER_TYPE_ACTIVE or ContentResolver.SYNC_OBSERVER_TYPE_PENDING,
            SyncStatusObserver { _ -> main.post { if (isActive()) refresh() } },
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        statusObserver?.let { ContentResolver.removeStatusChangeListener(it) }
        statusObserver = null
        executor.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------ listing

    private data class AccountScreen(
        val davAccount: DavAccount,
        val account: Account,
        val report: AccountReport?,
    )

    private fun refresh() {
        executor.execute {
            val screens = readAccounts()
            main.post { if (isActive()) populate(screens) }
        }
    }

    private fun readAccounts(): List<AccountScreen> {
        val store = UiDependencies.accountStore(this)
        val status = SyncStatusStore(this)
        return store.list().map { davAccount ->
            AccountScreen(davAccount, davAccount.androidAccount, status.read(davAccount.androidAccount))
        }
    }

    private fun populate(screens: List<AccountScreen>) {
        accountsContainer.removeAllViews()
        emptyState.visibility = if (screens.isEmpty()) View.VISIBLE else View.GONE
        // The platform opens this screen for one account; the extra names whose card it is.
        val highlighted = intent?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        var target: View? = null
        screens.forEach { screen ->
            val isTarget = screen.davAccount.label == highlighted
            val card = accountCard(screen, isTarget)
            accountsContainer.addView(card)
            if (isTarget) target = card
        }
        target?.let { card -> settingsScroll.post { settingsScroll.smoothScrollTo(0, card.top) } }
    }

    private fun accountCard(screen: AccountScreen, highlighted: Boolean): View {
        val card = LayoutInflater.from(this).inflate(R.layout.row_account, accountsContainer, false)
        val status = screen.report?.status ?: AccountStatus.NEVER_SYNCED

        val label = card.findViewById<TextView>(R.id.account_label)
        label.text = screen.davAccount.label
        if (highlighted) label.setTypeface(label.typeface, Typeface.BOLD)
        card.findViewById<TextView>(R.id.account_status).text =
            getString(R.string.status_line, statusLabel(this, status), statusDetail(this, screen.report))
        card.findViewById<TextView>(R.id.account_last_sync).text =
            lastSyncLabel(this, screen.report?.lastSyncAt ?: 0L)

        val collections = card.findViewById<LinearLayout>(R.id.account_collections)
        card.findViewById<TextView>(R.id.account_no_collections).visibility =
            if (screen.davAccount.collections.isEmpty()) View.VISIBLE else View.GONE
        screen.davAccount.collections.forEach { collections.addView(collectionRow(screen, it, collections)) }

        card.findViewById<LinearLayout>(R.id.account_wifi).visibility =
            if (isWaitingForWifi(screen.account)) View.VISIBLE else View.GONE
        card.findViewById<Button>(R.id.account_sync_now).setOnClickListener { requestManualSync(screen.account) }
        card.findViewById<Button>(R.id.account_sync_anyway).setOnClickListener { requestManualSync(screen.account) }

        // Diagnose varies credentials on purpose, so it appears only where a user is already
        // looking at a failure and asks for it.
        val diagnose = card.findViewById<Button>(R.id.account_diagnose)
        diagnose.visibility = if (status == AccountStatus.FAILED) View.VISIBLE else View.GONE
        diagnose.setOnClickListener { diagnose(screen) }

        card.findViewById<Button>(R.id.account_refresh_collections).setOnClickListener { checkCollections(screen) }
        card.findViewById<Button>(R.id.account_add_collection).setOnClickListener { addCollection(screen) }
        card.findViewById<Button>(R.id.account_remove).setOnClickListener { confirmRemove(screen) }
        return card
    }

    private fun collectionRow(screen: AccountScreen, collection: DavCollection, parent: LinearLayout): View {
        val row = LayoutInflater.from(this).inflate(R.layout.row_collection, parent, false)
        row.findViewById<TextView>(R.id.collection_name).text = collectionTitle(collection)
        applyCollectionColor(row.findViewById(R.id.collection_color), collection.color)
        row.findViewById<TextView>(R.id.collection_meta).text = getString(
            R.string.collection_meta,
            collectionTypeLabel(this, collection.type),
            collection.url,
        )
        val report = screen.report?.collections?.firstOrNull { it.collectionId == collection.id }
        row.findViewById<TextView>(R.id.collection_state).text = collectionState(collection, report)

        val check = row.findViewById<CheckBox>(R.id.collection_selected)
        check.isChecked = collection.selected
        // Listener last: re-rendering must not look like the user toggled something.
        check.setOnCheckedChangeListener { _, selected -> writeSelection(screen, collection, selected) }
        return row
    }

    /** A Collection that vanished from the server is unavailable, never deleted. */
    private fun collectionState(collection: DavCollection, report: CollectionReport?): String = when {
        !collection.available -> getString(R.string.collection_unavailable)
        report == null -> getString(R.string.collection_not_synced)
        report.outcome == CollectionOutcome.OK -> getString(R.string.collection_synced)
        else -> report.summary ?: getString(R.string.collection_not_synced)
    }

    // ------------------------------------------------------------ actions

    /**
     * A manual sync bypasses every constraint, including the account's own unmetered-only rule and
     * its schedule, so "Sync now" and "Sync anyway" are one call.
     */
    private fun requestManualSync(account: Account) {
        // The moment the user starts a sync is the moment an answer about failure notifications
        // means something; asking at first launch would mean nothing.
        requestNotificationPermissionIfNeeded()
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_BACKOFF, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_IGNORE_SETTINGS, true)
        }
        AUTHORITIES.forEach { authority ->
            ContentResolver.setIsSyncable(account, authority, 1)
            ContentResolver.requestSync(account, authority, extras)
        }
        showMessage(getString(R.string.sync_requested))
    }

    /**
     * The app enforces unmetered-only itself, since the platform has no API for it, so the waiting
     * state is inferred: a sync is queued and the network in use is metered.
     */
    private fun isWaitingForWifi(account: Account): Boolean {
        if (AUTHORITIES.none { ContentResolver.isSyncPending(account, it) }) return false
        val manager = getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun writeSelection(screen: AccountScreen, collection: DavCollection, selected: Boolean) {
        val updated = screen.davAccount.collections.map {
            if (it.id == collection.id) it.copy(selected = selected) else it
        }
        executor.execute {
            try {
                selectionWriter().saveCollections(screen.account, updated)
            } catch (e: Exception) {
                main.post { if (isActive()) { showMessage(getString(R.string.save_failed, e.javaClass.simpleName)); refresh() } }
                return@execute
            }
            main.post { if (isActive()) refresh() }
        }
    }

    private fun checkCollections(screen: AccountScreen) {
        executor.execute {
            val loaded = loadAccount(screen.account) ?: return@execute
            val outcome = CollectionDiscovery.discover(loaded)
            if (!outcome.completed) {
                main.post { if (isActive()) showMessage(getString(R.string.discovery_not_connected)) }
                return@execute
            }
            val merged = CollectionDiscovery.merge(screen.davAccount.collections, outcome.collections)
            try {
                selectionWriter().saveCollections(screen.account, merged)
            } catch (e: Exception) {
                main.post { if (isActive()) showMessage(getString(R.string.save_failed, e.javaClass.simpleName)) }
                return@execute
            }
            val added = merged.size - screen.davAccount.collections.size
            main.post {
                if (!isActive()) return@post
                // §8: every attempt is surfaced with its outcome, not just the final one.
                if (outcome.notes.isEmpty()) {
                    showMessage(getString(R.string.collections_checked, merged.size, added))
                } else {
                    showTextDialog(getString(R.string.refresh_collections), outcome.notes.joinToString("\n"))
                }
                refresh()
            }
        }
    }

    private fun addCollection(screen: AccountScreen) {
        val view = layoutInflater.inflate(R.layout.dialog_text_input, null)
        val input = view.findViewById<EditText>(R.id.text_input)
        input.hint = getString(R.string.collection_url_hint)
        AlertDialog.Builder(this)
            .setTitle(R.string.add_collection)
            .setView(view)
            .setPositiveButton(R.string.ok) { _, _ -> probeCollection(screen, input.text.toString().trim()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * A pasted Collection URL skips discovery: one `PROPFIND Depth: 0` says whether the URL is an
     * address book or a calendar, and a URL that is neither is reported as what it actually said.
     */
    private fun probeCollection(screen: AccountScreen, url: String) {
        if (url.isEmpty()) return
        val parsed = url.toHttpUrlOrNull()
        if (parsed == null) {
            showMessage(getString(R.string.error_url_scheme))
            return
        }
        executor.execute {
            val loaded = loadAccount(screen.account) ?: return@execute
            val outcome = DavProbe.propfind(
                factory = UiDependencies.httpClientFactory(this),
                classifier = UiDependencies.errorClassifier(),
                davAccount = loaded,
                url = parsed.toString(),
                variant = getString(R.string.add_collection),
            )
            val description = WebDavXml.collectionDescription(outcome.evidence.body)
            val type = description.type
            if (outcome.evidence.httpStatus != WEBDAV_MULTI_STATUS || type == null) {
                val message = getString(R.string.not_a_collection, outcome.error.summary)
                main.post { if (isActive()) showErrorDialog(message, outcome.error) }
                return@execute
            }
            val collection = DavCollection(
                id = collectionId(screen.davAccount.label, parsed.toString()),
                url = parsed.toString(),
                type = type,
                displayName = description.displayName,
                color = null,
                // New Collections arrive unselected.
                selected = false,
                available = true,
            )
            val prior = screen.davAccount.collections.firstOrNull { it.id == collection.id }
            val updated = screen.davAccount.collections.filterNot { it.id == collection.id } +
                // Re-adding a stored URL refreshes its details; it does not deselect it.
                (if (prior != null) collection.copy(selected = prior.selected) else collection)
            try {
                selectionWriter().saveCollections(screen.account, updated)
            } catch (e: Exception) {
                main.post { if (isActive()) showMessage(getString(R.string.save_failed, e.javaClass.simpleName)) }
                return@execute
            }
            val name = collectionTitle(collection)
            main.post { if (isActive()) { showMessage(getString(R.string.collection_added, name)); refresh() } }
        }
    }

    /**
     * The four requests, and only when the user asks: credentials, no headers, no certificate,
     * nothing. Each row is one request, never a retry, because a burst of credential-varying
     * requests is what an attack looks like and what gets a service token rate-limited.
     */
    private fun diagnose(screen: AccountScreen) {
        val view = layoutInflater.inflate(R.layout.dialog_diagnose, null)
        val progress = view.findViewById<TextView>(R.id.diagnose_progress)
        val rows = view.findViewById<LinearLayout>(R.id.diagnose_rows)
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.diagnose_title, screen.davAccount.label))
            .setView(view)
            .setPositiveButton(R.string.close, null)
            .create()
        dialog.show()

        executor.execute {
            val loaded = loadAccount(screen.account)
            if (loaded == null) {
                main.post { if (dialog.isShowing) progress.setText(R.string.diagnose_unreadable) }
                return@execute
            }
            val variants = diagnoseVariants(loaded)
            variants.forEachIndexed { index, (variant, account) ->
                main.post {
                    if (dialog.isShowing) progress.text = getString(R.string.diagnose_progress, index + 1, variants.size)
                }
                val outcome = DavProbe.propfind(
                    factory = UiDependencies.httpClientFactory(this),
                    classifier = UiDependencies.errorClassifier(),
                    davAccount = account,
                    url = loaded.baseUrl,
                    variant = variant,
                )
                main.post { if (dialog.isShowing) addDiagnoseRow(rows, outcome) }
            }
            main.post { if (dialog.isShowing) progress.setText("") }
        }
    }

    private fun diagnoseVariants(loaded: DavAccount): List<Pair<String, DavAccount>> = listOf(
        getString(R.string.diagnose_variant_with_credentials) to loaded,
        getString(R.string.diagnose_variant_without_headers) to loaded.copy(headers = emptyList()),
        getString(R.string.diagnose_variant_without_certificate) to loaded.copy(certAlias = null),
        getString(R.string.diagnose_variant_unauthenticated) to loaded.copy(
            headers = emptyList(),
            certAlias = null,
            username = null,
            password = null,
        ),
    )

    private fun addDiagnoseRow(container: LinearLayout, outcome: ProbeOutcome) {
        val row = layoutInflater.inflate(R.layout.row_diagnose, container, false)
        row.findViewById<TextView>(R.id.diagnose_name).text = outcome.variant
        row.findViewById<TextView>(R.id.diagnose_result).text = diagnoseRow(this, outcome)
        container.addView(row)
    }

    private fun confirmRemove(screen: AccountScreen) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.remove_account_title, screen.davAccount.label))
            .setMessage(R.string.remove_account_message)
            .setPositiveButton(R.string.remove) { _, _ -> remove(screen) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun remove(screen: AccountScreen) {
        executor.execute {
            try {
                UiDependencies.accountStore(this).delete(screen.account)
            } catch (e: Exception) {
                main.post { if (isActive()) showMessage(getString(R.string.save_failed, e.javaClass.simpleName)) }
                return@execute
            }
            SyncStatusStore(this).clear(screen.account)
            SyncLog(this).forget(screen.davAccount.label)
            SyncNotifications(this).dismiss(screen.davAccount.label)
            val label = screen.davAccount.label
            main.post { if (isActive()) { showMessage(getString(R.string.account_removed, label)); refresh() } }
        }
    }

    // ------------------------------------------------------------ export and import

    /**
     * One file with every Account and its secrets, which is the copy that can leave the device:
     * ADR-0001's on-device store is wrapped in a Keystore key a restore does not carry.
     */
    private fun exportAccounts() {
        passphraseDialog(R.string.export_accounts, R.string.export_note, confirm = true) { passphrase ->
            pendingExportPassphrase = passphrase
            createDocument.launch(getString(R.string.export_filename))
        }
    }

    private fun importAccounts() {
        openDocument.launch(arrayOf("application/json", "text/plain"))
    }

    private fun askImportPassphrase(uri: Uri) {
        passphraseDialog(R.string.import_accounts, R.string.import_note, confirm = false) { passphrase ->
            readImport(uri, passphrase)
        }
    }

    /**
     * A typo in a backup passphrase makes the file unreadable for good, which is why export asks
     * for it twice and why nothing is written before the picker has returned a destination.
     */
    private fun passphraseDialog(
        titleRes: Int,
        noteRes: Int,
        confirm: Boolean,
        onPassphrase: (String) -> Unit,
    ): AlertDialog {
        val view = layoutInflater.inflate(R.layout.dialog_passphrase, null)
        view.findViewById<TextView>(R.id.passphrase_note).setText(noteRes)
        val first = view.findViewById<EditText>(R.id.passphrase_input)
        val second = view.findViewById<EditText>(R.id.passphrase_repeat)
        if (!confirm) second.visibility = View.GONE
        val dialog = AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(view)
            .setPositiveButton(if (confirm) R.string.export_start else R.string.import_start, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val passphrase = first.text.toString()
            when {
                passphrase.isEmpty() -> first.error = getString(R.string.passphrase_required)
                confirm && passphrase.length < MIN_PASSPHRASE -> first.error = getString(R.string.passphrase_short)
                confirm && passphrase != second.text.toString() ->
                    second.error = getString(R.string.passphrase_mismatch)

                else -> {
                    dialog.dismiss()
                    onPassphrase(passphrase)
                }
            }
        }
        return dialog
    }

    private fun writeExport(uri: Uri, passphrase: String) {
        showMessage(getString(R.string.export_working))
        executor.execute {
            val store = UiDependencies.accountStore(this)
            val loaded = ArrayList<DavAccount>()
            val unreadable = ArrayList<String>()
            store.list().forEach { listed ->
                // list() never touches the Keystore and returns header names with empty values, so
                // an export built on it would ship blanks; load() is the only source of secrets.
                try {
                    store.load(listed.androidAccount)?.let { loaded += it }
                } catch (e: CredentialsUnreadableException) {
                    // Refusing to write blanks beats writing a file that fails as a bad password.
                    unreadable += listed.label
                }
            }
            try {
                val document = AccountExport.encode(loaded, passphrase.toCharArray())
                contentResolver.openOutputStream(uri)?.use { it.write(document.toByteArray(Charsets.UTF_8)) }
                    ?: error("the picked file could not be opened for writing")
            } catch (e: Exception) {
                val message = getString(R.string.export_failed, e.javaClass.simpleName)
                main.post { if (isActive()) showMessage(message) }
                return@execute
            }
            val exported = loaded.size
            main.post {
                if (!isActive()) return@post
                showMessage(getString(R.string.export_done, exported))
                if (unreadable.isNotEmpty()) {
                    showMessage(getString(R.string.export_skipped, unreadable.size, unreadable.joinToString(", ")))
                }
            }
        }
    }

    private fun readImport(uri: Uri, passphrase: String) {
        showMessage(getString(R.string.import_working))
        executor.execute {
            val document = try {
                contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: error("the picked file could not be opened")
            } catch (e: Exception) {
                val message = getString(R.string.import_failed, e.javaClass.simpleName)
                main.post { if (isActive()) showMessage(message) }
                return@execute
            }
            val accounts = try {
                AccountExport.decode(document, passphrase.toCharArray())
            } catch (e: AccountExport.UnknownFormatVersionException) {
                // Never guessed: a file from a format this build does not know is refused whole.
                val message = getString(R.string.import_version, e.version)
                main.post { if (isActive()) showMessage(message) }
                return@execute
            } catch (e: AccountExport.WrongPassphraseException) {
                main.post { if (isActive()) showMessage(getString(R.string.import_wrong_passphrase)) }
                return@execute
            } catch (e: Exception) {
                val message = getString(R.string.import_failed, e.javaClass.simpleName)
                main.post { if (isActive()) showMessage(message) }
                return@execute
            }
            val store = UiDependencies.accountStore(this)
            val existing = store.list().map { it.label }.toSet()
            val skipped = accounts.filter { it.label in existing }.map { it.label }
            val failed = ArrayList<String>()
            var imported = 0
            accounts.filterNot { it.label in existing }.forEach { account ->
                try {
                    store.save(account)
                    imported++
                } catch (e: Exception) {
                    // One Account the store refuses must not discard the rest of the file.
                    failed += account.label
                }
            }
            val importedCount = imported
            main.post {
                if (!isActive()) return@post
                showMessage(getString(R.string.import_done, importedCount))
                // An Account already on this device keeps what it has: importing is additive.
                if (skipped.isNotEmpty()) {
                    showMessage(getString(R.string.import_skipped, skipped.size, skipped.joinToString(", ")))
                }
                if (failed.isNotEmpty()) {
                    showMessage(getString(R.string.import_failed_accounts, failed.size, failed.joinToString(", ")))
                }
                refresh()
            }
        }
    }

    // ------------------------------------------------------------ notifications

    /**
     * Asked when the user starts a sync by hand, never at first launch: the question is only
     * meaningful next to the thing that would produce the notification.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        AlertDialog.Builder(this)
            .setTitle(R.string.notifications_title)
            .setMessage(R.string.notifications_message)
            .setPositiveButton(R.string.notifications_allow) { _, _ ->
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            .setNegativeButton(R.string.notifications_later, null)
            .show()
    }

    // ------------------------------------------------------------ log

    private fun showLog() {
        executor.execute {
            val entries = SyncLog(this).read()
            main.post {
                if (!isActive()) return@post
                val view = layoutInflater.inflate(R.layout.dialog_log, null)
                view.findViewById<TextView>(R.id.log_text).text =
                    if (entries.isEmpty()) getString(R.string.log_empty) else entries.joinToString("\n")
                AlertDialog.Builder(this)
                    .setTitle(R.string.log_title)
                    .setView(view)
                    .setPositiveButton(R.string.share) { _, _ -> shareLog(entries) }
                    .setNeutralButton(R.string.clear) { _, _ -> clearLog() }
                    .setNegativeButton(R.string.close, null)
                    .show()
            }
        }
    }

    private fun shareLog(entries: List<String>) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.log_title))
            putExtra(Intent.EXTRA_TEXT, entries.joinToString("\n"))
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun clearLog() {
        executor.execute {
            SyncLog(this).clear()
            main.post { if (isActive()) refresh() }
        }
    }

    // ------------------------------------------------------------ plumbing

    private fun selectionWriter(): CollectionSelectionWriter =
        UiDependencies.accountStore(this) as CollectionSelectionWriter

    /** Null when the credentials cannot be read: the caller has already reported why. */
    private fun loadAccount(account: Account): DavAccount? = try {
        UiDependencies.accountStore(this).load(account)
    } catch (e: CredentialsUnreadableException) {
        main.post { if (isActive()) showCredentialsUnreadable(e) }
        null
    }

    private fun showCredentialsUnreadable(cause: Throwable) {
        val error = credentialsUnreadable(cause)
        showErrorDialog(error.summary, error)
    }

    private fun showErrorDialog(title: String, error: SyncError) {
        showTextDialog(title, errorDetails(this, error))
    }

    private fun showTextDialog(title: String, body: String) {
        val view = layoutInflater.inflate(R.layout.dialog_text, null)
        view.findViewById<TextView>(R.id.text_body).text = body
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showMessage(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    /**
     * The id is derived from the account label and the URL rather than drawn at random, so adding
     * the same URL twice yields the same Collection: this id is what lands in `RawContacts.SYNC3`.
     */
    private fun collectionId(label: String, url: String): String =
        UUID.nameUUIDFromBytes("$label\n$url".toByteArray(Charsets.UTF_8)).toString()

    private fun isActive(): Boolean = !isFinishing && !isDestroyed

    private companion object {
        val AUTHORITIES = listOf(ContactsContract.AUTHORITY, CalendarContract.AUTHORITY)
        const val WEBDAV_MULTI_STATUS = 207
        const val MIN_PASSPHRASE = 8
    }
}
