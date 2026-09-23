package app.davkeep.ui

import android.accounts.Account
import app.davkeep.core.AccountStore
import app.davkeep.core.DavCollection

/**
 * The part of an [AccountStore] that rewrites only an account's Collection selection.
 *
 * [AccountStore.save] is total: an object that omits a header name, a header value or a password
 * removes the stored secret. Rewriting a selection through it would therefore require a
 * load/modify/save round trip through a screen that ADR-0001 does not let display secrets at all.
 * This entry point carries no credential material, so it cannot lose any.
 */
interface CollectionSelectionWriter {

    /** @throws IllegalStateException when the account has no stored record to update. */
    fun saveCollections(account: Account, collections: List<DavCollection>)
}

/**
 * Rewrites an Account's Collections, starting from the record as it is now rather than as a screen
 * last drew it.
 *
 * Every UI write of a Collection used to rebuild the whole list from the copy its screen rendered, so
 * whichever write landed last carried a stale value for every Collection it was not changing. Two
 * quick toggles had the second undo the first; a checkbox tapped while "Check collections" was out on
 * the network was undone seconds later when the walk's merge landed; a Collection a walk had retired
 * came back. Reading the record here, on the thread that writes it and after any network work, means
 * [transform] sees what is actually stored and each write changes only what it was asked to.
 *
 * The record comes from [AccountStore.list], which reads no Credentials: a selection change must not
 * fail because the Keystore key was lost in a restore, when nothing about it needs a secret.
 *
 * @return the list that was written, for the caller that reschedules from it.
 * @throws IllegalStateException when the Account has no stored record.
 */
internal fun AccountStore.updateCollections(
    account: Account,
    transform: (List<DavCollection>) -> List<DavCollection>,
): List<DavCollection> {
    val current = list().firstOrNull { it.label == account.name }?.collections
        ?: throw IllegalStateException("no stored record for this Account")
    val updated = transform(current)
    (this as CollectionSelectionWriter).saveCollections(account, updated)
    return updated
}

/**
 * [updateCollections] for the common case of one field on one Collection.
 *
 * @throws IllegalStateException when the Account has no stored record, or no such Collection — a
 * Collection removed while its screen was open is not silently recreated by the write.
 */
internal fun AccountStore.updateCollection(
    account: Account,
    collectionId: String,
    change: (DavCollection) -> DavCollection,
): List<DavCollection> = updateCollections(account) { current ->
    check(current.any { it.id == collectionId }) { "no such Collection on this Account" }
    current.map { if (it.id == collectionId) change(it) else it }
}
