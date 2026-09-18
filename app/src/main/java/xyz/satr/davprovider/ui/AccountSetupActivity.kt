package xyz.satr.davprovider.ui

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AccountAuthenticatorResponse
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.security.KeyChain
import android.security.KeyChainAliasCallback
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.widget.doAfterTextChanged
import java.util.concurrent.Executors
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xyz.satr.davprovider.R
import xyz.satr.davprovider.core.ACCOUNT_TYPE
import xyz.satr.davprovider.core.CredentialsUnreadableException
import xyz.satr.davprovider.core.DavAccount
import xyz.satr.davprovider.core.DavHeader
import xyz.satr.davprovider.error.credentialsUnreadable

/**
 * Creating one Account: its base URL, the headers and client certificate that authenticate it,
 * and an optional username and password.
 *
 * Only the base URL is required. Nothing here refuses a save for want of a credential, because an
 * unauthenticated server is a real configuration and a form rule would reject it; what the server
 * actually does is reported by the check that runs after the save.
 */
class AccountSetupActivity : AppCompatActivity(), KeyChainAliasCallback {

    private lateinit var urlInput: EditText
    private lateinit var labelInput: EditText
    private lateinit var labelNote: TextView
    private lateinit var headerRows: LinearLayout
    private lateinit var certAliasView: TextView
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var saveButton: Button
    private lateinit var saveProgress: TextView
    private lateinit var probeCard: LinearLayout
    private lateinit var probeSummary: TextView
    private lateinit var probeDetails: TextView
    private lateinit var probeToggle: Button

    private var certAlias: String? = null

    /** Set by "Clear password"; typed text clears it again, so the intent is never stale. */
    private var passwordCleared = false

