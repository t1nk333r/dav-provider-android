package xyz.satr.davprovider.store

import org.json.JSONArray
import org.json.JSONObject
import xyz.satr.davprovider.core.ACCOUNT_TYPE
import xyz.satr.davprovider.core.CollectionType
import xyz.satr.davprovider.core.DavAccount
import xyz.satr.davprovider.core.DavCollection
import xyz.satr.davprovider.core.DavHeader

/**
 * The non-secret half of an Account, as one JSON object in the account's userdata.
 *
 * Header **values** and the password are absent by design: [CredentialStore] holds those as
 * Keystore-wrapped ciphertext (ADR-0001), so no plaintext Credential is ever written to userdata.
 *
 * [encode] and [decode] are the record's shape and name no Android type; org.json only turns that
 * shape into text. Keeping the two apart is what makes the interesting half testable off-device.
 */
internal object AccountRecordJson {

    /** The userdata key this object is stored under, in the account it describes. */
    const val USER_DATA_KEY: String = "$ACCOUNT_TYPE.account"

    private const val BASE_URL = "baseUrl"
    private const val HEADER_NAMES = "headerNames"
    private const val CERT_ALIAS = "certAlias"
    private const val USERNAME = "username"
    private const val COLLECTIONS = "collections"

    private const val ID = "id"
    private const val URL = "url"
    private const val TYPE = "type"
    private const val DISPLAY_NAME = "displayName"
    private const val COLOR = "color"
    private const val SELECTED = "selected"
    private const val AVAILABLE = "available"

    /**
     * The record for [account]: header **names** only, never their values. Unset fields are left
     * out of the object rather than written as null; [decode] reads both back as null.
     */
    fun encode(account: DavAccount): Map<String, Any?> = record(
        BASE_URL to account.baseUrl,
        HEADER_NAMES to account.headers.map { it.name },
        CERT_ALIAS to account.certAlias,
        USERNAME to account.username,
        COLLECTIONS to account.collections.map { collection(it) },
    )

    /**
     * The Account [body] describes, stored under [label] — the AccountManager account name, which
     * *is* the identity key and so is not duplicated into the record where it could drift.
     *
     * Header values come back empty and the password null: neither is in the record. Fields the
     * record cannot be understood without are refused, never guessed.
     */
    fun decode(body: Map<String, Any?>, label: String): DavAccount = DavAccount(
        label = label,
        baseUrl = body.string(BASE_URL)
            ?: throw IllegalStateException("Account record has no $BASE_URL"),
        headers = body.strings(HEADER_NAMES).map { DavHeader(it, "") },
        certAlias = body.string(CERT_ALIAS),
        username = body.string(USERNAME),
        collections = body.objects(COLLECTIONS).map { decodeCollection(it) },
    )

    /** The record as it is stored. */
    fun toJson(account: DavAccount): String = jsonObject(encode(account)).toString()

    fun fromJson(encoded: String, label: String): DavAccount =
        decode(fieldMap(JSONObject(encoded)), label)

    private fun collection(entry: DavCollection): Map<String, Any?> = record(
        ID to entry.id,
        URL to entry.url,
        TYPE to entry.type.name,
        DISPLAY_NAME to entry.displayName,
        COLOR to entry.color,
        SELECTED to entry.selected,
        AVAILABLE to entry.available,
    )

    private fun decodeCollection(fields: Map<*, *>): DavCollection = DavCollection(
        id = fields.string(ID) ?: throw IllegalStateException("Collection has no $ID"),
        url = fields.string(URL) ?: throw IllegalStateException("Collection has no $URL"),
        type = fields.string(TYPE)?.let { name ->
            CollectionType.entries.firstOrNull { it.name == name }
                ?: throw IllegalStateException("Unknown Collection type $name")
        } ?: throw IllegalStateException("Collection has no $TYPE"),
        displayName = fields.string(DISPLAY_NAME),
        color = (fields[COLOR] as? Number)?.toInt(),
        selected = fields[SELECTED] as? Boolean ?: false,
        available = fields[AVAILABLE] as? Boolean ?: true,
    )

    private fun record(vararg fields: Pair<String, Any?>): Map<String, Any?> {
        val record = LinkedHashMap<String, Any?>(fields.size)
        for ((key, value) in fields) {
            if (value != null) record[key] = value
        }
        return record
    }

    private fun Map<*, *>.string(key: String): String? = this[key] as? String

    private fun Map<*, *>.strings(key: String): List<String> =
        (this[key] as? List<*>)?.map {
            it as? String ?: throw IllegalStateException("$key holds something that is not a name")
        } ?: emptyList()

    private fun Map<*, *>.objects(key: String): List<Map<*, *>> =
        (this[key] as? List<*>)?.map {
            it as? Map<*, *>
                ?: throw IllegalStateException("$key holds something that is not an object")
        } ?: emptyList()

    private fun jsonObject(fields: Map<*, *>): JSONObject {
        val json = JSONObject()
        for ((key, value) in fields) json.put(key.toString(), jsonValue(value))
        return json
    }

    private fun jsonValue(value: Any?): Any = when (value) {
        // org.json drops a mapping whose value is null, so JSON null has to be spelled out.
        null -> JSONObject.NULL
        is Map<*, *> -> jsonObject(value)
        is List<*> -> JSONArray().apply { for (item in value) put(jsonValue(item)) }
        else -> value
    }

    private fun fieldMap(json: JSONObject): Map<String, Any?> {
        val fields = LinkedHashMap<String, Any?>(json.length())
        for (key in json.keys()) fields[key] = fieldValue(json.get(key))
        return fields
    }

    private fun fieldValue(encoded: Any?): Any? = when (encoded) {
        JSONObject.NULL -> null
        is JSONObject -> fieldMap(encoded)
        is JSONArray -> (0 until encoded.length()).map { fieldValue(encoded.opt(it)) }
        else -> encoded
    }
}
