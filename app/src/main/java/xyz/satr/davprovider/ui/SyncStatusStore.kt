package xyz.satr.davprovider.ui

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import xyz.satr.davprovider.core.ErrorClass
import xyz.satr.davprovider.sync.SyncDeferralRecorder
import xyz.satr.davprovider.sync.SyncPreferences

/** How one Collection came out of the last run. [SKIPPED] is §5 class 3: information, not failure. */
internal enum class CollectionOutcome { OK, FAILED, SKIPPED }

internal data class CollectionReport(
    val collectionId: String,
    val outcome: CollectionOutcome,
    val errorClass: ErrorClass? = null,
    val summary: String? = null,
)

internal enum class AccountStatus { OK, PARTIAL, FAILED, NEVER_SYNCED }

/**
 * One authority's verdict from its most recent run.
 *
 * [status] and [summary] are the sync engine's per-run rollup, which knows about a terminal class
 * that stopped the run and about a failure that never reached a Collection. They are kept per
 * authority because the two authorities run separately and each is entitled to its own answer.
 */
internal data class AuthorityReport(
    val status: AccountStatus,
    val summary: String? = null,
)

/**
 * The last run of one account: an Account-wide rollup plus one entry per Collection, because one
 * broken Collection must never hide the state of the others.
 *
 * [summary] carries a run-level failure — the account vanished mid-run, the HTTP client could not
 * be built, or a terminal credential failure stopped the remaining Collections — which is the only
 * explanation when no Collection reports anything.
 *
 * [deferred] is §8's waiting state, and the one field here that no run produced: the last automatic
 * run did no work because the Account only syncs on unmetered networks. It survives leaving the
 * screen, and the next run that does happen clears it.
 *
 * [lastAutomaticAt], [missedSlots] and [batteryPromptDismissed] are §8's evidence that the schedule
 * is or is not being kept. Zero means "no automatic run recorded yet", the same convention
 * [lastSyncAt] uses for never, and no real timestamp is ever zero.
 */
internal data class AccountReport(
    val status: AccountStatus,
    val lastSyncAt: Long,
    val collections: List<CollectionReport>,
    val summary: String? = null,
    val deferred: Boolean = false,
    val authorities: Map<String, AuthorityReport> = emptyMap(),
    val lastAutomaticAt: Long = 0L,
    val missedSlots: Int = 0,
    val batteryPromptDismissed: Boolean = false,
) {
    /**
     * The verdict to display.
     *
     * The worst authority, not the last one to run: contacts and calendars finish at different
     * moments, and letting the later one speak meant a failed calendar was reported as "All
     * Collections synced" whenever contacts happened to succeed afterwards. The card then
     * contradicted the Collection line printed directly beneath it.
     */
    val composedStatus: AccountStatus
        get() = authorities.values.maxByOrNull { it.status.severity }?.status ?: status

    /** The reason to display: the failing authority's, which the last run may not be. */
    val composedSummary: String?
        get() = authorities.values
            .filter { it.status.severity > AccountStatus.OK.severity }
            .maxByOrNull { it.status.severity }
            ?.summary
            ?: summary
}

/**
 * How bad a status is, so that two authorities can be compared rather than ordered by when they
 * happened to run. NEVER_SYNCED sits below OK because an authority that has not reported says
 * nothing about the account.
 */
internal val AccountStatus.severity: Int
    get() = when (this) {
        AccountStatus.NEVER_SYNCED -> 0
        AccountStatus.OK -> 1
        AccountStatus.PARTIAL -> 2
        AccountStatus.FAILED -> 3
    }

/**
 * Per-Account sync status, kept in AccountManager userdata next to the account record.
 *
 * It lives here rather than in a file of its own so that it shares the account's lifetime: the
 * status can never outlive the account it describes, and never describes an account twice.
 * Only §5 fields are stored — the classifier's summary, the class, the status and the method —
 * never a response body, a header value or a password.
 *
 * The battery-optimisation dismissal is stored here for the same reason: it is a decision about one
 * Account's schedule, and an Account that is removed must take it along rather than leave a prompt
 * the user already answered to be shown again for a different server.
 *
 * The rollup is the sync engine's, not this store's: it is the side that knows about terminal
 * classes and about a run that stopped early.
 */
internal class SyncStatusStore(context: Context) : SyncDeferralRecorder {

    private val appContext = context.applicationContext
    private val manager = AccountManager.get(appContext)

    /** Null when nothing has been recorded, or when the record cannot be read. */
    fun read(account: Account): AccountReport? {
        val raw = manager.getUserData(account, KEY) ?: return null
        return runCatching { AccountReportJson.decode(raw) }.getOrNull()
    }