    /** The last label this screen derived from the URL, so a typed label is never overwritten. */
    private var derivedLabel: String? = null
    private var stored = false
    private var authenticatorResponse: AccountAuthenticatorResponse? = null
    private var detailsText = ""

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account_setup)

        UiSyncWiring.install(this)

        urlInput = findViewById(R.id.url_input)
        labelInput = findViewById(R.id.label_input)
        labelNote = findViewById(R.id.label_note)
        headerRows = findViewById(R.id.header_rows)
        certAliasView = findViewById(R.id.cert_alias)
        usernameInput = findViewById(R.id.username_input)
        passwordInput = findViewById(R.id.password_input)
        saveButton = findViewById(R.id.save_account)
        saveProgress = findViewById(R.id.save_progress)
        probeCard = findViewById(R.id.probe_card)
        probeSummary = findViewById(R.id.probe_summary)
        probeDetails = findViewById(R.id.probe_details)
        probeToggle = findViewById(R.id.probe_details_toggle)

        authenticatorResponse = IntentCompat.getParcelableExtra(
            intent,
            AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE,
            AccountAuthenticatorResponse::class.java,
        )

        urlInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) defaultLabelFromUrl() }
        findViewById<Button>(R.id.add_header).setOnClickListener { addHeaderRow("", "") }
        findViewById<Button>(R.id.cloudflare_shortcut).setOnClickListener { seedProxyHeaders() }
        findViewById<Button>(R.id.choose_certificate).setOnClickListener { chooseCertificate() }
        findViewById<Button>(R.id.clear_certificate).setOnClickListener { clearCertificate() }
        findViewById<Button>(R.id.clear_password).setOnClickListener { clearPassword() }
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
        restoreForm(savedInstanceState)
    }

    /**
     * Survives a rotation or a theme change, which recreate this activity and would otherwise take
     * the header rows and the chosen certificate with them.
     *
     * Header *values* and the password are deliberately absent: the instance state is not where
     * secrets belong, and the two fields that hold them switch the platform's own view-state saving
     * off for the same reason. After a rotation they are retyped, and the seeded row names are
     * still there to retype into.
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_URL, urlInput.text.toString())
        outState.putString(KEY_LABEL, labelInput.text.toString())
        outState.putString(KEY_USERNAME, usernameInput.text.toString())
        outState.putString(KEY_CERT_ALIAS, certAlias)
        outState.putBoolean(KEY_STORED, stored)
        outState.putBoolean(KEY_PASSWORD_CLEARED, passwordCleared)
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
        urlInput.setText(state.getString(KEY_URL))
        labelInput.setText(state.getString(KEY_LABEL))
        usernameInput.setText(state.getString(KEY_USERNAME))
        derivedLabel = null
        state.getString(KEY_CERT_ALIAS)?.let { alias ->
            certAlias = alias
            certAliasView.text = getString(R.string.certificate_selected, alias)
        }
        state.getStringArrayList(KEY_HEADER_NAMES)?.forEach { name -> addHeaderRow(name, "") }
        passwordCleared = state.getBoolean(KEY_PASSWORD_CLEARED)
        if (state.getBoolean(KEY_STORED)) {
            stored = true
            labelInput.isEnabled = false
            labelNote.setText(R.string.label_locked)
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
        executor.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------ certificate

    private fun chooseCertificate() {
        val host = urlInput.text.toString().trim().toHttpUrlOrNull()?.host
        KeyChain.choosePrivateKeyAlias(this, this, null, null, host, -1, certAlias)
    }

    /** The KeyChain's answer is the alias; the key itself never enters this process. */
    override fun alias(alias: String?) {
        // A null alias means the chooser was dismissed, which is not a request to forget the alias.
        if (alias == null) return
        certAlias = alias
        certAliasView.text = getString(R.string.certificate_selected, alias)
    }

    private fun clearCertificate() {
        certAlias = null
        certAliasView.setText(R.string.no_certificate)
    }

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
            urlInput.error = getString(R.string.error_url_required)
            return
        }
        val parsed = urlInput.text.toString().trim().toHttpUrlOrNull()
        if (parsed == null) {
            urlInput.error = getString(R.string.error_url_scheme)
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
            val storedSecrets = if (needsStoredSecrets) {
                try {
                    store.load(Account(label, ACCOUNT_TYPE))
                } catch (e: CredentialsUnreadableException) {
                    // Writing blanks is worse than refusing: this is exactly §5 class 5.
                    val error = credentialsUnreadable(e)
                    main.post { if (isActive()) reportFailure(error.summary, errorDetails(this, error)) }
                    return@execute
                }
            } else {
                null
            }
            val davAccount = DavAccount(
                label = label,
                baseUrl = parsed.toString(),
                headers = readHeaders(storedSecrets?.headers.orEmpty()),
                certAlias = certAlias,
                username = typedUsername,
                password = when {
                    typedPassword.isNotEmpty() -> typedPassword
                    passwordCleared -> null
                    else -> storedSecrets?.password
                },
            )
            try {
                store.save(davAccount)
            } catch (e: Exception) {
                val message = getString(R.string.save_failed, e.javaClass.simpleName)
                main.post { if (isActive()) reportFailure(message, message) }
                return@execute
            }
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
            labelInput.isEnabled = false
            labelNote.setText(R.string.label_locked)
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

    private companion object {
        /**
         * Header names for an identity-aware proxy's service token. Names, never values: the value
         * is a live credential and the user pastes it into a masked field.
         */
        val PROXY_HEADER_NAMES = listOf("CF-Access-Client-Id", "CF-Access-Client-Secret")

        const val KEY_URL = "url"
        const val KEY_LABEL = "label"
        const val KEY_USERNAME = "username"
        const val KEY_CERT_ALIAS = "certAlias"
        const val KEY_STORED = "stored"
        const val KEY_PASSWORD_CLEARED = "passwordCleared"
        const val KEY_HEADER_NAMES = "headerNames"
        const val KEY_SUMMARY = "summary"
        const val KEY_DETAILS = "details"
        const val KEY_DETAILS_VISIBLE = "detailsVisible"
    }
}
