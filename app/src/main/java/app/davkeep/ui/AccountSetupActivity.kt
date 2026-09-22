package app.davkeep.ui

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AccountAuthenticatorResponse
import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.security.KeyChain
import android.security.KeyChainAliasCallback
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.Executors
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import app.davkeep.R
import app.davkeep.core.ACCOUNT_TYPE
import app.davkeep.core.AccountExistsException
import app.davkeep.core.AccountStore
import app.davkeep.core.CertificateImportResult
import app.davkeep.core.ClientCertificateInfo
import app.davkeep.core.ClientCertificateSource
import app.davkeep.core.ClientCertificateStore
import app.davkeep.core.CredentialsUnreadableException
import app.davkeep.core.DavAccount
import app.davkeep.core.DavHeader
import app.davkeep.core.readBounded
import app.davkeep.error.credentialsUnreadable
import app.davkeep.sync.SyncScheduler

/**
 * The label of an Account that already exists, to fill its configuration in again.
 *
 * A label rather than the whole record: the record is read from the store, and passing one through
 * an Intent would be passing a copy that can disagree with it.
 */
internal const val EXTRA_RECONFIGURE: String = "app.davkeep.reconfigure"

/**
 * Creating one Account: its base URL, the headers and client certificate that authenticate it,
 * and an optional username and password.
 *
 * Only the base URL is required. Nothing here refuses a save for want of a credential, because an
 * unauthenticated server is a real configuration and a form rule would reject it; what the server
 * actually does is reported by the check that runs after the save.
 *
 * The client certificate is offered two ways, as alternatives: an alias in the system KeyChain, and
 * a `.p12` file this app holds itself. They fail differently — the alias stops resolving after a
 * device restore, an imported archive travels inside the Account's own encrypted export — so the
 * screen keeps them apart and names the one an Account has.
 */
class AccountSetupActivity : AppCompatActivity(), KeyChainAliasCallback {

    private lateinit var urlInput: EditText
    private lateinit var labelInput: EditText
    private lateinit var labelNote: TextView

    /** The note under the URL, which only ever says one thing: this address is not encrypted. */
    private lateinit var urlNote: TextView

    /** The note's own colour, put back when the label stops colliding with an account. */
    private var labelNoteColor: Int = 0

    /**
     * The labels already on the device. Read once, because the only thing that can add one while
     * this screen is open is this screen, and it reads again when it does. Stale the other way —
     * an account removed elsewhere leaves a warning behind — which is why it warns and does not
     * decide: the save is what refuses.
     */
    private var knownLabels: Set<String> = emptySet()

    private lateinit var headerRows: LinearLayout
    private lateinit var certStatusView: TextView
    private lateinit var certDetailsView: TextView
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var saveButton: Button
    private lateinit var saveProgress: TextView
    private lateinit var probeCard: LinearLayout
    private lateinit var probeSummary: TextView
    private lateinit var probeDetails: TextView
    private lateinit var probeToggle: Button

    /** Colors to return to: only the certificate section is ever recoloured, and only to warn. */
    private var certStatusColor = 0
    private var certDetailsColor = 0

    private var certificate: ClientCertificateSource? = null

    /** What the imported archive says about itself, once it has been read. */
    private var importedInfo: ClientCertificateInfo? = null

    /** A picked archive, held until the Account exists to hold it. */
    private var pendingImport: PendingImport? = null

    /** Whether the folded sections are open. Carried across a rotation with the rest of the form. */
    private var advancedOpen = false

    /** Set by "Clear password"; typed text clears it again, so the intent is never stale. */
    private var passwordCleared = false