    /**
     * A run happened, so this carries the truth and any earlier deferral is over. That is what makes
     * a manual sync — or the next run on Wi-Fi — clear the waiting state without anyone asking.
     *
     * What the run does to §8's evidence depends on whether the framework or the user started it,
     * which is why [mergeRun] is asked rather than told.
     */
    fun record(
        account: Account,
        authority: String,
        atMillis: Long,
        status: AccountStatus,
        summary: String?,
        collections: List<CollectionReport>,
        automatic: Boolean,
    ) {
        update(account) { previous ->
            mergeRun(
                previous = previous,
                authority = authority,
                atMillis = atMillis,
                status = status,
                summary = summary,
                collections = collections,
                automatic = automatic,
                intervalSeconds = intervalSeconds(account),
            )
        }
    }

    /**
     * §8: an automatic run did nothing, and the user can see it.
     *
     * Everything a run recorded is kept — the status and the last sync time stay the last run's —
     * because the deferral says nothing about how the Account last fared. An Account that has never
     * completed a run still gets a record, since "not synced yet, waiting for Wi-Fi" is exactly the
     * state that must not be silent.
     */
    override fun recordDeferred(account: Account) {
        update(account) { previous ->
            mergeDeferral(
                previous = previous,
                atMillis = System.currentTimeMillis(),
                intervalSeconds = intervalSeconds(account),
            )
        }
    }

    /**
     * §8's permanent dismissal of the battery-optimisation prompt.
     *
     * Written where the evidence lives, so it lasts exactly as long as the Account does: the prompt
     * is offered once per Account and never again, and the record it silences goes away with the
     * Account rather than outliving it.
     */
    fun dismissBatteryPrompt(account: Account) {
        read(account)?.let { write(account, it.copy(batteryPromptDismissed = true)) }
    }

    fun clear(account: Account) {
        manager.setUserData(account, KEY, null)
    }

    /**
     * The one place the report is changed, so every change is read-merge-written under one lock.
     *
     * Contacts and calendar are different adapters and finish in this process concurrently, and the
     * report is a single userdata key: without this, two finishes landing together could drop one
     * authority's verdict from the merged record — which is the very thing the merge keeps an
     * `authorities` map for, so losing it loses half of what the account screen can say.
     *
     * The merge runs inside the lock rather than before it: reading, merging and writing are one
     * decision, and splitting them is how the second writer overwrites the first.
     */
    private fun update(account: Account, merge: (AccountReport?) -> AccountReport) {
        synchronized(LOCK) {
            write(account, merge(read(account)))
        }
    }

    private fun write(account: Account, report: AccountReport) {
        manager.setUserData(account, KEY, AccountReportJson.encode(report))
    }

    private fun intervalSeconds(account: Account): Long =
        SyncPreferences(appContext).intervalSeconds(account)

    private companion object {
        const val KEY = "dav_sync_status_v1"

        val LOCK = Any()
    }
}

/**
 * The record's shape on disk, and the only thing that knows it.
 *
 * [encode] and [decode] name no Android type, so the half worth arguing about — the arithmetic the
 * record carries and what an older record means — can be exercised without a device. Records on
 * people's devices are written by other builds: a field added here is absent there, and decoding one
 * is not an error.
 */
internal object AccountReportJson {

    private const val KEY_STATUS = "status"
    private const val KEY_LAST_SYNC = "lastSyncAt"
    private const val KEY_DEFERRED = "deferred"
    private const val KEY_COLLECTIONS = "collections"
    private const val KEY_COLLECTION_ID = "id"
    private const val KEY_OUTCOME = "outcome"
    private const val KEY_ERROR_CLASS = "errorClass"
    private const val KEY_SUMMARY = "summary"
    private const val KEY_AUTHORITIES = "authorities"
    private const val KEY_LAST_AUTOMATIC = "lastAutomaticAt"
    private const val KEY_MISSED_SLOTS = "missedSlots"
    private const val KEY_BATTERY_DISMISSED = "batteryPromptDismissed"

    fun encode(report: AccountReport): String {
        val collections = JSONArray()
        report.collections.forEach { entry ->
            collections.put(
                JSONObject().apply {
                    put(KEY_COLLECTION_ID, entry.collectionId)
                    put(KEY_OUTCOME, entry.outcome.name)
                    entry.errorClass?.let { put(KEY_ERROR_CLASS, it.name) }
                    entry.summary?.let { put(KEY_SUMMARY, it) }
                },
            )
        }
        return JSONObject().apply {
            put(KEY_STATUS, report.status.name)
            put(KEY_LAST_SYNC, report.lastSyncAt)
            report.summary?.let { put(KEY_SUMMARY, it) }
            // Written only when set, so a record from before these fields existed decodes unchanged
            // — and so the text of a record says nothing it does not mean.
            if (report.deferred) put(KEY_DEFERRED, true)
            if (report.lastAutomaticAt > 0L) put(KEY_LAST_AUTOMATIC, report.lastAutomaticAt)
            if (report.missedSlots > 0) put(KEY_MISSED_SLOTS, report.missedSlots)
            if (report.batteryPromptDismissed) put(KEY_BATTERY_DISMISSED, true)
            put(KEY_COLLECTIONS, collections)
            if (report.authorities.isNotEmpty()) {
                put(
                    KEY_AUTHORITIES,
                    JSONObject().apply {
                        report.authorities.forEach { (authority, verdict) ->
                            put(
                                authority,
                                JSONObject().apply {
                                    put(KEY_STATUS, verdict.status.name)
                                    verdict.summary?.let { put(KEY_SUMMARY, it) }
                                },
                            )
                        }
                    },
                )
            }
        }.toString()
    }

