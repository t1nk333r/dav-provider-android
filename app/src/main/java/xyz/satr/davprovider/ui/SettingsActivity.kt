package xyz.satr.davprovider.ui

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import xyz.satr.davprovider.BuildConfig
import android.content.ContentResolver
import android.content.Intent
import android.content.SyncStatusObserver
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xyz.satr.davprovider.R
import xyz.satr.davprovider.core.ClientCertificateInfo
import xyz.satr.davprovider.core.ClientCertificateSource
import xyz.satr.davprovider.core.ClientCertificateStore
import xyz.satr.davprovider.core.CredentialsUnreadableException
import xyz.satr.davprovider.core.DavAccount
import xyz.satr.davprovider.core.DavCollection
import xyz.satr.davprovider.core.SyncError
import xyz.satr.davprovider.core.readBounded
import xyz.satr.davprovider.error.credentialsUnreadable
import xyz.satr.davprovider.sync.SyncPreferences
import xyz.satr.davprovider.sync.SyncScheduler

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

    /**
     * The provider permissions are *dangerous* ones: declaring them in the manifest grants nothing.
     * Without them every sync dies opening the contacts or calendar provider with a `databaseError`
     * that the framework treats as a hard error and therefore never retries.
     *
     * That is exactly what happened — the app registered both sync adapters correctly and then
     * failed every run at the provider, with nothing in the log, because the failure precedes any
     * HTTP work and the reporter is only reached at the end of a run. Ask as soon as an Account
     * exists, not at first launch: the request only means something next to what needs it.
     */
    private val providerPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* the answer is the user's */ }

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
        findViewById<Button>(R.id.view_log).setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }
        findViewById<Button>(R.id.export_accounts).setOnClickListener { exportAccounts() }
        findViewById<Button>(R.id.import_accounts).setOnClickListener { importAccounts() }
        // Read from the build rather than written into a string, so it cannot drift from the APK.
        findViewById<TextView>(R.id.settings_version).text = getString(
            R.string.settings_version,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )

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
        /** The certificate line, already rendered: reading an imported identity is not free. */
        val certificate: String?,
        /**
         * An Account with no server: its configuration was removed and its rows were kept, so there
         * is nothing to sync and nothing to show a sync's outcome for. The record's own address is
         * the signal — an Account that has never been filled in does not exist, because a record is
         * only written by a save.
         */
        val disconnected: Boolean = davAccount.baseUrl.isEmpty(),
    )

    private var askedProviderPermissions = false

    private fun refresh() {
        executor.execute {
            val screens = readAccounts()
            main.post {
                if (!isActive()) return@post
                populate(screens)
                // Automatic runs have no gesture to hang this question on, so ask as soon as a
                // configured Account exists — otherwise the framework fires a periodic sync that
                // fails unretryably at the provider and nobody ever sees why. Once per visit, so
                // declining is not punished with a dialog on every refresh.
                if (screens.isNotEmpty() && !askedProviderPermissions) {
                    askedProviderPermissions = true
                    requestProviderPermissionsIfNeeded()
                }
            }
        }
    }

    private fun readAccounts(): List<AccountScreen> {
        val store = UiDependencies.accountStore(this)
        val certificates = UiDependencies.clientCertificateStore(this)
        val status = SyncStatusStore(this)
        return store.list().map { davAccount ->
            AccountScreen(
                davAccount = davAccount,
                account = davAccount.androidAccount,
                report = status.read(davAccount.androidAccount),
                certificate = certificateLabel(certificates, davAccount),
            )
        }
    }

    /**
     * What the Account's certificate is, in one line, or null when it has none.
     *
     * A KeyChain alias is named as an alias and an imported identity by what it says about itself,
     * because the two fail differently: an alias is a name for a key this app never sees, and an
     * imported archive is key material this app is answerable for.
     */
    private fun certificateLabel(certificates: ClientCertificateStore, davAccount: DavAccount): String? {
        val source = davAccount.certificate ?: return null
        val info = if (source is ClientCertificateSource.Imported) {
            readCertificateInfo(certificates, davAccount.androidAccount)
        } else {
            null
        }
        return certificateLine(this, source, info)
    }

    /**
     * Null when the imported archive cannot be read — after a restore, or after the store deleted a
     * copy that stopped parsing. The record stays as it is and the line says what to do about it.
     */
    private fun readCertificateInfo(certificates: ClientCertificateStore, account: Account): ClientCertificateInfo? =
        try {
            certificates.info(account)
        } catch (e: Exception) {
            null
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
        if (screen.disconnected) return disconnectedCard(screen, highlighted)

        val card = LayoutInflater.from(this).inflate(R.layout.row_account, accountsContainer, false)
        val status = screen.report?.composedStatus ?: AccountStatus.NEVER_SYNCED

        val label = card.findViewById<TextView>(R.id.account_label)
        label.text = screen.davAccount.label
        if (highlighted) label.setTypeface(label.typeface, Typeface.BOLD)
        card.findViewById<TextView>(R.id.account_status).text =
            getString(R.string.status_line, statusLabel(this, status), statusDetail(this, screen.report))
        card.findViewById<TextView>(R.id.account_last_sync).text =
            lastSyncLabel(this, screen.report?.lastSyncAt ?: 0L)
        val certificate = card.findViewById<TextView>(R.id.account_certificate)
        certificate.text = screen.certificate.orEmpty()
        certificate.visibility = if (screen.certificate == null) View.GONE else View.VISIBLE

        val collections = card.findViewById<LinearLayout>(R.id.account_collections)
        card.findViewById<TextView>(R.id.account_no_collections).visibility =
            if (screen.davAccount.collections.isEmpty()) View.VISIBLE else View.GONE
        screen.davAccount.collections.forEach { collections.addView(collectionRow(screen, it, collections)) }

        // §8: the waiting state is what a deferred run recorded, never inferred from the network:
        // a queued sync on a metered network may be waiting for something else entirely, and the
        // user needs the state that actually happened.
        card.findViewById<LinearLayout>(R.id.account_wifi).visibility =
            if (screen.report?.deferred == true) View.VISIBLE else View.GONE

        val unmeteredOnly = card.findViewById<CheckBox>(R.id.account_unmetered_only)
        unmeteredOnly.isChecked = SyncPreferences(this).unmeteredOnly(screen.account)
        // Listener last: re-rendering must not look like the user toggled something.
        unmeteredOnly.setOnCheckedChangeListener { _, checked -> writeUnmeteredOnly(screen, checked) }

        // §8's interval: the row is where the Account's schedule is read, and tapping it is the
        // only way it is changed.
        val interval = card.findViewById<TextView>(R.id.account_interval)
        interval.text = getString(
            R.string.sync_interval_line,
            intervalLabel(this, SyncPreferences(this).intervalSeconds(screen.account)),
        )
        interval.setOnClickListener { chooseInterval(screen) }

        // §8's exemption row, which is offered only where there is evidence and the user has not
        // already answered it. It states what was observed and leaves the conclusion to the user:
        // what this app can see is when its own syncs ran, never what Android decided.
        val battery = card.findViewById<LinearLayout>(R.id.account_battery)
        if (batteryExemptionAdvised(screen.report, isBatteryOptimisationExempt())) {
            battery.visibility = View.VISIBLE
            card.findViewById<TextView>(R.id.account_battery_evidence).text =
                getString(R.string.battery_evidence, screen.report?.missedSlots ?: 0)
            card.findViewById<Button>(R.id.account_battery_request).setOnClickListener {
                requestBatteryExemption()
            }
            card.findViewById<Button>(R.id.account_battery_dismiss).setOnClickListener {
                dismissBatteryPrompt(screen)
            }
        } else {
            battery.visibility = View.GONE
        }

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

    /**
     * The card for an Account whose configuration has been removed and whose data has not.
     *
     * Nothing here is a failure, so nothing here reads like one: no recorded outcome, no schedule,
     * no certificate, no Collections. Showing the last run's report would say a sync had failed,
     * and the truth is that no sync is coming.
     */
    private fun disconnectedCard(screen: AccountScreen, highlighted: Boolean): View {
        val card = LayoutInflater.from(this)
            .inflate(R.layout.row_account_disconnected, accountsContainer, false)
        val label = card.findViewById<TextView>(R.id.account_label)
        label.text = screen.davAccount.label
        if (highlighted) label.setTypeface(label.typeface, Typeface.BOLD)
        card.findViewById<TextView>(R.id.account_status).text = getString(
            R.string.status_line,
            getString(R.string.status_disconnected),
            getString(R.string.status_detail_disconnected),
        )
        card.findViewById<Button>(R.id.account_configure).setOnClickListener { configure(screen) }
        card.findViewById<Button>(R.id.account_remove).setOnClickListener { confirmRemove(screen) }
        return card
    }

    /**
     * Opens the setup screen on an Account that already exists, so its configuration can be filled
     * in again. The Account is never removed on the way, so what it synced stays where it is: the
     * rows belong to it and it is the same account after this as before, with a server again.
     */
    private fun configure(screen: AccountScreen) {
        startActivity(
            Intent(this, AccountSetupActivity::class.java)
                .putExtra(EXTRA_RECONFIGURE, screen.davAccount.label),
        )
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
        // A sync without these cannot open either provider, so asking here is what turns a hard
        // failure the framework will not retry into one question.
        requestProviderPermissionsIfNeeded()
        SyncScheduler.syncNow(account)
        showMessage(getString(R.string.sync_requested))
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
            // §8: the schedule follows the selection, and it follows it here because this screen is
            // where an Account first comes to have something to sync.
            SyncScheduler.applySelection(this, screen.account, updated)
            main.post { if (isActive()) refresh() }
        }
    }

    /**
     * §8's unmetered-only setting, written where the sync engine reads it.
     *
     * Nothing on the card changes with it: whether a run is waiting is what that run recorded, not
     * what this toggle says, and re-rendering here would only redraw the same state.
     */
    private fun writeUnmeteredOnly(screen: AccountScreen, enabled: Boolean) {
        executor.execute {
            try {
                SyncPreferences(this).setUnmeteredOnly(screen.account, enabled)
            } catch (e: Exception) {
                main.post { if (isActive()) { showMessage(getString(R.string.save_failed, e.javaClass.simpleName)); refresh() } }
            }
        }
    }

    /**
     * §8's interval chooser: one answer, so a single-choice list rather than a second screen.
     *
     * The list opens on the interval the Account is actually on. An interval this build does not
     * offer — userdata another build wrote — leaves nothing checked rather than checking the first
     * option, which would show an answer the Account does not have.
     *
     * No message on the dialog: an `AlertDialog` renders either its message or its choice list, and
     * the reason the shortest interval is the shortest is on the card, where it is read before the
     * chooser is opened rather than instead of the choices.
     */
    private fun chooseInterval(screen: AccountScreen) {
        val offered = SyncPreferences.OFFERED_INTERVAL_SECONDS
        val labels = offered.map { intervalLabel(this, it) }.toTypedArray()
        val checked = offered.indexOf(SyncPreferences(this).intervalSeconds(screen.account))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.sync_interval_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                dialog.dismiss()
                writeInterval(screen, offered[which])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * The chosen interval is stored and the schedule is then re-applied from it, so it takes effect
     * now instead of at the next run: a periodic sync is a persisted job, and the platform only
     * learns of a change when it is told.
     *
     * The re-apply is the call selection writes already end with, which is what keeps one schedule
     * per Account: it removes the periodic sync before adding it back, and it leaves an Account
     * with nothing selected unscheduled even when its interval changed.
     */
    private fun writeInterval(screen: AccountScreen, seconds: Long) {
        executor.execute {
            try {
                SyncPreferences(this).setIntervalSeconds(screen.account, seconds)
                SyncScheduler.applySelection(this, screen.account, screen.davAccount.collections)
            } catch (e: Exception) {
                main.post { if (isActive()) { showMessage(getString(R.string.save_failed, e.javaClass.simpleName)); refresh() } }
                return@execute
            }
            main.post { if (isActive()) refresh() }
        }
    }

    /**
     * Whether Android already lets this app's background work run unrestrained.
     *
     * Read on every render rather than remembered: the user can change it in the system settings at
     * any moment, and the row that offers it must disappear as soon as it is granted.
     */
    private fun isBatteryOptimisationExempt(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: false

    /**
     * §8's exemption request.
     *
     * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is normal-protection, so the manifest declaration plus
     * this intent is the whole mechanism — there is no runtime grant to ask for. Google Play
     * restricts the permission to apps that can state a need for it, which this one can: a sync
     * provider whose periodic job is suppressed does not work. A published build would have to
     * declare that need, so the row is justified or dropped rather than the declaration kept
     * quietly.
     */
    private fun requestBatteryExemption() {
        // Re-checked at the tap: the row was rendered from what was true then, and the only way it
        // can be stale is that the user has since granted it in the system settings.
        if (isBatteryOptimisationExempt()) {
            refresh()
            return
        }
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.fromParts("package", packageName, null),
        )
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Some devices ship no screen for this; being told where it lives beats a button that
            // appears to do nothing.
            showMessage(getString(R.string.battery_unavailable))
        }
    }

    /**
     * §8's permanent dismissal, written to the Account's own userdata so the answer lasts exactly
     * as long as the evidence it silences: the prompt is offered once per Account, and an Account
     * that is removed takes the answer with it rather than leaving it to silence another.
     */
    private fun dismissBatteryPrompt(screen: AccountScreen) {
        executor.execute {
            try {
                SyncStatusStore(this).dismissBatteryPrompt(screen.account)
            } catch (e: Exception) {
                main.post { if (isActive()) showMessage(getString(R.string.save_failed, e.javaClass.simpleName)) }
                return@execute
            }
            main.post { if (isActive()) refresh() }
        }
    }

    private fun checkCollections(screen: AccountScreen) {
        executor.execute {
            val loaded = loadAccount(screen.account) ?: return@execute
            val outcome = CollectionDiscovery.discover(
                factory = UiDependencies.httpClientFactory(this),
                classifier = UiDependencies.errorClassifier(),
                davAccount = loaded,
            )
            // §8: the walk is a sequence, and a sequence shown once in a dialog is gone the moment
            // it closes. WARN rather than INFO when it did not run to the end or found nothing —
            // those are the two outcomes someone comes back to the log to understand.
            SyncLog(this).appendDiscovery(
                account = loaded.label,
                level = if (outcome.completed && outcome.collections.isNotEmpty()) {
                    SyncLog.Level.INFO
                } else {
                    SyncLog.Level.WARN
                },
                notes = outcome.notes,
            )
            if (!outcome.completed) {
                // §8: every attempt is surfaced with its outcome, including the ones that failed —
                // which is the whole difference between this and "couldn't find any services".
                main.post {
                    if (isActive()) showTextDialog(getString(R.string.refresh_collections), outcome.notes.joinToString("\n"))
                }
                return@execute
            }
            val merged = CollectionDiscovery.merge(screen.davAccount.collections, outcome.collections)
            try {
                selectionWriter().saveCollections(screen.account, merged)
            } catch (e: Exception) {
                main.post { if (isActive()) showMessage(getString(R.string.save_failed, e.javaClass.simpleName)) }
                return@execute
            }
            // A Collection discovered here arrives unselected, so this usually keeps a selection's
            // schedule as it was; an Account that had none is left unscheduled until one is chosen.
            SyncScheduler.applySelection(this, screen.account, merged)
            val added = merged.size - screen.davAccount.collections.size
            main.post {
                if (!isActive()) return@post
                showTextDialog(
                    getString(R.string.refresh_collections),
                    (listOf(getString(R.string.collections_checked, merged.size, added)) + outcome.notes)
                        .joinToString("\n"),
                )
                refresh()
            }
        }
    }

    private fun addCollection(screen: AccountScreen) {
        val view = layoutInflater.inflate(R.layout.dialog_text_input, null)
        val input = view.findViewById<EditText>(R.id.text_input)
        MaterialAlertDialogBuilder(this)
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
                id = CollectionDiscovery.collectionId(parsed.toString()),
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
            // Re-adding a selected URL keeps the schedule it had; a new one is unselected and does
            // not start one.
            SyncScheduler.applySelection(this, screen.account, updated)
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
        val dialog = MaterialAlertDialogBuilder(this)
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
        getString(R.string.diagnose_variant_without_certificate) to loaded.copy(certificate = null),
        getString(R.string.diagnose_variant_unauthenticated) to loaded.copy(
            headers = emptyList(),
            certificate = null,
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

    /**
     * Keeping the synced contacts and events is what happens when the user says nothing. Removal
     * used to take them away, so the outcome that destroys them is the one that has to be reached
     * for rather than fallen into.
     */
    private fun confirmRemove(screen: AccountScreen) {
        val view = layoutInflater.inflate(R.layout.dialog_remove_account, null)
        view.findViewById<TextView>(R.id.remove_message).setText(R.string.remove_account_message)
        val alsoDelete = view.findViewById<CheckBox>(R.id.remove_also_delete)
        alsoDelete.setText(R.string.remove_also_delete)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.remove_account_title, screen.davAccount.label))
            .setView(view)
            .setPositiveButton(R.string.remove) { _, _ -> remove(screen, alsoDelete.isChecked) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** [alsoDelete] is the user having asked for the synced rows to go with the account. */
    private fun remove(screen: AccountScreen, alsoDelete: Boolean) {
        executor.execute {
            // While the Account still exists, because the schedule is the framework's and is keyed
            // by the Account's name and type: a periodic job left behind would wake the device to
            // sync an Account nothing resolves.
            SyncScheduler.cancel(screen.account)
            val store = UiDependencies.accountStore(this)
            try {
                store.disconnect(screen.account)
                // The providers reap the rows of an account that is gone (spec §7), so removing the
                // account is the only way to take the rows with it — and it is what was asked for.
                if (alsoDelete) store.delete(screen.account)
            } catch (e: Exception) {
                main.post { if (isActive()) showMessage(getString(R.string.save_failed, e.javaClass.simpleName)) }
                return@execute
            }
            SyncStatusStore(this).clear(screen.account)
            SyncLog(this).forget(screen.davAccount.label)
            SyncNotifications(this).dismiss(screen.davAccount.label)
            val label = screen.davAccount.label
            val message = if (alsoDelete) {
                getString(R.string.account_removed, label)
            } else {
                getString(R.string.account_disconnected)
            }
            main.post { if (isActive()) { showMessage(message); refresh() } }
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
        // Import asks once, so the whole box goes rather than the field alone — hiding only the
        // field would leave an empty outlined row under the first one.
        if (!confirm) view.findViewById<View>(R.id.passphrase_repeat_box).visibility = View.GONE
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(view)
            .setPositiveButton(if (confirm) R.string.export_start else R.string.import_start, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val passphrase = first.text.toString()
            when {
                passphrase.isEmpty() -> first.fieldError(getString(R.string.passphrase_required))
                confirm && passphrase.length < MIN_PASSPHRASE -> first.fieldError(getString(R.string.passphrase_short))
                confirm && passphrase != second.text.toString() ->
                    second.fieldError(getString(R.string.passphrase_mismatch))

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
            val certificates = HashMap<String, AccountExport.ImportedCertificate>()
            val unreadable = ArrayList<String>()
            store.list().forEach { listed ->
                // list() never touches the Keystore and returns header names with empty values, so
                // an export built on it would ship blanks; load() is the only source of secrets.
                try {
                    store.load(listed.androidAccount)?.let { account ->
                        loaded += account
                        // An imported identity is the one credential a restore cannot rebuild, so it
                        // is carried whole: the archive this app re-wrapped, and its own passphrase.
                        if (account.certificate is ClientCertificateSource.Imported) {
                            UiDependencies.importedCertificate(this, listed.androidAccount)
                                ?.let { certificates[account.label] = it }
                        }
                    }
                } catch (e: CredentialsUnreadableException) {
                    // Refusing to write blanks beats writing a file that fails as a bad password.
                    unreadable += listed.label
                }
            }
            try {
                val document = AccountExport.encode(loaded, certificates, passphrase.toCharArray())
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
                contentResolver.openInputStream(uri)?.use {
                    readBounded(it).toString(Charsets.UTF_8)
                } ?: error("the picked file could not be opened")
            } catch (e: Exception) {
                // The message, when there is one: readBounded explains that a file was too large.
                val message = getString(R.string.import_failed, e.message ?: e.javaClass.simpleName)
                main.post { if (isActive()) showMessage(message) }
                return@execute
            }
            val entries = try {
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
            val skipped = entries.filter { it.account.label in existing }.map { it.account.label }
            val failed = ArrayList<String>()
            val withoutCertificate = ArrayList<String>()
            var imported = 0
            entries.filterNot { it.account.label in existing }.forEach { entry ->
                val importedCertificate = entry.importedCertificate
                // A record naming an imported identity the file does not carry would claim an
                // identity nothing can produce, so it is imported without one and said out loud.
                val stripped = entry.account.certificate is ClientCertificateSource.Imported &&
                    importedCertificate == null
                try {
                    val account = if (stripped) entry.account.copy(certificate = null) else entry.account
                    // create, not save: the filter above is what makes an import additive, and this
                    // is the store saying the same thing, so neither has to be trusted alone.
                    store.create(account)
                    // Written after the Account exists, which is where the archive belongs.
                    if (importedCertificate != null) {
                        UiDependencies.restoreImportedCertificate(this, account.androidAccount, importedCertificate)
                    }
                    if (stripped) withoutCertificate += entry.account.label
                    imported++
                } catch (e: Exception) {
                    // One Account the store refuses must not discard the rest of the file.
                    failed += entry.account.label
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
                if (withoutCertificate.isNotEmpty()) {
                    showMessage(getString(R.string.import_certificate_missing, withoutCertificate.joinToString(", ")))
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
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.notifications_title)
            .setMessage(R.string.notifications_message)
            .setPositiveButton(R.string.notifications_allow) { _, _ ->
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            .setNegativeButton(R.string.notifications_later, null)
            .show()
    }

    // ------------------------------------------------------------ provider permissions

    private val providerPermissionNames = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    private fun missingProviderPermissions(): List<String> = providerPermissionNames.filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    /**
     * Asked before a sync rather than at launch, for the same reason as the notification question:
     * it only means something next to the action that needs it.
     *
     * The rationale is shown because these are ordinary personal-data permissions a user is right
     * to refuse — but refusing them means the app cannot work at all, and a silent failure would be
     * the worst of the three outcomes.
     */
    private fun requestProviderPermissionsIfNeeded() {
        val missing = missingProviderPermissions()
        if (missing.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.provider_permissions_title)
            .setMessage(R.string.provider_permissions_message)
            .setPositiveButton(R.string.provider_permissions_allow) { _, _ ->
                providerPermissions.launch(missing.toTypedArray())
            }
            .setNegativeButton(R.string.provider_permissions_later, null)
            .show()
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
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun showMessage(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun isActive(): Boolean = !isFinishing && !isDestroyed

    private companion object {
        const val WEBDAV_MULTI_STATUS = 207
        const val MIN_PASSPHRASE = 8
    }
}
