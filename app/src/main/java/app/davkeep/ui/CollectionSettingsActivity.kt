package app.davkeep.ui

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import app.davkeep.R
import app.davkeep.core.ACCOUNT_TYPE
import app.davkeep.core.DavAccount
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
    private var davAccount: DavAccount? = null

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
     */
    private fun render() {
        val stored = try {
            UiDependencies.accountStore(this).load(account)
        } catch (e: Exception) {
            finish()
            return
        }
        davAccount = stored
        val collection = stored?.collections?.firstOrNull { it.id == collectionId }
        if (collection == null) {
            // The Collection was removed, or a walk retired it, while this screen was open.
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
            write(collection.copy(selected = value), reschedule = true)
        }
        bind(R.id.collection_writable, collection.writable) { value ->
            write(collection.copy(writable = value), reschedule = false)
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
     * Writes the whole Collection list back, as the accounts screen does.
     *
     * [reschedule] is what separates the two switches: §8's schedule follows what is selected, and
     * nothing about the schedule depends on which way edits flow.
     */
    private fun write(updated: DavCollection, reschedule: Boolean) {
        val stored = davAccount ?: return
        val collections = stored.collections.map { if (it.id == updated.id) updated else it }
        executor.execute {
            try {
                (UiDependencies.accountStore(this) as CollectionSelectionWriter)
                    .saveCollections(account, collections)
            } catch (e: Exception) {
                main.post { if (!isFinishing) render() }
                return@execute
            }
            if (reschedule) SyncScheduler.applySelection(this, account, collections)
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
