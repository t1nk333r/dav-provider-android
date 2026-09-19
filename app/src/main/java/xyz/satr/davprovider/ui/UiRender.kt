package xyz.satr.davprovider.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import android.view.View
import android.widget.Toast
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import xyz.satr.davprovider.R
import xyz.satr.davprovider.core.ClientCertificateInfo
import xyz.satr.davprovider.core.ClientCertificateSource
import xyz.satr.davprovider.core.CollectionType
import xyz.satr.davprovider.core.DavCollection
import xyz.satr.davprovider.core.SyncError

/** Shared rendering rules, so both screens describe the same facts the same way. */

private const val MAX_BODY_LINE = 300
private const val MAX_CAUSE = 200

/**
 * `displayname` when the server gave one, otherwise the URL's last segment, verbatim.
 *
 * An opaque name is shown as it is: a UUID dressed up as something friendly removes the user's
 * only means of noticing that the wrong Collection is selected.
 */
internal fun collectionTitle(collection: DavCollection): String =
    collection.displayName?.takeIf { it.isNotBlank() } ?: urlLastSegment(collection.url)

internal fun urlLastSegment(url: String): String {
    val trimmed = url.substringBefore('?').substringBefore('#').trimEnd('/')
    return trimmed.substringAfterLast('/').ifEmpty { trimmed }
}

internal fun collectionTypeLabel(context: Context, type: CollectionType): String = context.getString(
    when (type) {
        CollectionType.ADDRESS_BOOK -> R.string.type_addressbook
        CollectionType.CALENDAR -> R.string.type_calendar
    },
)

internal fun statusLabel(context: Context, status: AccountStatus): String = context.getString(
    when (status) {
        AccountStatus.OK -> R.string.status_ok
        AccountStatus.PARTIAL -> R.string.status_partial
        AccountStatus.FAILED -> R.string.status_failed
        AccountStatus.NEVER_SYNCED -> R.string.status_never_synced
    },
)

internal fun statusDetail(context: Context, report: AccountReport?): String {
    if (report == null) return context.getString(R.string.status_detail_never_synced)
    // A run-level failure is the reason for the status, and counts of zero would hide it. The
    // failing authority's, not the last run's: the last run may be the one that succeeded.
    report.composedSummary?.let { return it }
    val considered = report.collections.filter { it.outcome != CollectionOutcome.SKIPPED }
    val failed = considered.count { it.outcome == CollectionOutcome.FAILED }
    return when (report.composedStatus) {
        AccountStatus.OK -> context.getString(R.string.status_detail_ok)
        AccountStatus.PARTIAL -> context.getString(R.string.status_detail_partial, failed, considered.size)
        AccountStatus.FAILED -> context.getString(R.string.status_detail_failed, considered.size)
        AccountStatus.NEVER_SYNCED -> context.getString(R.string.status_detail_never_synced)
    }
}

internal fun lastSyncLabel(context: Context, atMillis: Long): String =
    if (atMillis <= 0L) {
        context.getString(R.string.last_sync_never)
    } else {
        context.getString(R.string.last_sync, DateUtils.getRelativeTimeSpanString(atMillis))
    }

/**
 * §8's interval as the card and the chooser say it.
 *
 * A value nothing offers is spelled in minutes rather than replaced with the default: a build that
 * offered more intervals, or one that offered fewer, still leaves the Account on the interval it
 * was actually given, and a row that named a different one would describe a schedule the framework
 * does not have.
 */
internal fun intervalLabel(context: Context, seconds: Long): String {
    val label = intervalLabelResource(seconds)
    return if (label == null) {
        context.getString(R.string.interval_minutes, seconds / 60)
    } else {
        context.getString(label)
    }
}

/**
 * The string one interval is known by, or null when nothing offers it.
 *
 * Separate from [intervalLabel] so that "every interval the chooser offers has a name of its own"
 * is checkable without a device: two options that rendered identically would be a chooser whose
 * answer the user cannot predict.
 */
internal fun intervalLabelResource(seconds: Long): Int? = when (seconds) {
    15L * 60 -> R.string.interval_15_minutes
    30L * 60 -> R.string.interval_30_minutes
    60L * 60 -> R.string.interval_hourly
    2L * 60 * 60 -> R.string.interval_2_hours
    6L * 60 * 60 -> R.string.interval_6_hours
    24L * 60 * 60 -> R.string.interval_daily
    else -> null
}

