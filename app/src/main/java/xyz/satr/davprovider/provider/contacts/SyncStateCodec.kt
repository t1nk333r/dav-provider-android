package xyz.satr.davprovider.provider.contacts

import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import xyz.satr.davprovider.core.CollectionState

/**
 * Reads and writes the sync state of every Collection of one Account.
 *
 * `ContactsContract.SyncState` keeps one row per Account, so the Collection's state is an entry in
 * that row's blob rather than a row of its own: writing one Collection must not drop the others.
 * State therefore lives with the data it describes, which is what stops the provider's account
 * cleanup from leaving rows behind that outlive the contacts they belong to.
 *
 * Only fields that differ from [CollectionState]'s defaults are written; a Collection that has never
 * been synced costs nothing to remember.
 */
internal object SyncStateCodec {

    private const val CTAG = "ctag"
    private const val SYNC_TOKEN = "syncToken"
    private const val SUPPORTS_SYNC_COLLECTION = "supportsSyncCollection"
    private const val CAPABILITY_CHECKED_AT = "capabilityCheckedAt"
    private const val LAST_SUCCESS_AT = "lastSuccessAt"

    fun decode(data: ByteArray?): Map<String, CollectionState> {
        if (data == null || data.isEmpty()) return emptyMap()
        return try {
            val json = JSONObject(String(data, Charsets.UTF_8))
            buildMap {
                for (id in json.keys()) {
                    val entry = json.optJSONObject(id) ?: continue
                    put(
                        id,
                        CollectionState(
                            ctag = entry.stringOrNull(CTAG),
                            syncToken = entry.stringOrNull(SYNC_TOKEN),
                            supportsSyncCollection = entry.booleanOrNull(SUPPORTS_SYNC_COLLECTION),
                            capabilityCheckedAt = entry.optLong(CAPABILITY_CHECKED_AT, 0L),
                            lastSuccessAt = entry.optLong(LAST_SUCCESS_AT, 0L),
                        ),
                    )
                }
            }
        } catch (e: JSONException) {
            // Losing the state costs one full listing of the Collection: item ETags live on the
            // rows, and the CTag is only the shortcut that skips the listing. Starting over is
            // therefore safe, and it is the only way out of a blob that can no longer be read.
            Log.w(LOG_TAG, "Discarding unreadable sync state", e)
            emptyMap()
        }
    }

    fun encode(states: Map<String, CollectionState>): ByteArray {
        val json = JSONObject()
        for ((id, state) in states) {
            val entry = JSONObject()
            state.ctag?.let { entry.put(CTAG, it) }
            state.syncToken?.let { entry.put(SYNC_TOKEN, it) }
            state.supportsSyncCollection?.let { entry.put(SUPPORTS_SYNC_COLLECTION, it) }
            if (state.capabilityCheckedAt != 0L) entry.put(CAPABILITY_CHECKED_AT, state.capabilityCheckedAt)
            if (state.lastSuccessAt != 0L) entry.put(LAST_SUCCESS_AT, state.lastSuccessAt)
            json.put(id, entry)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    private fun JSONObject.booleanOrNull(key: String): Boolean? =
        if (has(key) && !isNull(key)) getBoolean(key) else null
}
