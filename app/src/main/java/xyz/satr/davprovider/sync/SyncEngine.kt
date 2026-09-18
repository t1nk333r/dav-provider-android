package xyz.satr.davprovider.sync

import android.accounts.Account
import android.content.SyncResult
import io.ktor.http.Url
import xyz.satr.davprovider.core.AccountStore
import xyz.satr.davprovider.core.CollectionState
import xyz.satr.davprovider.core.CollectionType
import xyz.satr.davprovider.core.CredentialsUnreadableException
import xyz.satr.davprovider.core.DavAccount
import xyz.satr.davprovider.core.DavCollection
import xyz.satr.davprovider.core.DavHttpClientFactory
import xyz.satr.davprovider.core.ErrorClass
import xyz.satr.davprovider.core.ProviderMapper
import xyz.satr.davprovider.core.RemoteItem
import xyz.satr.davprovider.core.SyncError
import xyz.satr.davprovider.core.SyncErrorClassifier

/** §6 step 3 keeps traffic moving: a non-user-initiated sync stalling for 60s gets cancelled. */
internal const val MULTIGET_BATCH_SIZE = 50

/** §6: sync-collection support is re-probed weekly. */
private const val SYNC_COLLECTION_REPROBE_MS = 7L * 24 * 60 * 60 * 1000

/**
 * The error classes that abort the Account's remaining Collections.
 *
 * §6 names 1, 2, 4 and 5 — the failures that cannot improve by being asked again on the next
 * Collection. The other terminal classes (6, 12) are terminal for notification, not for the run;
 * they are still reported, per Collection.
 */
private val ACCOUNT_ABORTING_CLASSES = setOf(
    ErrorClass.PROXY_REJECTED_CREDENTIALS,
    ErrorClass.ORIGIN_WANTS_CREDENTIALS,
    ErrorClass.CERTIFICATE_UNAVAILABLE,
    ErrorClass.CREDENTIALS_UNREADABLE,
)

/**
 * Syncs one Account's Collections for one authority, following §6.
 *
 * Collections run sequentially, and each is self-contained: a failure that is not one of
 * [ACCOUNT_ABORTING_CLASSES] leaves the others alone.
 *
 * The three §6 invariants are enforced here rather than left to the mapper:
 *
 * 1. Rows may be written before a Collection completes; its CTag may not. The
 *    [ProviderMapper.writeState] carrying the new CTag is the last thing a Collection does, after
 *    every batch is committed — recording one early would make the next run skip the items that
 *    never arrived.
 * 2. Deletion runs only against a listing that completed, asserted in [deleteMissing]: it is the
 *    only invariant whose violation destroys user data rather than wasting traffic.
 * 3. Sync state lives with the data. Item ETags reach the mapper together with the bodies they
 *    describe, and Collection state goes through [ProviderMapper.readState]/[ProviderMapper.writeState];
 *    there is no local database that could outlive the provider's account cleanup.
 *
 * Retrying is the framework's: the outcome is expressed in the [SyncResult] counters and nothing
 * here loops.
 */