/**
 * A client certificate's expiry as a plain date, in the device's own zone.
 *
 * A date and not a verdict: an expired certificate is reported and offered, never refused, because
 * whether one is still accepted is the server's answer to give — the app has no way to know, and a
 * refusal here would take away the only way to find out.
 */
internal fun certificateDate(notAfter: Long): String =
    DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(notAfter).atZone(ZoneId.systemDefault()))

/**
 * The Account's client certificate as one line, for the settings screen and for the setup screen's
 * own status line. Null when the Account has none, so a caller can leave its row out.
 *
 * The two sources are named apart on purpose: a KeyChain alias that no longer resolves and an
 * archive that is gone after a restore look identical in a TLS failure, and telling them apart is
 * the whole reason both are offered.
 */
internal fun certificateLine(
    context: Context,
    source: ClientCertificateSource?,
    info: ClientCertificateInfo?,
): String? = when (source) {
    null -> null
    is ClientCertificateSource.KeyChainAlias -> context.getString(R.string.certificate_keychain, source.alias)
    ClientCertificateSource.Imported -> when {
        info == null -> context.getString(R.string.certificate_imported_missing)
        info.expired -> context.getString(
            R.string.certificate_imported_expired,
            info.subject,
            certificateDate(info.notAfter),
        )

        else -> context.getString(
            R.string.certificate_imported_valid,
            info.subject,
            certificateDate(info.notAfter),
        )
    }
}

/** What an imported identity says about itself, one fact per line. */
internal fun certificateDetails(context: Context, info: ClientCertificateInfo): String = listOf(
    context.getString(R.string.certificate_subject, info.subject),
    context.getString(R.string.certificate_issuer, info.issuer),
    context.getString(
        if (info.expired) R.string.certificate_expired else R.string.certificate_expires,
        certificateDate(info.notAfter),
    ),
).joinToString("\n")

/**
 * The Details expander's text: what was observed, verbatim, in a fixed order, so that a support
 * conversation can start from it. Nothing here is inferred — "Certificate offered: no" is a report
 * that the KeyChain alias callback never fired, not a claim about what the server wanted.
 */
internal fun errorDetails(context: Context, error: SyncError): String = buildString {
    appendLine(
        context.getString(
            R.string.details_status,
            error.httpStatus?.toString() ?: context.getString(R.string.value_no_response),
        ),
    )
    appendLine(context.getString(R.string.details_body, error.firstBodyLine ?: context.getString(R.string.value_none)))
    appendLine(
        context.getString(
            R.string.details_certificate,
            context.getString(if (error.certificateOffered) R.string.value_yes else R.string.value_no),
        ),
    )
    appendLine(context.getString(R.string.details_method, error.requestMethod ?: context.getString(R.string.value_none)))
    appendLine(context.getString(R.string.details_class, error.errorClass.name))
    error.davCondition?.let { appendLine(context.getString(R.string.details_condition, it)) }
    error.cause?.let { cause ->
        val message = cause.message?.let { clip(it, MAX_CAUSE) }.orEmpty()
        appendLine(context.getString(R.string.details_cause, cause.javaClass.simpleName + ": " + message))
    }
}.trimEnd()

/** One row of the Diagnose matrix: the observable facts first, then the classifier's reading. */
internal fun diagnoseRow(context: Context, outcome: ProbeOutcome): String = buildString {
    appendLine(
        context.getString(
            R.string.diagnose_row_head,
            outcome.evidence.httpStatus?.toString() ?: context.getString(R.string.value_no_response),
            context.getString(if (outcome.evidence.certificateOffered) R.string.value_yes else R.string.value_no),
            outcome.error.requestMethod ?: context.getString(R.string.value_none),
        ),
    )
    appendLine(outcome.error.summary)
    outcome.error.firstBodyLine?.let { appendLine(context.getString(R.string.details_body, clip(it, MAX_BODY_LINE))) }
}.trimEnd()

internal fun applyCollectionColor(view: View, color: Int?) {
    view.setBackgroundColor(color ?: view.context.getColor(android.R.color.darker_gray))
}

internal fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
}

private fun clip(text: String, max: Int): String = if (text.length <= max) text else text.take(max) + "\u2026"