    fun decode(raw: String): AccountReport {
        val root = JSONObject(raw)
        val collections = root.optJSONArray(KEY_COLLECTIONS) ?: JSONArray()
        val entries = ArrayList<CollectionReport>(collections.length())
        for (index in 0 until collections.length()) {
            val entry = collections.getJSONObject(index)
            entries += CollectionReport(
                collectionId = entry.getString(KEY_COLLECTION_ID),
                outcome = CollectionOutcome.valueOf(entry.getString(KEY_OUTCOME)),
                errorClass = entry.optString(KEY_ERROR_CLASS).takeIf { it.isNotEmpty() }
                    ?.let { ErrorClass.valueOf(it) },
                summary = entry.optString(KEY_SUMMARY).takeIf { it.isNotEmpty() },
            )
        }
        return AccountReport(
            status = AccountStatus.valueOf(root.getString(KEY_STATUS)),
            lastSyncAt = root.optLong(KEY_LAST_SYNC),
            collections = entries,
            summary = root.optString(KEY_SUMMARY).takeIf { it.isNotEmpty() },
            deferred = root.optBoolean(KEY_DEFERRED, false),
            authorities = decodeAuthorities(root.optJSONObject(KEY_AUTHORITIES)),
            // Absent in a record written before §8's evidence existed, and absent means none: the
            // Account simply has nothing recorded about the schedule yet, which is not a missed slot.
            lastAutomaticAt = root.optLong(KEY_LAST_AUTOMATIC),
            missedSlots = root.optInt(KEY_MISSED_SLOTS, 0),
            batteryPromptDismissed = root.optBoolean(KEY_BATTERY_DISMISSED, false),
        )
    }

    /**
     * A record written before the per-authority verdicts existed has none, and decoding one is not
     * an error: the top-level fields it does have remain the fallback the card renders until each
     * authority reports once.
     */
    private fun decodeAuthorities(raw: JSONObject?): Map<String, AuthorityReport> {
        if (raw == null) return emptyMap()
        val decoded = LinkedHashMap<String, AuthorityReport>(raw.length())
        raw.keys().forEach { authority ->
            val entry = raw.optJSONObject(authority) ?: return@forEach
            val status = runCatching { AccountStatus.valueOf(entry.getString(KEY_STATUS)) }
                .getOrNull() ?: return@forEach
            decoded[authority] = AuthorityReport(
                status = status,
                summary = entry.optString(KEY_SUMMARY).takeIf { it.isNotEmpty() },
            )
        }
        return decoded
    }
}

/**
 * Folds one run's per-Collection outcomes into what earlier runs recorded.
 *
 * Contacts and calendars are separate runs, and each reports only the Collections of its own
 * authority, so this has to accumulate rather than replace: replacing is what made a freshly synced
 * calendar read "not synced yet", because the contacts run that followed wrote a list its
 * Collection was not in. Collection ids come from the Collection URL, so the same id from either
 * authority is the same Collection and a later answer for one is simply newer.
 *
 * The order is previous-then-new so an id keeps its position and the list does not reshuffle as
 * authorities take turns.
 */
internal fun mergeCollectionReports(
    previous: List<CollectionReport>,
    reported: List<CollectionReport>,
): List<CollectionReport> {
    if (previous.isEmpty()) return reported
    val byId = LinkedHashMap<String, CollectionReport>(previous.size + reported.size)
    previous.forEach { byId[it.collectionId] = it }
    reported.forEach { byId[it.collectionId] = it }
    return byId.values.toList()
}

/**
 * The record after one run.
 *
 * §8's missed-slot evidence is decided here, and only from **automatic** runs: a manual run happens
 * whenever the user asks, so it is neither evidence that the schedule fired nor evidence that it did
 * not. It leaves the last automatic time and the count exactly as they were, which is what keeps a
 * "Sync now" between two missed slots from being read as the schedule recovering.
 */
