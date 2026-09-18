package xyz.satr.davprovider.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import java.util.concurrent.Executors
import xyz.satr.davprovider.R

/**
 * The log, on a screen of its own.
 *
 * It used to be a dialog whose body was a single TextView in a ScrollView, which is why a ring
 * buffer of 200 entries could not be read: nothing could be searched, nothing sorted, and the whole
 * paragraph was re-measured on every change and every keystroke. Here the entries are rows; the
 * questions a reader actually asks of them — what does it say, which Account, which level, which of
 * the two kinds — are controls that are always visible; and the file is read once, off the main
 * thread, never per keystroke.
 *
 * Nothing here is a second opinion about the file: the entries, their wording and their level
 * colours are the same ones the dialog showed, and what Share hands over is still the filtered view
 * — a reader who narrowed to one Account's errors is handing over exactly the question they asked.
 */
class LogsActivity : AppCompatActivity() {

    private lateinit var list: RecyclerView
    private lateinit var loading: View
    private lateinit var message: TextView
    private lateinit var error: TextView
    private lateinit var showing: TextView
    private lateinit var search: EditText
    private lateinit var sortToggle: Button
    private lateinit var levelFilter: Spinner
    private lateinit var kindFilter: Spinner
    private lateinit var accountFilter: Spinner

    private val adapter = LogsAdapter()
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Index 0 is "all" in each of the three selectors, so an unfiltered view is the first state. */
    private val levels: List<SyncLog.Level?> = listOf(null) + SyncLog.Level.entries

    /**
     * [SyncLog.Kind.UNPARSED] is deliberately not offered: it is not a kind of run to ask for, it is
     * a line nobody can read, and it stays in the unfiltered view where it is evidence.
     */
    private val kinds: List<SyncLog.Kind?> = listOf(null, SyncLog.Kind.SYNC, SyncLog.Kind.DISCOVERY)
    private var accounts: List<String?> = listOf(null)

    /** Null until the read lands: "still reading" and "nothing recorded" are different answers. */
    private var entries: List<SyncLog.Entry>? = null

    /** Why the read failed, when it did; the screen says so instead of pretending the log is empty. */
    private var failure: String? = null

    /** What the current search, filters and direction match — the whole answer, not just the page. */
    private var matched: List<SyncLog.Entry> = emptyList()

    /** How many of [matched] are bound, i.e. how far the chunked reveal has got. */
    private var revealed = 0

    private var newestFirst = true
    private var query = LogQuery()

    /** A burst of keystrokes is one re-filter: typing never touches the disk, and rarely the list. */
    private val refilter = Runnable {
        syncQuery()
        applyQuery()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.log_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.log_screen_title)

        list = findViewById(R.id.log_list)
        loading = findViewById(R.id.log_loading)
        message = findViewById(R.id.log_message)
        error = findViewById(R.id.log_error)
        showing = findViewById(R.id.log_showing)
        search = findViewById(R.id.log_search)
        sortToggle = findViewById(R.id.log_sort)
        levelFilter = findViewById(R.id.log_level_filter)
        kindFilter = findViewById(R.id.log_kind_filter)
        accountFilter = findViewById(R.id.log_account_filter)

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.addOnScrollListener(revealAsTheEndNears())

        levelFilter.adapter = labels(levels.map { it?.name ?: getString(R.string.log_level_all) })
        kindFilter.adapter = labels(kinds.map { it?.name ?: getString(R.string.log_kind_all) })
        accountFilter.adapter = labels(accounts.map { it ?: getString(R.string.log_account_all) })

        levelFilter.onItemSelectedListener = selected { onQueryChanged() }
        kindFilter.onItemSelectedListener = selected { onQueryChanged() }
        accountFilter.onItemSelectedListener = selected { onQueryChanged() }

