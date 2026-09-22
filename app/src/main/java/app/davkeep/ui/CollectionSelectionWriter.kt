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