internal fun mergeRun(
    previous: AccountReport?,
    authority: String,
    atMillis: Long,
    status: AccountStatus,
    summary: String?,
    collections: List<CollectionReport>,
    automatic: Boolean,
    intervalSeconds: Long,
): AccountReport = AccountReport(
    status = status,
    lastSyncAt = atMillis,
    collections = mergeCollectionReports(previous?.collections.orEmpty(), collections),
    summary = summary,
    authorities = previous?.authorities.orEmpty() + (authority to AuthorityReport(status, summary)),
    lastAutomaticAt = if (automatic) atMillis else previous?.lastAutomaticAt ?: 0L,
    missedSlots = if (automatic) {
        missedSlotsAfter(
            previousAutomaticAt = previous?.lastAutomaticAt ?: 0L,
            automaticAt = atMillis,
            intervalSeconds = intervalSeconds,
            previousMissedSlots = previous?.missedSlots ?: 0,
        )
    } else {
        previous?.missedSlots ?: 0
    },
    // Carried, never cleared: the dismissal is the user's answer to the one prompt, and only
    // removing the Account takes it back.
    batteryPromptDismissed = previous?.batteryPromptDismissed ?: false,
)

/**
 * The record after an automatic run deferred §8's unmetered-only rule.
 *
 * A deferral is a slot that fired: the framework asked, the Account's own setting answered. So it
 * moves the last automatic time rather than leaving it behind — otherwise the card would end up
 * accusing the system of suppressing syncs that this app's setting held back, which is the one
 * accusation it must never make.
 *
 * A manual run never reaches here: the pre-flight lets the user's own request through before it
 * considers the setting at all.
 */
internal fun mergeDeferral(
    previous: AccountReport?,
    atMillis: Long,
    intervalSeconds: Long,
): AccountReport = AccountReport(
    status = previous?.status ?: AccountStatus.NEVER_SYNCED,
    lastSyncAt = previous?.lastSyncAt ?: 0L,
    collections = previous?.collections.orEmpty(),
    summary = previous?.summary,
    deferred = true,
    authorities = previous?.authorities.orEmpty(),
    lastAutomaticAt = atMillis,
    missedSlots = missedSlotsAfter(
        previousAutomaticAt = previous?.lastAutomaticAt ?: 0L,
        automaticAt = atMillis,
        intervalSeconds = intervalSeconds,
        previousMissedSlots = previous?.missedSlots ?: 0,
    ),
    batteryPromptDismissed = previous?.batteryPromptDismissed ?: false,
)

/**
 * A gap has to exceed this many of the Account's own intervals before the slots inside it count as
 * missed. Twice: the framework's own scheduling has jitter, and a single late run is not evidence
 * of anything.
 */
internal const val MISSED_SLOT_MULTIPLE = 2

/** Missed slots before the account card offers §8's battery-optimisation exemption. */
internal const val MISSED_SLOT_PROMPT_THRESHOLD = 3

/**
 * §8's arithmetic: the missed count after an automatic run.
 *
 * Counted from the gap between automatic runs rather than observed one slot at a time, because a
 * suppressed schedule produces no runs to observe — the absence is the whole evidence. Every slot
 * inside the gap is added rather than one per run, so a schedule that is consistently a little late
 * reaches the threshold instead of being waved through as a small number each time.
 *
 * A run inside the first interval is not late at all, and one that arrived within
 * [MISSED_SLOT_MULTIPLE] intervals is jitter: it resets the count, because a run that arrived is
 * evidence the schedule is working and a count that survived it would keep accusing the system long
 * after it recovered.
 *
 * A clock that moved backwards, and the first automatic run an Account ever has, are both silent
 * rather than evidence: nothing can be compared, so nothing is concluded.
 */
internal fun missedSlotsAfter(
    previousAutomaticAt: Long,
    automaticAt: Long,
    intervalSeconds: Long,
    previousMissedSlots: Int,
): Int {
    if (previousAutomaticAt <= 0L || intervalSeconds <= 0L) return 0
    val gap = automaticAt - previousAutomaticAt
    if (gap <= 0L) return previousMissedSlots
    val skipped = gap / (intervalSeconds * 1000L) - (MISSED_SLOT_MULTIPLE - 1)
    if (skipped <= 0L) return 0
    return (previousMissedSlots + skipped).toInt()
}

/**
 * §8: whether the account card offers the battery-optimisation exemption.
 *
 * Three conditions, and all of them are necessary. There has to be evidence: the threshold is what
 * makes this a pattern rather than one late run, and the prompt says what was observed rather than
 * what it means. The user must not have dismissed it, because that answer is permanent and asking
 * twice is the nagging this rule exists to prevent. And the app must not already be exempt, since a
 * row asking for what is already granted asks the user to fix something that is not wrong.
 */
internal fun batteryExemptionAdvised(report: AccountReport?, alreadyExempt: Boolean): Boolean =
    report != null &&
        !alreadyExempt &&
        !report.batteryPromptDismissed &&
        report.missedSlots >= MISSED_SLOT_PROMPT_THRESHOLD