        sortToggle.setOnClickListener {
            newestFirst = !newestFirst
            sortToggle.setText(if (newestFirst) R.string.log_sort_newest else R.string.log_sort_oldest)
            onQueryChanged()
        }
        sortToggle.setText(if (newestFirst) R.string.log_sort_newest else R.string.log_sort_oldest)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(text: Editable?) {
                main.removeCallbacks(refilter)
                main.postDelayed(refilter, SEARCH_DELAY_MS)
            }
        })

        // What the log does and does not hold is read once, so the caption keeps the rest of the
        // screen for the entries and opens when it is asked for.
        val note = findViewById<TextView>(R.id.log_note)
        note.setOnClickListener {
            val opening = note.maxLines != NOTE_OPEN_LINES
            note.maxLines = if (opening) NOTE_OPEN_LINES else NOTE_LINES
            note.ellipsize = if (opening) null else TextUtils.TruncateAt.END
        }

        read()
    }

    /** Back, in every way it can be asked for: the arrow, the gesture and the button all finish. */
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, SHARE, Menu.NONE, R.string.share).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(Menu.NONE, CLEAR, Menu.NONE, R.string.clear).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        SHARE -> {
            share()
            true
        }

        CLEAR -> {
            clear()
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        main.removeCallbacks(refilter)
        executor.shutdown()
        super.onDestroy()
    }

    /**
     * The file is read once, on the executor, and every outcome is a state the screen can be in:
     * entries, nothing recorded, or a failure that says what failed. A sync log is the only record
     * of what syncs did, so a read that breaks is something to show, not an empty list.
     */
    private fun read() {
        entries = null
        failure = null
        matched = emptyList()
        revealed = 0
        render()

        executor.execute {
            val outcome = try {
                Result.success(SyncLog(this).read())
            } catch (e: Exception) {
                Result.failure(e)
            }
            main.post {
                if (isFinishing || isDestroyed) return@post
                entries = outcome.getOrNull()
                failure = outcome.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }

                // Which Accounts can be filtered on is a property of what the log holds, so the
                // selector is only answerable once the file has been read.
                accounts = listOf(null) + entries.orEmpty().mapNotNull { it.account }.distinct()
                accountFilter.adapter = labels(accounts.map { it ?: getString(R.string.log_account_all) })

                syncQuery()
                applyQuery()
            }
        }
    }

    private fun clear() {
        executor.execute {
            SyncLog(this).clear()
            main.post { if (!isFinishing && !isDestroyed) read() }
        }
    }

    private fun onQueryChanged() {
        syncQuery()
        applyQuery()
    }

    /** The controls are read as positions, never as a copy that a re-populated selector could stale. */
    private fun syncQuery() {
        query = LogQuery(
            search = search.text.toString(),
            level = levels.getOrNull(levelFilter.selectedItemPosition),
            kind = kinds.getOrNull(kindFilter.selectedItemPosition),
            account = accounts.getOrNull(accountFilter.selectedItemPosition),
            newestFirst = newestFirst,
        )
    }

    /** Re-answers the question from the entries already in memory, and starts the reveal over. */
    private fun applyQuery() {
        matched = entries?.let { query.select(it) } ?: emptyList()
        revealed = minOf(REVEAL_PAGE, matched.size)
        // A new question is read from its first answer: the reader narrowing to the newest errors
        // wants the top of the new list, not wherever the previous one happened to be scrolled to.
        list.scrollToPosition(0)
        render()
    }

    /**
     * One state at a time, and never one state standing in for another.
     *
     * Still reading, nothing recorded, nothing matching this filter and a read that failed are four
     * answers; the last of them is the one that must never look like the second, because "the log is
     * empty" and "the log cannot be read" call for different things from the person reading it.
     */
    private fun render() {
        val failed = failure
        val all = entries
        val listing = failed == null && all != null && matched.isNotEmpty()

        loading.visibility = if (failed == null && all == null) View.VISIBLE else View.GONE

        error.visibility = if (failed == null) View.GONE else View.VISIBLE
        if (failed != null) error.text = getString(R.string.log_read_failed, failed)

        // Read, but with nothing to show: "nothing recorded yet" and "nothing matches this filter"
        // share one place, and the words are what tell them apart.
        val silent = failed == null && all != null && matched.isEmpty()
        message.visibility = if (silent) View.VISIBLE else View.GONE
        if (silent) {
            message.setText(if (all.isNullOrEmpty()) R.string.log_empty else R.string.log_filter_empty)
        }

        list.visibility = if (listing) View.VISIBLE else View.GONE
        adapter.submitList(if (listing) matched.take(revealed) else emptyList())

        val description = filterDescription()
        showing.visibility = if (description == null) View.GONE else View.VISIBLE
        if (description != null) showing.text = getString(R.string.log_showing, description)
    }

    /**
     * A page at a time, as the reader comes near the end of what is bound.
     *
     * The ring buffer holds at most 200 entries today, so in practice the first page is the whole
     * log and this never fires twice — it is here so that a raised cap would mean a slower list
     * rather than one that binds everything at once, and it costs an integer to keep correct.
     */
    private fun revealAsTheEndNears() = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (dy <= 0) return
            val layout = recyclerView.layoutManager as? LinearLayoutManager ?: return
            if (layout.findLastVisibleItemPosition() >= revealed - REVEAL_EARLY) revealMore()
        }
    }

    private fun revealMore() {
        if (revealed >= matched.size) return
        revealed = minOf(revealed + REVEAL_PAGE, matched.size)
        adapter.submitList(matched.take(revealed))
    }

    /** Null when nothing is narrowed, so an unfiltered view says nothing about filtering. */
    private fun filterDescription(): String? = listOfNotNull(
        query.level?.let { getString(R.string.log_filter_level, it.name) },
        query.kind?.let { getString(R.string.log_filter_kind, it.name) },
        query.account?.let { getString(R.string.log_filter_account, it) },
        query.search.trim().takeIf { it.isNotEmpty() }?.let { getString(R.string.log_filter_search, it) },
    ).takeIf { it.isNotEmpty() }?.joinToString(" \u00b7 ")

    /**
     * What Share hands over: the same entries the screen is showing, rendered the same way, with the
     * same description line.
     *
     * Two things are deliberate. Every entry the filter matched travels, not only the pages the
     * chunked reveal has bound so far — the reveal is a rendering limit, not part of the question
     * the reader asked. And when there is nothing to show, the screen's own words travel, so a
     * shared log is never silently blank.
     */
    private fun export(): String = buildString {
        val state = stateMessage()
        if (state != null) {
            append(state)
            return@buildString
        }
        filterDescription()?.let { append(getString(R.string.log_showing, it)).append("\n\n") }
        matched.forEachIndexed { index, entry ->
            if (index > 0) append("\n\n")
            append(logHeadline(this@LogsActivity, entry))
            // Indented as the dialog's text was, so a shared log reads the way it always read.
            logDetails(this@LogsActivity, entry).forEach { append("\n    ").append(it) }
        }
    }

    /** What the screen says when it has no entries to list, or null when it is listing them. */
    private fun stateMessage(): String? {
        val failed = failure
        val all = entries
        return when {
            failed != null -> getString(R.string.log_read_failed, failed)
            all == null -> getString(R.string.log_loading)
            all.isEmpty() -> getString(R.string.log_empty)
            matched.isEmpty() -> getString(R.string.log_filter_empty)
            else -> null
        }
    }

    private fun share() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.log_title))
            putExtra(Intent.EXTRA_TEXT, export())
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun labels(items: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun selected(onChange: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onChange()

        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    private companion object {
        /** The ring buffer's own cap makes this the whole log today; see [revealAsTheEndNears]. */
        const val REVEAL_PAGE = 50

        /** Reveal early enough that a fling does not stall on the page boundary. */
        const val REVEAL_EARLY = 5

        const val SEARCH_DELAY_MS = 250L

        /** The caption's closed height, which the layout also declares. */
        const val NOTE_LINES = 2
        const val NOTE_OPEN_LINES = Int.MAX_VALUE

        const val SHARE = 1
        const val CLEAR = 2
    }
}

/**
 * The question the reader is asking of the log: what to match, and in which direction.
 *
 * The entries sit in the file oldest first, so that is the order they arrive in; newest first is the
 * default because the last thing the app did is the first thing a failure is looked for in.
 * Direction is a view of the same list rather than a different list, which is what lets a search, a
 * filter and a direction compose in any order and still answer the one question they were all
 * narrowing.
 */
internal data class LogQuery(
    val search: String = "",
    val level: SyncLog.Level? = null,
    val kind: SyncLog.Kind? = null,
    val account: String? = null,
    val newestFirst: Boolean = true,
) {

    /**
     * The entries that match, in the direction asked for.
     *
     * The search covers the fields a reader has in hand: what the entry says happened, the Account
     * it happened to, the Collection it happened against, and the two pieces of the server's own
     * answer — the DAV condition and the first body line — that a question about a specific failure
     * is usually phrased in. It is case-insensitive and substring, because a log is searched with a
     * fragment someone half remembers.
     */
    fun select(entries: List<SyncLog.Entry>): List<SyncLog.Entry> {
        val needle = search.trim()
        val matched = entries.filter { matches(it, needle) }
        return if (newestFirst) matched.asReversed() else matched
    }

    private fun matches(entry: SyncLog.Entry, needle: String): Boolean {
        if (level != null && entry.level != level) return false
        if (kind != null && entry.kind != kind) return false
        if (account != null && entry.account != account) return false
        if (needle.isEmpty()) return true
        return entry.summary.mentions(needle) ||
            entry.account.mentions(needle) ||
            entry.displayName.mentions(needle) ||
            entry.collectionId.mentions(needle) ||
            entry.davCondition.mentions(needle) ||
            entry.firstBodyLine.mentions(needle)
    }

    private fun String?.mentions(needle: String): Boolean = this != null && contains(needle, ignoreCase = true)
}