class SyncEngine(
    private val mapper: ProviderMapper,
    private val collectionType: CollectionType,
    private val accountStore: AccountStore,
    private val httpClientFactory: DavHttpClientFactory,
    private val classifier: SyncErrorClassifier,
    private val reporter: SyncReporter,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    suspend fun sync(
        account: Account,
        result: SyncResult,
        isCancelled: () -> Boolean = { false },
    ) {
        val outcomes = mutableListOf<CollectionOutcome>()

        try {
            ensureAccountRegistered(account)
        } catch (e: AccountVanishedException) {
            result.databaseError = true
            report(account, outcomes, aborted = true, error = null)
            return
        }

        val davAccount = try {
            accountStore.load(account)
        } catch (e: CredentialsUnreadableException) {
            // The Account survived a device restore; its Credentials did not. §5 class 5.
            val error = ErrorMapping(classifier, certificateOffered = false, certificateConfigured = false)
                .classify(e, method = null)
            record(result, error)
            report(account, outcomes, aborted = true, error = error)
            return
        }
        if (davAccount == null) {
            // Removed between the two reads, or its record was never completed.
            result.databaseError = true
            report(account, outcomes, aborted = true, error = null)
            return
        }

        val httpClient = try {
            httpClientFactory.create(davAccount)
        } catch (e: Exception) {
            val error = ErrorMapping(classifier, certificateOffered = false, certificateConfigured = false)
                .classify(e, method = null)
            record(result, error)
            report(account, outcomes, aborted = true, error = error)
            return
        }

        val http = openDavHttpSession(httpClient)
        var aborted = false
        try {
            for (collection in collectionsOf(davAccount)) {
                if (isCancelled()) break

                val outcome = syncCollection(account, davAccount, http, collection, result)
                outcomes += outcome

                if (outcome.abortsAccount) {
                    aborted = true
                    break
                }
            }
        } catch (e: AccountVanishedException) {
            // Everything written so far stands; the provider reaps it with the Account. What must not
            // happen is more writing, or a CTag claiming a Collection that will not be finished.
            result.databaseError = true
            aborted = true
        } finally {
            http.close()
        }

        report(account, outcomes, aborted = aborted, error = null)
    }

    /**
     * §6 steps 1–7 for one Collection.
     *
     * Where the server supports it, one REPORT collapses steps 1 and 2; otherwise the polling path
     * runs. Either way the Collection ends with the same steps 3–7.
     */
    private suspend fun syncCollection(
        account: Account,
        davAccount: DavAccount,
        http: DavHttpSession,
        collection: DavCollection,
        result: SyncResult,
    ): CollectionOutcome {
        val startedAt = clock()
        val state = mapper.readState(account, collection)
        val session = CollectionSession(http.client, collection, http::startOperation)
        val errors = errorMapping(davAccount, http)

        return try {
            // The answer this run got, carried into step 7's state write: the poll path must not
            // overwrite what the probe just learned.
            var supportsSyncCollection = state.supportsSyncCollection
            var capabilityCheckedAt = state.capabilityCheckedAt

            if (mayAttemptSyncCollection(state, startedAt)) {
                // A refusal is an answer worth remembering; a failure is not, so nothing is cached for
                // it and the next run probes again — §6's "re-probe after failure".
                val report = session.reportChanges(state.syncToken)

                if (report != null) {
                    return applyChanges(
                        account, collection, session, report.members,
                        fullListing = report.fullListing,
                        // A REPORT names no CTag; the last one we listed at is kept. A server whose
                        // CTag has moved on since then simply makes the next polling run list in full.
                        newCtag = state.ctag,
                        newToken = report.token,
                        supportsSyncCollection = true,
                        capabilityCheckedAt = startedAt,
                        result = result,
                    )
                }

                // The server answered `<supported-report/>`: remember it and poll like everyone else.
                cacheCapability(account, collection, state, supported = false, at = startedAt)
                supportsSyncCollection = false
                capabilityCheckedAt = startedAt
            }

            poll(account, collection, session, state, supportsSyncCollection, capabilityCheckedAt, result)
        } catch (e: AccountVanishedException) {
            throw e
        } catch (e: Exception) {
            val error = errors.classify(e, session.lastMethod)
            record(result, error)
            CollectionOutcome(collection, error = error)
        }
    }

    /** §6 steps 1, 2 and — through [applyChanges] — 3 to 7, without sync-collection support. */
    private suspend fun poll(
        account: Account,
        collection: DavCollection,
        session: CollectionSession,
        state: CollectionState,
        supportsSyncCollection: Boolean?,
        capabilityCheckedAt: Long,
        result: SyncResult,
    ): CollectionOutcome {
        val ctag = session.ctag()
        if (ctag != null && ctag == state.ctag)
            return CollectionOutcome(collection, unchanged = true)

        return applyChanges(
            account, collection, session, session.members(),
            fullListing = true,
            newCtag = ctag,
            // A full listing invalidates any sync token: the next REPORT must start from scratch.
            newToken = null,
            supportsSyncCollection = supportsSyncCollection,
            capabilityCheckedAt = capabilityCheckedAt,
            result = result,
        )
    }

    /** §6 steps 3 to 7, plus §7's read-only backstop, for a Collection whose members are known. */
    private suspend fun applyChanges(
        account: Account,
        collection: DavCollection,
        session: CollectionSession,
        members: Members,
        fullListing: Boolean,
        newCtag: String?,
        newToken: String?,
        supportsSyncCollection: Boolean?,
        capabilityCheckedAt: Long,
        result: SyncResult,
    ): CollectionOutcome {
        val local = mapper.localItems(account, collection)

        // Step 3: fetch adds and updates only. An href whose ETag already matches the row needs no
        // body, and an href the server gives no ETag for can never be compared, so it is fetched.
        val wanted = mutableMapOf<String, RemoteItem>()
        for ((key, item) in members.byKey)
            if (item.etag == null || local[key] != item.etag)
                wanted[key] = item

        // Steps 3 and 4, in batches of 50: each batch is committed, and its ETags are persisted with
        // the bodies they belong to, so a later diff cannot see a body without its ETag.
        var written = 0
        for (batch in wanted.entries.chunked(MULTIGET_BATCH_SIZE)) {
            ensureAccountRegistered(account)

            val bodies = session.multiget(batch.map { Url(it.value.href) })
            if (bodies.isEmpty()) continue

            val etags = batch.associate { it.key to it.value.etag }.filterKeys { it in bodies }
            written += mapper.upsert(account, collection, bodies, etags)
        }

        // Step 6. With a sync token the server reports removals explicitly, so only those are
        // deleted; a full listing instead justifies deleting everything it did not mention.
        val keep = if (fullListing) members.byKey.keys else local.keys - members.removed
        val deleted = deleteMissing(account, collection, members, keep)

        // §7: re-apply server state and drop DIRTY, so an edit made in an editor that ignored
        // supportsUploading="false" reverts visibly within an interval.
        mapper.clearDirty(account, collection)

        // Step 7, and not a line earlier: only now is the Collection complete.
        mapper.writeState(
            account,
            collection,
            CollectionState(
                ctag = newCtag,
                syncToken = newToken,
                supportsSyncCollection = supportsSyncCollection,
                capabilityCheckedAt = capabilityCheckedAt,
                lastSuccessAt = clock(),
            ),
        )

        return CollectionOutcome(collection, written = written, deleted = deleted)
    }

    /**
     * §6 invariant 2, asserted rather than commented.
     *
     * A listing that did not complete says nothing about the members it never reached, and deleting
     * on that evidence removes rows the server still has. [Members] is completed only by a collect
     * that ran to its end; the flag is checked again here because this is the one call whose mistake
     * loses user data rather than traffic.
     */
    private fun deleteMissing(
        account: Account,
        collection: DavCollection,
        members: Members,
        keep: Set<String>,
    ): Int {
        check(members.completed) {
            "refusing to delete rows of ${collection.id}: the member listing did not complete"
        }
        return mapper.deleteMissing(account, collection, keep)
    }

    /**
     * Records the answer the sync-collection probe produced.
     *
     * The state is written with the CTag it already had: the Collection is not complete, so nothing
     * new may be claimed about it — only the capability cache moves.
     */
    private fun cacheCapability(
        account: Account,
        collection: DavCollection,
        state: CollectionState,
        supported: Boolean,
        at: Long,
    ) {
        mapper.writeState(
            account,
            collection,
            state.copy(supportsSyncCollection = supported, capabilityCheckedAt = at),
        )
    }

    /**
     * §6: a trusted "unsupported" answer is re-probed weekly, or as soon as it fails. A "supported"
     * answer needs no separate probe — asking again would be the request we were about to make.
     */
    private fun mayAttemptSyncCollection(state: CollectionState, now: Long): Boolean =
        when (state.supportsSyncCollection) {
            null, true -> true
            false -> now - state.capabilityCheckedAt >= SYNC_COLLECTION_REPROBE_MS
        }

    /**
     * §7: the provider reaps the rows of an ACCOUNT_TYPE/ACCOUNT_NAME missing from AccountManager,
     * and a user can remove the Account mid-run — most likely during a long first sync. So this is
     * asked at the start and again before every batch commit.
     */
    private fun ensureAccountRegistered(account: Account) {
        try {
            mapper.assertAccountRegistered(account)
        } catch (e: Exception) {
            throw AccountVanishedException(e)
        }
    }

    /** The Collections this authority syncs, in the Account's own order. */
    private fun collectionsOf(davAccount: DavAccount): List<DavCollection> =
        davAccount.collections.filter { it.type == collectionType && it.selected && it.available }

    /** §5's mapping onto the framework's counters. Nothing here sets up a retry of its own. */
    private fun record(result: SyncResult, error: SyncError) {
        when (error.errorClass) {
            ErrorClass.PROXY_REJECTED_CREDENTIALS,
            ErrorClass.ORIGIN_WANTS_CREDENTIALS,
            ErrorClass.CERTIFICATE_UNAVAILABLE,
            ErrorClass.CREDENTIALS_UNREADABLE,
            ErrorClass.NO_CERTIFICATE_SENT,
            ErrorClass.PROXY_INTERFERENCE,
            -> result.stats.numAuthExceptions++

            ErrorClass.TRANSPORT_FAILURE,
            ErrorClass.SERVER_ERROR,
            -> result.stats.numIoExceptions++

            ErrorClass.NOT_FOUND,
            ErrorClass.METHOD_REFUSED,
            ErrorClass.MALFORMED_RESPONSE,
            -> result.stats.numParseExceptions++

            // Class 3 is informational: it neither fails a Collection nor contributes to "Partial".
            ErrorClass.ORIGIN_REFUSED_INFO -> Unit
        }
    }

    private fun report(
        account: Account,
        outcomes: List<CollectionOutcome>,
        aborted: Boolean,
        error: SyncError?,
    ) {
        reporter.onSyncFinished(
            AccountSyncReport(
                account = account,
                authority = mapper.authority,
                status = statusOf(outcomes, aborted, error),
                collections = outcomes,
                aborted = aborted,
                error = error,
                finishedAt = clock(),
            ),
        )
    }

    /** §5's OK / Partial / Failed rollup, where one broken Collection never masks the others. */
    private fun statusOf(
        outcomes: List<CollectionOutcome>,
        aborted: Boolean,
        error: SyncError?,
    ): AccountSyncStatus = when {
        aborted || error != null -> AccountSyncStatus.FAILED
        outcomes.any { it.error?.errorClass?.terminal == true } -> AccountSyncStatus.FAILED
        outcomes.any { it.failed } -> AccountSyncStatus.PARTIAL
        else -> AccountSyncStatus.OK
    }

    private fun errorMapping(davAccount: DavAccount, http: DavHttpSession) = ErrorMapping(
        classifier = classifier,
        certificateOffered = http.certificateOffered,
        certificateConfigured = davAccount.certAlias != null,
        lastExchange = { http.lastExchange },
    )

    /** §6: only classes 1, 2, 4 and 5 stop the Account's remaining Collections. */
    private val CollectionOutcome.abortsAccount: Boolean
        get() = error?.errorClass?.let { it in ACCOUNT_ABORTING_CLASSES } ?: false
}

/**
 * The Account is no longer registered.
 *
 * Continuing would write rows the provider reaps as soon as the Account is gone, and could record a
 * completed Collection for data that is about to disappear.
 */
private class AccountVanishedException(cause: Throwable) : Exception(cause)