    /** The last label this screen derived from the URL, so a typed label is never overwritten. */
    private var derivedLabel: String? = null
    private var stored = false
    private var authenticatorResponse: AccountAuthenticatorResponse? = null
    private var detailsText = ""

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) readArchive(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account_setup)

        UiSyncWiring.install(this)

        urlInput = findViewById(R.id.url_input)
        labelInput = findViewById(R.id.label_input)
        labelNote = findViewById(R.id.label_note)
        labelNoteColor = labelNote.currentTextColor
        urlNote = findViewById(R.id.url_note)
        urlNote.setText(R.string.url_cleartext)
        headerRows = findViewById(R.id.header_rows)
        certStatusView = findViewById(R.id.cert_status)
        certDetailsView = findViewById(R.id.cert_details)
        usernameInput = findViewById(R.id.username_input)
        passwordInput = findViewById(R.id.password_input)
        saveButton = findViewById(R.id.save_account)
        saveProgress = findViewById(R.id.save_progress)
        probeCard = findViewById(R.id.probe_card)
        probeSummary = findViewById(R.id.probe_summary)
        probeDetails = findViewById(R.id.probe_details)
        probeToggle = findViewById(R.id.probe_details_toggle)
        certStatusColor = certStatusView.currentTextColor
        certDetailsColor = certDetailsView.currentTextColor

        authenticatorResponse = IntentCompat.getParcelableExtra(
            intent,
            AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE,
            AccountAuthenticatorResponse::class.java,
        )

        urlInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) defaultLabelFromUrl() }
        // The up arrow is Back, through the same dispatcher, so leaving without saving still tells
        // the authenticator the account was cancelled.
        setSupportActionBar(findViewById(R.id.setup_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.title_add_account)
        findViewById<MaterialToolbar>(R.id.setup_toolbar).setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        findViewById<View>(R.id.setup_advanced_row).setOnClickListener { applyAdvanced(!advancedOpen) }
        findViewById<Button>(R.id.add_header).setOnClickListener { addHeaderRow("", "") }
        findViewById<Button>(R.id.cloudflare_shortcut).setOnClickListener { seedProxyHeaders() }
        findViewById<Button>(R.id.choose_certificate).setOnClickListener { chooseCertificate() }
        findViewById<Button>(R.id.import_certificate).setOnClickListener { importCertificate() }
        findViewById<Button>(R.id.clear_certificate).setOnClickListener { clearCertificate() }
        findViewById<Button>(R.id.clear_password).setOnClickListener { clearPassword() }
        // Material's box keeps its error until something clears it, and a complaint that the URL is
        // missing has no business sitting under a field that now has one.
        urlInput.doAfterTextChanged {
            urlInput.fieldError(null)
            updateUrlNote()
        }
        // The label can arrive without being typed: it is derived from the host when the URL field
        // loses focus, and Save derives it too.
        labelInput.doAfterTextChanged { updateLabelNote() }
        passwordInput.doAfterTextChanged { text -> if (!text.isNullOrEmpty()) passwordCleared = false }
        saveButton.setOnClickListener { save() }
        probeToggle.setOnClickListener { toggleDetails() }
        findViewById<Button>(R.id.probe_copy).setOnClickListener {
            copyToClipboard(this, getString(R.string.clip_label), detailsText.ifEmpty { probeSummary.text.toString() })
        }
        findViewById<Button>(R.id.probe_close).setOnClickListener { finish() }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!stored) {
                        authenticatorResponse?.onError(AccountManager.ERROR_CODE_CANCELED, "Account setup cancelled")
                    }
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            },
        )
        executor.execute {
            val labels = try {
                // Configured accounts only: a disconnected one is a label the user can fill in
                // again, and warning about it would make the honest case look like the dangerous one.
                UiDependencies.accountStore(this).list()
                    .filter { it.baseUrl.isNotEmpty() }
                    .map { it.label }
                    .toSet()
            } catch (e: Exception) {
                // A record that cannot be read is not this screen's to report — the save says so
                // when it gets there — and an empty set costs only the warning.
                emptySet()
            }
            main.post {
                if (isActive()) {
                    knownLabels = labels
                    updateLabelNote()
                }
            }
        }
        // An Account that already exists, being configured again. The label is its identity and is
        // not changed here, and treating it as stored is what makes the save an update rather than
        // a registration — AccountStore.save, not AccountStore.create.
        intent.getStringExtra(EXTRA_RECONFIGURE)?.let { existing ->
            stored = true
            labelInput.setText(existing)
            findViewById<TextInputLayout>(R.id.label_input_box).isEnabled = false
            updateLabelNote()
            supportActionBar?.setTitle(R.string.title_configure_account)
        }
        restoreForm(savedInstanceState)
        // Never hide something already filled in: a rotation restores the header rows and the chosen
        // certificate, and folding them away would look like losing them.
        if (advancedOpen || headerRows.childCount > 0 || certificate != null || usernameInput.text.isNotEmpty()) {
            applyAdvanced(true)
        }
    }

    /**
     * Opens or closes the folded sections.
     *
     * One drawable rotated rather than two: an open-state chevron would be a second vector to keep
     * in step with this one.
     */
    private fun applyAdvanced(open: Boolean) {
        advancedOpen = open
        findViewById<View>(R.id.setup_advanced_content).visibility = if (open) View.VISIBLE else View.GONE
        findViewById<ImageView>(R.id.setup_chevron).rotation = if (open) 180f else 0f
    }

    /**
     * Survives a rotation or a theme change, which recreate this activity and would otherwise take
     * the header rows and the chosen certificate with them.
     *
     * Header *values*, the password and a picked file's passphrase are deliberately absent: the
     * instance state is not where secrets belong, and the two fields that hold them switch the
     * platform's own view-state saving off for the same reason. After a rotation they are retyped,
     * and the seeded row names are still there to retype into — the certificate *source* is kept,
     * because losing it would make the next save delete an identity that is still working.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_URL, urlInput.text.toString())
        outState.putString(KEY_LABEL, labelInput.text.toString())
        outState.putString(KEY_USERNAME, usernameInput.text.toString())
        outState.putString(
            KEY_CERT_SOURCE,
            when (certificate) {
                is ClientCertificateSource.KeyChainAlias -> CERT_KEYCHAIN
                ClientCertificateSource.Imported -> CERT_IMPORTED
                null -> null
            },
        )
        outState.putString(KEY_CERT_ALIAS, (certificate as? ClientCertificateSource.KeyChainAlias)?.alias)
        outState.putBoolean(KEY_STORED, stored)
        outState.putBoolean(KEY_PASSWORD_CLEARED, passwordCleared)
        outState.putBoolean(KEY_ADVANCED_OPEN, advancedOpen)
        outState.putString(KEY_SUMMARY, probeSummary.text?.toString())
        outState.putString(KEY_DETAILS, detailsText)
        outState.putBoolean(KEY_DETAILS_VISIBLE, probeDetails.visibility == View.VISIBLE)
        val names = ArrayList<String>(headerRows.childCount)
        for (index in 0 until headerRows.childCount) {
            names += headerRows.getChildAt(index).findViewById<EditText>(R.id.header_name).text.toString()
        }
        outState.putStringArrayList(KEY_HEADER_NAMES, names)
    }

    private fun restoreForm(state: Bundle?) {
        if (state == null) return
        advancedOpen = state.getBoolean(KEY_ADVANCED_OPEN)
        urlInput.setText(state.getString(KEY_URL))
        labelInput.setText(state.getString(KEY_LABEL))
        usernameInput.setText(state.getString(KEY_USERNAME))
        derivedLabel = null
        when (state.getString(KEY_CERT_SOURCE)) {
            CERT_KEYCHAIN -> state.getString(KEY_CERT_ALIAS)?.let {
                certificate = ClientCertificateSource.KeyChainAlias(it)
            }

            CERT_IMPORTED -> {
                certificate = ClientCertificateSource.Imported
                // What the archive says is read back from the archive: it is not carried here.
                readImportedInfo()
            }
        }
        refreshCertificateView()
        state.getStringArrayList(KEY_HEADER_NAMES)?.forEach { name -> addHeaderRow(name, "") }
        passwordCleared = state.getBoolean(KEY_PASSWORD_CLEARED)
        if (state.getBoolean(KEY_STORED)) {
            stored = true
            // The box is what greys out: a TextInputLayout left enabled keeps drawing an active
            // outline around a field nobody can type into. Disabling it reaches the field inside.
            findViewById<TextInputLayout>(R.id.label_input_box).isEnabled = false
            updateLabelNote()
        }
        detailsText = state.getString(KEY_DETAILS).orEmpty()
        probeDetails.text = detailsText
        state.getString(KEY_SUMMARY)?.let {
            probeCard.visibility = View.VISIBLE
            probeSummary.text = it
        }
        if (state.getBoolean(KEY_DETAILS_VISIBLE)) {
            probeDetails.visibility = View.VISIBLE
            probeToggle.setText(R.string.details_hide)
        }
    }

    override fun onDestroy() {
        // The user's passphrase and the archive it opened are dropped with the screen: used, not kept.
        discardPendingImport()
        executor.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------ certificate

    /** The KeyChain's answer is the alias; the key itself never enters this process. */
    private fun chooseCertificate() {
        val host = urlInput.text.toString().trim().toHttpUrlOrNull()?.host
        val current = (certificate as? ClientCertificateSource.KeyChainAlias)?.alias
        KeyChain.choosePrivateKeyAlias(this, this, null, null, host, -1, current)
    }

    override fun alias(alias: String?) {
        // A null alias means the chooser was dismissed, which is not a request to forget the alias.
        if (alias == null) return
        // One identity, not two: choosing an alias abandons a picked file.
        discardPendingImport()
        certificate = ClientCertificateSource.KeyChainAlias(alias)
        importedInfo = null
        refreshCertificateView()
    }

    /**
     * Opens the system picker for anything at all.
     *
     * `.p12` has no MIME type a picker can be trusted with — it arrives as `application/x-pkcs12`,
     * as `application/octet-stream`, or as nothing at all — so the filter is left off and the
     * file's own first byte decides whether it is an archive. That verdict is then about the file:
     * a passphrase prompt for something that was never a PKCS#12 file blames the wrong thing.
     */
    private fun importCertificate() {
        openDocument.launch(arrayOf("*/*"))
    }

    private fun readArchive(uri: Uri) {
        executor.execute {
            val archive = try {
                contentResolver.openInputStream(uri)?.use { readBounded(it) }
                    ?: throw IllegalStateException("the picked file could not be opened")
            } catch (e: Exception) {
                // The message, when there is one: readBounded explains that a file was too large,
                // and reporting the class instead would hide the only useful part.
                val reason = e.message ?: e.javaClass.simpleName
                val message = getString(R.string.certificate_unreadable, reason)
                main.post { if (isActive()) showCertificateFailure(message) }
                return@execute
            }
            main.post {
                if (isActive()) askCertificatePassphrase(archive, retry = false) { holdForImport(archive, it) }
            }
        }
    }

    /**
     * The passphrase that opens the picked file, asked for once and used once: it is never stored,
     * and the archive is re-wrapped under a random passphrase this app generates. A passphrase
     * chosen for a file is one the user has almost certainly used somewhere else.
     */
    private fun askCertificatePassphrase(archive: ByteArray, retry: Boolean, onPassphrase: (CharArray) -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_passphrase, null)
        view.findViewById<TextView>(R.id.passphrase_note).setText(R.string.certificate_passphrase_note)
        val input = view.findViewById<EditText>(R.id.passphrase_input)
        // The label belongs to the field's box now, not the EditText: a hint set on the inner view
        // is what a TextInputLayout ignores.
        view.findViewById<TextInputLayout>(R.id.passphrase_input_box)
            .hint = getString(R.string.certificate_passphrase)
        // One field: an import opens an existing file, so there is nothing to confirm against. The
        // box goes, not just the field, or an empty outlined row is left behind.
        view.findViewById<View>(R.id.passphrase_repeat_box).visibility = View.GONE
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (retry) R.string.certificate_wrong_passphrase_title else R.string.import_certificate)
            .setView(view)
            .setPositiveButton(R.string.import_start, null)
            // Cancel changes nothing: an archive held from an earlier attempt stays held, and the
            // section keeps describing it until the user replaces or clears the certificate.
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val passphrase = input.text.toString()
            if (passphrase.isEmpty()) {
                input.fieldError(getString(R.string.passphrase_required))
                return@setOnClickListener
            }
            dialog.dismiss()
            onPassphrase(passphrase.toCharArray())
        }
    }

    /**
     * Keeps a picked archive until the Account exists to hold it.
     *
     * The re-wrapped archive goes into the Account's own userdata through the CredentialStore, and
     * an Account this screen has not saved yet has no userdata to write to — so the import runs on
     * save, and a form the user abandons imports nothing. The passphrase travels with it in memory
     * only, and is dropped by [onDestroy] if the form is given up.
     *
     * A retype arrives here with the archive that is already held, so the handover must not zero it:
     * only a superseded archive — a different file — is wiped.
     */
    private fun holdForImport(archive: ByteArray, passphrase: CharArray) {
        pendingImport?.let { held ->
            if (held.archive !== archive) held.archive.fill(0)
            held.passphrase.fill('\u0000')
        }
        pendingImport = PendingImport(archive, passphrase)
        // The two sources are alternatives: a picked file replaces a KeyChain alias.
        certificate = null
        importedInfo = null
        refreshCertificateView()
    }

    /**
     * Forgets whichever source is selected. What the Account actually holds follows on save: a
     * record that names no certificate is what makes an imported archive unreachable, and the
     * archive is deleted there — see [installCertificate].
     */
    private fun clearCertificate() {
        discardPendingImport()
        certificate = null
        importedInfo = null
        refreshCertificateView()
    }

    /** Reads back what an Account's imported identity says about itself; none of it is a secret. */
    private fun readImportedInfo() {
        val label = labelInput.text.toString().trim()
        if (label.isEmpty()) return
        executor.execute {
            val info = try {
                UiDependencies.clientCertificateStore(this).info(Account(label, ACCOUNT_TYPE))
            } catch (e: Exception) {
                // An archive that cannot be read by its own app is reported, not crashed on: the
                // store deletes what it cannot parse, and the user is told to import the file again.
                null
            }
            main.post {
                if (!isActive()) return@post
                importedInfo = info
                refreshCertificateView()
            }
        }
    }

    /**
     * One place that decides what the certificate section says, so that no path — picking, clearing,
     * a failed import, a restored screen — can leave it describing a certificate that is not there.
     */
    private fun refreshCertificateView() {
        val pending = pendingImport
        val source = certificate
        val info = importedInfo
        when {
            pending != null -> {
                showCertificateStatus(getString(R.string.certificate_import_pending))
                showCertificateDetails(null, warn = false)
            }

            source is ClientCertificateSource.KeyChainAlias -> {
                showCertificateStatus(getString(R.string.certificate_keychain, source.alias))
                showCertificateDetails(null, warn = false)
            }

            source is ClientCertificateSource.Imported -> {
                // An expired certificate is imported and flagged, never refused: whether it is
                // still accepted is the server's answer, and this screen cannot give it.
                showCertificateStatus(
                    getString(
                        if (info == null) R.string.certificate_imported_missing else R.string.certificate_imported,
                    ),
                )
                showCertificateDetails(info?.let { certificateDetails(this, it) }, warn = info?.expired == true)
            }

            else -> {
                showCertificateStatus(getString(R.string.no_certificate))
                showCertificateDetails(null, warn = false)
            }
        }
    }

    private fun showCertificateStatus(text: String, failed: Boolean = false) {
        certStatusView.text = text
        certStatusView.setTextColor(if (failed) warningColor() else certStatusColor)
    }

    private fun showCertificateDetails(text: String?, warn: Boolean) {
        certDetailsView.visibility = if (text == null) View.GONE else View.VISIBLE
        certDetailsView.text = text.orEmpty()
        certDetailsView.setTextColor(if (warn) warningColor() else certDetailsColor)
    }

    /**
     * The four import outcomes, each named for what to do next. Collapsing them into "the import
     * failed" would throw away the distinction the parse exists to make: a wrong passphrase is
     * retyped, a file that is not an archive is replaced, and a file with no key is neither.
     *
     * Whatever the outcome, the screen ends up holding the identity the Account holds — [prior] when
     * nothing was imported. A screen that forgot an identity it still had would delete it on the
     * next save, which is the one way a mistyped passphrase could cost a working certificate.
     */
    private fun reportImport(result: CertificateImportResult?, prior: ClientCertificateSource?) {
        certificate = if (result is CertificateImportResult.Success) {
            ClientCertificateSource.Imported
        } else {
            prior
        }
        importedInfo = (result as? CertificateImportResult.Success)?.info
        // Only a wrong passphrase leaves the file worth keeping: every other outcome either used it
        // or proved it is not one this app can use, so the held archive goes with it.
        if (result !is CertificateImportResult.WrongPassphrase) discardPendingImport()
        when (result) {
            // Nothing was imported and the reason has already been reported where it happened.
            null -> refreshCertificateView()
            is CertificateImportResult.Success -> refreshCertificateView()
            CertificateImportResult.NotAPkcs12 ->
                showCertificateFailure(getString(R.string.certificate_not_pkcs12))

            CertificateImportResult.NoPrivateKey ->
                showCertificateFailure(getString(R.string.certificate_no_private_key))

            CertificateImportResult.WrongPassphrase -> {
                showCertificateFailure(getString(R.string.certificate_wrong_passphrase))
                offerRetype()
            }
        }
    }

    /**
     * A wrong passphrase is the one outcome the user can fix on the spot, so the retyped passphrase
     * goes straight back through the one import path there is: the Account exists by the time an
     * import can fail, and the check that follows reports what the server makes of the identity.
     */
    private fun offerRetype() {
        val archive = pendingImport?.archive ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.certificate_wrong_passphrase_title)
            .setMessage(R.string.certificate_wrong_passphrase_message)
            .setPositiveButton(R.string.certificate_retype) { _, _ ->
                askCertificatePassphrase(archive, retry = true) { passphrase ->
                    holdForImport(archive, passphrase)
                    save()
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                // Dropping the file is the honest answer to "no": nothing is left waiting to import.
                discardPendingImport()
                refreshCertificateView()
            }
            .show()
    }

    private fun showCertificateFailure(message: String) {
        showCertificateStatus(message, failed = true)
        showCertificateDetails(null, warn = false)
    }

    /**
     * Drops a held archive and the passphrase that opened it: both a file the user gave up on and
     * the passphrase typed for it are of no further use here.
     */
    private fun discardPendingImport() {
        pendingImport?.let {
            it.passphrase.fill('\u0000')
            it.archive.fill(0)
        }
        pendingImport = null
    }

    /** Material's error colour: nothing in this section is verified here, so warning is all it has. */
    private fun warningColor(): Int =
        MaterialColors.getColor(certStatusView, com.google.android.material.R.attr.colorError)

    // ------------------------------------------------------------ password

    /**
     * The one explicit way to remove a stored password. Clearing the field is not enough, because an
     * empty field means "keep what is stored".
     */
    private fun clearPassword() {
        passwordInput.setText("")
        passwordCleared = true
        Toast.makeText(this, R.string.password_will_be_removed, Toast.LENGTH_SHORT).show()
    }

    // ------------------------------------------------------------ headers

    private fun addHeaderRow(name: String, value: String): EditText {
        val row = LayoutInflater.from(this).inflate(R.layout.row_header, headerRows, false)
        row.findViewById<EditText>(R.id.header_name).setText(name)
        val valueView = row.findViewById<EditText>(R.id.header_value)
        valueView.setText(value)
        row.findViewById<View>(R.id.header_remove).setOnClickListener { headerRows.removeView(row) }
        headerRows.addView(row)
        return valueView
    }

    /**
     * Seeds the two header names an identity-aware proxy expects, with empty values to paste into.
     *
     * The shortcut exists because a misspelled name and a rejected token are indistinguishable from
     * outside — both come back as the same redirect — so the one thing worth removing is the
     * spelling.
     */
    private fun seedProxyHeaders() {
        val present = HashSet<String>()
        for (index in 0 until headerRows.childCount) {
            present += headerRows.getChildAt(index)
                .findViewById<EditText>(R.id.header_name).text.toString().trim()
        }
        var focus: EditText? = null
        for (name in PROXY_HEADER_NAMES) {
            if (name in present) continue
            focus = addHeaderRow(name, "")
        }
        focus?.requestFocus()
    }

    /**
     * Blank names are dropped; a row with a name and no value is a legal, if useless, header on a
     * first save, and on a later one it keeps the value already stored for that name.
     */
    private fun readHeaders(carryOver: List<DavHeader>): List<DavHeader> {
        val kept = carryOver.associate { it.name to it.value }
        val headers = ArrayList<DavHeader>(headerRows.childCount)
        for (index in 0 until headerRows.childCount) {
            val row = headerRows.getChildAt(index)
            val name = row.findViewById<EditText>(R.id.header_name).text.toString().trim()
            if (name.isEmpty()) continue
            val typed = row.findViewById<EditText>(R.id.header_value).text.toString()
            headers += DavHeader(name, if (typed.isNotEmpty()) typed else kept[name].orEmpty())
        }
        return headers
    }

    // ------------------------------------------------------------ label

    /**
     * The note under the URL, which says the one thing about a scheme the field cannot: `http://`
     * sends everything this account carries in the clear.
     *
     * A warning and not a refusal. A server on a network the user trusts is a documented use, and
     * the field already accepts both schemes; what was missing was saying which one this is.
     */
    private fun updateUrlNote() {
        val cleartext = urlInput.text.toString().trim().toHttpUrlOrNull()?.scheme == "http"
        urlNote.visibility = if (cleartext) View.VISIBLE else View.GONE
    }

    /**
     * The note under the label. Before Save it is the only place a collision can be seen, and the
     * label is derived from the host, so a server that is already configured gets here without
     * anyone typing its name.
     *
     * A warning, not the guard: [AccountStore.create] is what refuses, and it refuses whether or
     * not this has been read.
     */
    private fun updateLabelNote() {
        if (stored) {
            labelNote.setText(R.string.label_locked)
            labelNote.setTextColor(labelNoteColor)
            return
        }
        if (labelInput.text.toString().trim() in knownLabels) {
            labelNote.setText(R.string.label_taken)
            labelNote.setTextColor(
                MaterialColors.getColor(labelNote, com.google.android.material.R.attr.colorError),
            )
        } else {
            labelNote.setText(R.string.label_note)
            labelNote.setTextColor(labelNoteColor)
        }
    }

    private fun defaultLabelFromUrl() {
        val host = urlInput.text.toString().trim().toHttpUrlOrNull()?.host ?: return
        val current = labelInput.text.toString().trim()
        if (current.isEmpty() || current == derivedLabel) {
            labelInput.setText(host)
            derivedLabel = host
        }
    }

    // ------------------------------------------------------------ save

    /**
     * The store's save is total — an object that omits a header value or a password removes the
     * stored secret — and ADR-0001 means this screen can offer replace but never reveal. So once the
     * account exists, an empty field means "keep what is stored", deleting a row removes its header,
     * and "Clear password" is the one explicit way to remove a password. The first save takes
     * everything verbatim, because there is nothing yet to keep.
     */
    private fun save() {
        if (urlInput.text.toString().isBlank()) {
            urlInput.fieldError(getString(R.string.error_url_required))
            return
        }
        val parsed = urlInput.text.toString().trim().toHttpUrlOrNull()
        if (parsed == null) {
            urlInput.fieldError(getString(R.string.error_url_scheme))
            return
        }
        urlInput.setText(parsed.toString())
        val label = labelInput.text.toString().trim().ifEmpty { parsed.host }
        labelInput.setText(label)
        val typedPassword = passwordInput.text.toString()
        val typedUsername = usernameInput.text.toString().trim().ifEmpty { null }
        // The stored secrets are needed only to fill what the screen cannot show.
        val keepStoredPassword = !passwordCleared && typedPassword.isEmpty()
        val needsStoredSecrets = stored && (keepStoredPassword || hasEmptyHeaderValue())
        setBusy(true)
        executor.execute {
            val store = UiDependencies.accountStore(this)
            val certificates = UiDependencies.clientCertificateStore(this)
            val account = Account(label, ACCOUNT_TYPE)
            val storedSecrets = if (needsStoredSecrets) {
                try {
                    store.load(account)
                } catch (e: CredentialsUnreadableException) {
                    // Writing blanks is worse than refusing: this is exactly §5 class 5.
                    val error = credentialsUnreadable(e)
                    main.post { if (isActive()) reportFailure(error.summary, errorDetails(this, error)) }
                    return@execute
                }
            } else {
                null
            }
            // The record as it stands, for the one decision that has to compare against it: an
            // identity that is replaced or dropped has to be dealt with at the store too.
            val prior = store.list().firstOrNull { it.label == label }?.certificate
            var davAccount = DavAccount(
                label = label,
                baseUrl = parsed.toString(),
                headers = readHeaders(storedSecrets?.headers.orEmpty()),
                certificate = certificate,
                username = typedUsername,
                password = when {
                    typedPassword.isNotEmpty() -> typedPassword
                    passwordCleared -> null
                    else -> storedSecrets?.password
                },
            )
            try {
                // The first save is the one that can collide: a label already on the device belongs
                // to an account this form has never seen, and writing over it would take away an
                // address, a password and a Collection selection that are nowhere on this screen.
                // Later saves are of an account this session registered, and update it.
                if (stored) store.save(davAccount) else store.create(davAccount)
            } catch (e: AccountExistsException) {
                main.post { if (isActive()) showAccountExists(e.label) }
                return@execute
            } catch (e: Exception) {
                val message = getString(R.string.save_failed, e.javaClass.simpleName)
                main.post { if (isActive()) reportFailure(message, message) }
                return@execute
            }
            davAccount = installCertificate(store, certificates, account, davAccount, prior)
            // §8: a new Account is scheduled from here, and a new Account that arrives with nothing
            // selected is left unscheduled — a periodic job with nothing to sync is a wakeup for a
            // question nobody asked, and selecting a Collection is what enables the schedule.
            SyncScheduler.applySelection(this, account, davAccount.collections)
            main.post { if (isActive()) onStored(davAccount) }
            // The check is a report, not a condition: the account is already saved either way.
            val outcome = DavProbe.propfind(
                factory = UiDependencies.httpClientFactory(this),
                classifier = UiDependencies.errorClassifier(),
                davAccount = davAccount,
                url = davAccount.baseUrl,
                variant = getString(R.string.diagnose_variant_with_credentials),
            )
            main.post { if (isActive()) showOutcome(outcome) }
        }
    }

    /**
     * Applies the certificate this form holds to the Account that now exists to hold it, and answers
     * with the Account to carry on with.
     *
     * The order is the point: a re-wrapped archive is written into the Account's own userdata, which
     * an Account that has not been registered does not have. A failed import leaves the Account as
     * it was — a mistyped passphrase must not cost an identity that was already working — and
     * dropping an imported identity deletes its archive, which nothing else can reach.
     *
     * @return [saved], or a copy naming the certificate that is now in force.
     */
    private fun installCertificate(
        store: AccountStore,
        certificates: ClientCertificateStore,
        account: Account,
        saved: DavAccount,
        prior: ClientCertificateSource?,
    ): DavAccount {
        val pending = pendingImport
        if (pending == null) {
            if (prior is ClientCertificateSource.Imported &&
                saved.certificate !is ClientCertificateSource.Imported
            ) {
                // A credential can be replaced, never revealed — including by leaving it behind.
                certificates.remove(account)
            }
            return saved
        }
        var failure: String? = null
        val result = try {
            certificates.import(account, pending.archive, pending.passphrase)
        } catch (e: Exception) {
            failure = e.javaClass.simpleName
            null
        } finally {
            // The user's passphrase is used once and dropped: the archive is re-wrapped, and this
            // is the one secret on the screen most likely to be reused elsewhere. The archive is
            // kept for now — a wrong passphrase is retyped against the same file.
            pending.passphrase.fill('\u0000')
        }
        val applied = if (result is CertificateImportResult.Success) {
            saved.copy(certificate = ClientCertificateSource.Imported)
        } else {
            // Whatever the Account had before this save, it still has: it is not the import's loss.
            saved.copy(certificate = prior)
        }
        var saveFailure: String? = null
        try {
            store.save(applied)
        } catch (e: Exception) {
            saveFailure = e.javaClass.simpleName
        }
        main.post {
            if (!isActive()) return@post
            reportImport(result, prior)
            failure?.let { showCertificateFailure(getString(R.string.certificate_import_failed, it)) }
            saveFailure?.let {
                val message = getString(R.string.save_failed, it)
                reportFailure(message, message)
            }
        }
        return if (saveFailure == null) applied else saved
    }

    private fun hasEmptyHeaderValue(): Boolean {
        for (index in 0 until headerRows.childCount) {
            val row = headerRows.getChildAt(index)
            if (row.findViewById<EditText>(R.id.header_name).text.toString().trim().isEmpty()) continue
            if (row.findViewById<EditText>(R.id.header_value).text.isEmpty()) return true
        }
        return false
    }

    private fun onStored(davAccount: DavAccount) {
        setBusy(false)
        if (!stored) {
            stored = true
            // The label is the account identity; renaming would create a second account and a
            // second copy of the same data.
            // The box is what greys out: a TextInputLayout left enabled keeps drawing an active
            // outline around a field nobody can type into. Disabling it reaches the field inside.
            findViewById<TextInputLayout>(R.id.label_input_box).isEnabled = false
            updateLabelNote()
            setResult(Activity.RESULT_OK)
            authenticatorResponse?.onResult(
                Bundle().apply {
                    putString(AccountManager.KEY_ACCOUNT_NAME, davAccount.label)
                    putString(AccountManager.KEY_ACCOUNT_TYPE, ACCOUNT_TYPE)
                },
            )
        }
        probeCard.visibility = View.VISIBLE
        probeSummary.setText(R.string.validation_progress)
        detailsText = ""
        probeDetails.text = ""
        probeDetails.visibility = View.GONE
        probeToggle.setText(R.string.details)
    }

    private fun reportFailure(summary: String, details: String) {
        setBusy(false)
        probeCard.visibility = View.VISIBLE
        probeSummary.text = summary
        detailsText = details
        probeDetails.text = details
        probeDetails.visibility = View.VISIBLE
        probeToggle.setText(R.string.details_hide)
    }

    /**
     * A refusal, not a question. Nothing this screen can show would tell the user what saving over
     * the account takes away — the password is never displayed and the Collections are on another
     * screen — so the account is named, and what to do about it is said plainly. Nothing is
     * deleted from here: removing an account is a decision about its data, and that lives where the
     * data is.
     */
    private fun showAccountExists(label: String) {
        setBusy(false)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.account_exists_title)
            .setMessage(getString(R.string.account_exists_message, label))
            .setPositiveButton(R.string.account_exists_change_label) { _, _ ->
                labelInput.requestFocus()
            }
            .show()
    }

    private fun showOutcome(outcome: ProbeOutcome) {
        probeCard.visibility = View.VISIBLE
        probeSummary.text = outcome.error.summary
        detailsText = errorDetails(this, outcome.error)
        probeDetails.text = detailsText
    }

    private fun toggleDetails() {
        val show = probeDetails.visibility != View.VISIBLE
        probeDetails.visibility = if (show) View.VISIBLE else View.GONE
        probeToggle.setText(if (show) R.string.details_hide else R.string.details)
    }

    private fun setBusy(busy: Boolean) {
        saveButton.isEnabled = !busy
        saveProgress.visibility = if (busy) View.VISIBLE else View.GONE
        saveProgress.setText(R.string.saving)
    }

    private fun isActive(): Boolean = !isFinishing && !isDestroyed

    /**
     * A picked `.p12` and the passphrase that opened it, held from the moment the file is chosen
     * until the Account exists to hold the re-wrapped copy. Deliberately not instance state: the
     * passphrase is a secret, and a rotation means the file is picked again rather than kept.
     */
    private class PendingImport(val archive: ByteArray, val passphrase: CharArray)

    private companion object {
        /**
         * Header names for an identity-aware proxy's service token. Names, never values: the value
         * is a live credential and the user pastes it into a masked field.
         */
        val PROXY_HEADER_NAMES = listOf("CF-Access-Client-Id", "CF-Access-Client-Secret")

        const val KEY_URL = "url"
        const val KEY_LABEL = "label"
        const val KEY_USERNAME = "username"
        const val KEY_CERT_SOURCE = "certSource"
        const val KEY_CERT_ALIAS = "certAlias"
        const val CERT_KEYCHAIN = "keychain"
        const val CERT_IMPORTED = "imported"
        const val KEY_STORED = "stored"
        const val KEY_PASSWORD_CLEARED = "passwordCleared"
        const val KEY_ADVANCED_OPEN = "advancedOpen"
        const val KEY_HEADER_NAMES = "headerNames"
        const val KEY_SUMMARY = "summary"
        const val KEY_DETAILS = "details"
        const val KEY_DETAILS_VISIBLE = "detailsVisible"
    }
}
