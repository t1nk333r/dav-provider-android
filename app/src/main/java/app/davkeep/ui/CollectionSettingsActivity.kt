package app.davkeep.ui

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import app.davkeep.R
import app.davkeep.core.ACCOUNT_TYPE
import app.davkeep.core.DavCollection
import app.davkeep.sync.SyncScheduler
import java.util.concurrent.Executors

/**
 * One Collection's settings, on a screen of its own.
 *
 * Both switches used to live in the Collection's row on the accounts screen. The write one needs a
 * sentence to be honest — it decides whether this phone may change the server — and a sentence in
 * every row turned a list into a wall. A row is a summary; this is where it is changed.
 *
 * The screen owns nothing: it reads the Account record when it opens and writes the whole Collection
 * list back through the same writer the accounts screen uses, so two screens cannot hold different
 * ideas of what is selected.
 */
class CollectionSettingsActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var account: Account
    private lateinit var collectionId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_collection_settings)

        val name = intent.getStringExtra(EXTRA_ACCOUNT_NAME)
        val id = intent.getStringExtra(EXTRA_COLLECTION_ID)
        if (name == null || id == null) {
            // Nothing to show and nothing to correct: a screen with no subject closes rather than
            // rendering an empty one.
            finish()
            return
        }
        account = Account(name, ACCOUNT_TYPE)
        collectionId = id

        findViewById<MaterialToolbar>(R.id.collection_toolbar).apply {
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
            setNavigationOnClickListener { finish() }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    /**
     * Reads the record and draws it.
     *
     * Read on every resume rather than held: a sync that ran while this screen was open changes what
     * the state line says, and the record is the only place that is true.
     *
     * The record is read without Credentials. This screen needs none, and reading them would make it
     * unreachable exactly when the accounts screen still lists the Account: after a restore the
     * Keystore key is gone, decrypting throws, and the screen used to close the instant it opened —
     * with the write switch, which lives only here, out of reach and nothing said about why.
     */
    private fun render() {
        val stored = UiDependencies.accountStore(this).list().firstOrNull { it.label == account.name }
        val collection = stored?.collections?.firstOrNull { it.id == collectionId }
        if (collection == null) {
            // The Account or the Collection was removed while this screen was open.
            finish()
            return
        }

        findViewById<MaterialToolbar>(R.id.collection_toolbar).title = collectionTitle(collection)
        findViewById<TextView>(R.id.collection_meta).text = getString(
            R.string.collection_meta,
            collectionTypeLabel(this, collection.type),
            collection.url,
        )
        // What the row had to ellipsize: the last outcome in full, and — for a Collection a walk
        // retired — the fact that its switches still mean something for rows already on the phone.
        val report = SyncStatusStore(this).read(account)
            ?.collections?.firstOrNull { it.collectionId == collection.id }
        findViewById<TextView>(R.id.collection_state).text = collectionState(this, collection, report)

        bind(R.id.collection_selected, collection.selected) { value ->
            write(reschedule = true) { it.copy(selected = value) }
        }
        bind(R.id.collection_writable, collection.writable) { value ->
            write(reschedule = false) { it.copy(writable = value) }
        }
    }

    /** Listener last, so that re-rendering never reads as the user having toggled something. */
    private fun bind(id: Int, value: Boolean, onChange: (Boolean) -> Unit) {
        findViewById<MaterialSwitch>(id).apply {
            setOnCheckedChangeListener(null)
            isChecked = value
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
    }

    /**
     * Changes one field of this Collection, on the record as it is when the write runs.
     *
     * [reschedule] is what separates the two switches: §8's schedule follows what is selected, and
     * nothing about the schedule depends on which way edits flow.
     *
     * A failed write is said, not just undone: a switch that snaps back with no explanation reads as a
     * tap that did not register, and the user taps again at something that cannot be written.
     */
    private fun write(reschedule: Boolean, change: (DavCollection) -> DavCollection) {
        executor.execute {
            val written = try {
                UiDependencies.accountStore(this).updateCollection(account, collectionId, change)
            } catch (e: Exception) {
                main.post {
                    if (!isFinishing) {
                        Toast.makeText(this, getString(R.string.save_failed, e.javaClass.simpleName), Toast.LENGTH_LONG).show()
                        render()
                    }
                }
                return@execute
            }
            if (reschedule) SyncScheduler.applySelection(this, account, written)
            main.post { if (!isFinishing) render() }
        }
    }

    companion object {
        private const val EXTRA_ACCOUNT_NAME = "account_name"
        private const val EXTRA_COLLECTION_ID = "collection_id"

        fun intent(context: Context, account: Account, collection: DavCollection): Intent =
            Intent(context, CollectionSettingsActivity::class.java)
                .putExtra(EXTRA_ACCOUNT_NAME, account.name)
                .putExtra(EXTRA_COLLECTION_ID, collection.id)
    }
}
