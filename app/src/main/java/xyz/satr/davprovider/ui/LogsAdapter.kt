package xyz.satr.davprovider.ui

import android.content.Context
import android.graphics.Typeface
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import xyz.satr.davprovider.R

/**
 * The entries as rows.
 *
 * A [ListAdapter] rather than one TextView holding the whole log: a ring buffer read at volume is
 * read by scrolling, filtering and sorting, and every one of those is a change to a list of rows
 * rather than a rebuild of a paragraph. Diffing the entries already in memory is what makes a
 * filter change repaint only the rows that actually moved.
 */
internal class LogsAdapter : ListAdapter<SyncLog.Entry, LogsAdapter.Row>(DIFFERENCE) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Row =
        Row(LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false))

    override fun onBindViewHolder(holder: Row, position: Int) = holder.bind(getItem(position))

    internal class Row(view: View) : RecyclerView.ViewHolder(view) {
        private val head: TextView = view.findViewById(R.id.log_head)
        private val detail: TextView = view.findViewById(R.id.log_detail)

        init {
            // The facts start under the headline's own text rather than under its timestamp, and the
            // width of that column is measured in the headline's font rather than guessed in dp: the
            // two line up because they are the same monospace at the same size, and they keep lining
            // up if either ever changes.
            detail.setPaddingRelative(
                head.paint.measureText(TIME_UNREAD + COLUMN).toInt(),
                detail.paddingTop,
                detail.paddingEnd,
                detail.paddingBottom,
            )
        }

        fun bind(entry: SyncLog.Entry) {
            val context = itemView.context
            head.text = logHeadline(context, entry)
            detail.text = logDetails(context, entry).joinToString("\n")
        }
    }

    private companion object {
        /**
         * An entry is a line in a file and carries no identity of its own, so a row is the same row
         * only when it is literally the same entry object. Narrowing re-uses the entries already
         * read, which makes identity exactly the key the diff needs; entries read afresh after a
         * Clear are new objects, which rebinds the list, and should.
         */
        val DIFFERENCE = object : DiffUtil.ItemCallback<SyncLog.Entry>() {
            override fun areItemsTheSame(oldItem: SyncLog.Entry, newItem: SyncLog.Entry): Boolean =
                oldItem === newItem

            override fun areContentsTheSame(oldItem: SyncLog.Entry, newItem: SyncLog.Entry): Boolean =
                oldItem == newItem
        }
    }
}

/**
 * The headline as the columns of a terminal line: when, the level, then which kind, which Account,
 * which authority and which Collection. It is columns rather than a sentence because a screen of
 * entries is read down one of them, and that column is the second.
 *
 * The level is the only thing coloured — the rest of the line takes the output colour from the row's
 * own layout — because a hundred lines are read by scanning for the red one.
 *
 * A line this build cannot read has no time to put in the first column; the column is held open
 * rather than closed up, because the one column the reader scans must not move depending on which
 * rows happen to be readable.
 */
internal fun logHeadline(context: Context, entry: SyncLog.Entry): CharSequence {
    val head = SpannableStringBuilder()
    head.append(entry.at?.let { logTimestamp(it) } ?: TIME_UNREAD)
    head.append(COLUMN)
    val start = head.length
    head.append(entry.level.name)
    head.setSpan(StyleSpan(Typeface.BOLD), start, head.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    head.setSpan(
        ForegroundColorSpan(logLevelColor(context, entry)),
        start,
        head.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
    )
    head.append(COLUMN).append(
        listOfNotNull(
            context.getString(
                when (entry.kind) {
                    SyncLog.Kind.SYNC -> R.string.log_kind_sync
                    SyncLog.Kind.DISCOVERY -> R.string.log_kind_discovery
                    SyncLog.Kind.UNPARSED -> R.string.log_kind_unparsed
                },
            ),
            entry.account,
            entry.authority?.let { logAuthorityLabel(context, it) },
            entry.displayName ?: entry.collectionId,
        ).joinToString(COLUMN),
    )
    return head
}

/**
 * The facts under the level: the summary first, then the evidence in the order the Details expander
 * uses, so a log entry and an error dialog read the same way. A field nothing observed is left out
 * rather than shown as empty — "certificate offered: none" would be a third answer to a question
 * that has two.
 *
 * An entry this build cannot read is the one whose summary *is* the evidence: its line is shown
 * verbatim rather than dropped, because a log that hides the entry someone came for is worse than
 * one with a raw line in it.
 */
internal fun logDetails(context: Context, entry: SyncLog.Entry): List<String> = buildList {
    entry.summary.takeIf { it.isNotEmpty() }?.let { add(it) }
    entry.httpStatus?.let { add(context.getString(R.string.details_status, it.toString())) }
    entry.firstBodyLine?.let { add(context.getString(R.string.details_body, it)) }
    entry.certificateOffered?.let { offered ->
        add(
            context.getString(
                R.string.details_certificate,
                context.getString(if (offered) R.string.value_yes else R.string.value_no),
            ),
        )
    }
    entry.method?.let { add(context.getString(R.string.details_method, it)) }
    entry.errorClass?.let { add(context.getString(R.string.details_class, it.name)) }
    entry.davCondition?.let { add(context.getString(R.string.details_condition, it)) }
    val written = entry.written
    val deleted = entry.deleted
    if (written != null && deleted != null) add(context.getString(R.string.log_counts, written, deleted))
    if (entry.unchanged == true) add(context.getString(R.string.log_unchanged))
}

/**
 * The colour of the level designator: the three the reader scans for, and the dim colour for a line
 * nobody can read.
 *
 * That last case is why this takes the entry rather than its level. An unreadable line carries
 * [SyncLog.Level.WARN] — the reading that invents least — so colouring it from its level would
 * paint it yellow and claim the log called it a warning, which is the one thing the log did not
 * say. Dim is what it is: a raw line the log kept as evidence.
 */
internal fun logLevelColor(context: Context, entry: SyncLog.Entry): Int = when {
    entry.kind == SyncLog.Kind.UNPARSED -> ContextCompat.getColor(context, R.color.term_dim)
    entry.level == SyncLog.Level.ERROR -> ContextCompat.getColor(context, R.color.level_error)
    entry.level == SyncLog.Level.WARN -> ContextCompat.getColor(context, R.color.level_warn)
    else -> ContextCompat.getColor(context, R.color.level_info)
}

private fun logTimestamp(at: Instant): String = LOG_TIMESTAMP.format(at.atZone(ZoneId.systemDefault()))

/** The authority a Collection belongs to, named the way the rest of the app names it. */
private fun logAuthorityLabel(context: Context, authority: String): String = when (authority) {
    ContactsContract.AUTHORITY -> context.getString(R.string.log_authority_contacts)
    CalendarContract.AUTHORITY -> context.getString(R.string.log_authority_calendar)
    else -> authority
}

/** Sortable and unambiguous in the device's own zone, which is the zone the user reads it in. */
private const val TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss"

private val LOG_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern(TIMESTAMP_PATTERN, Locale.US)

/** Two spaces between columns, which is what makes them columns rather than words. */
private const val COLUMN = "  "

/**
 * The first column on a line that has no time of its own, held open at the timestamp's own width so
 * that the level column stays the same place on every row. It is the width of the pattern rather
 * than a count of characters because the pattern is what defines the column.
 */
private val TIME_UNREAD = " ".repeat(TIMESTAMP_PATTERN.length)
