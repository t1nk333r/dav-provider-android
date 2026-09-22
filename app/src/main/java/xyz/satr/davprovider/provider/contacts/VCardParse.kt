package xyz.satr.davprovider.provider.contacts

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.util.Log
import ezvcard.Ezvcard
import ezvcard.VCard
import ezvcard.parameter.AddressType
import ezvcard.parameter.EmailType
import ezvcard.parameter.TelephoneType
import ezvcard.property.Address
import ezvcard.property.Birthday
import ezvcard.property.Categories
import ezvcard.property.FormattedName
import ezvcard.property.Kind
import ezvcard.property.ProductId
import ezvcard.property.RawProperty
import ezvcard.property.Revision
import ezvcard.property.Source
import ezvcard.property.Telephone
import ezvcard.property.Uid
import ezvcard.util.PartialDate
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.Locale
import ezvcard.property.Email as EzEmail
import ezvcard.property.Nickname as EzNickname
import ezvcard.property.Organization as EzOrganization
import ezvcard.property.Photo as EzPhoto
import ezvcard.property.StructuredName as EzStructuredName
import ezvcard.property.Title as EzTitle
import ezvcard.property.Url as EzUrl

/**
 * One `Data` row: the data kind's MIME type, the columns of that kind, and the handle of the source
 * property it was derived from.
 *
 * Column values are text because both sides of the write path can produce them as text — a cursor
 * returns `getString` for a column of any type, and the mapping writes the same spelling — which is
 * what lets a row the phone holds be compared with one derived from a vCard (`VCardPatch.kt`). No
 * `ContentValues`: the unit tests are plain JVM and an `android.jar` stub throws.
 */
internal class DataRow(
    val mimeType: String,
    val values: Map<String, String?>,
    /**
     * `<PROPERTY>:<ordinal>` of the source property this row came from — `TEL:1`, `NICKNAME:0/2` — or
     * null for a class a vCard holds once (the name, the organisation). The write path stores it in
     * `Data.SYNC1`, and a property is dropped only when the source derived a row from it and no
     * current row carries that row's handle.
     */
    val handle: String? = null,
)


/** Where a vCard's `PHOTO` is: bytes it carries, or a reference that would need the network. */
internal sealed interface PhotoSource {
    class Inline(val bytes: ByteArray) : PhotoSource
    class Link(val reference: String) : PhotoSource
}

/**
 * One fetched resource, decoded into the rows a write needs.
 *
 * Parsing is kept apart from writing: this decides what the vCard says, and the mapper decides
 * identity, batching and which rows may be written at all.
 */
internal class ParsedVCard(
    /** The vCard UID, which lands in `RawContacts.SYNC1`. */
    val uid: String?,
    /** A group vCard: RFC 6352's `KIND:group`, or the Apple spelling a vCard 3 server sends. */
    val isGroup: Boolean,
    /** The vCard's `FN`: a contact's display name, or a group's title. */
    val displayName: String?,
    /** Every `Data` row except the photo, the memberships and the verbatim copy. */
    val rows: List<DataRow>,
    val photo: PhotoSource?,
    /** `CATEGORIES` values: each names a group the contact belongs to. */
    val categories: List<String>,
    /** `X-ADDRESSBOOKSERVER-MEMBER` UID references, as a group vCard writes them. */
    val members: List<String>,
    /** Properties with no `ContactsContract` column of their own. */
    val unmappedProperties: Int,
)

/**
 * Decodes [text], or returns null when it is not a vCard this app can write.
 *
 * A resource that fails to parse is left unwritten and its ETag is therefore never persisted, so the
 * next run is offered it again: an item that arrives damaged is retried rather than accepted as
 * empty, which is what would destroy it on the server's side of any future upload.
 */
internal fun parseVCard(text: String): ParsedVCard? = readVCard(text)?.let { mapVCard(it) }

/**
 * The parsed object, or null when [text] is not a vCard at all.
 *
 * Separate from [parseVCard] because the write path needs the object itself: an upload is the stored
 * text patched, not a new vCard built from its mapping.
 */
internal fun readVCard(text: String): VCard? = try {
    Ezvcard.parse(text).first()
} catch (e: RuntimeException) {
    // ez-vcard reports malformed input as unchecked exceptions of several unrelated types.
    Log.w(LOG_TAG, "Unparseable vCard", e)
    null
}

internal fun mapVCard(vcard: VCard): ParsedVCard {
    var uid: String? = null
    var kind: String? = null
    var formattedName: String? = null
    var structuredName: EzStructuredName? = null
    var organization: EzOrganization? = null
    var photo: PhotoSource? = null
    val titles = ArrayList<EzTitle>()
    val categories = ArrayList<String>()
    val members = ArrayList<String>()
    val phonetics = HashMap<String, String>()
    var unmapped = 0

    for (property in vcard.properties) {
        when (property) {
            is EzStructuredName -> if (structuredName == null) structuredName = property
            is FormattedName -> if (formattedName == null) formattedName = property.value
            is Kind -> kind = property.value
            is Uid -> uid = property.value
            is EzOrganization -> if (organization == null) organization = property
            is EzTitle -> titles += property
            is Categories -> categories += property.values.filter { it.isNotBlank() }
            is EzPhoto -> if (photo == null) photo = photoSource(property)
            is RawProperty -> when (property.propertyName.uppercase(Locale.ROOT)) {
                // Apple's group convention, used because vCard 3 has no KIND.
                "X-ADDRESSBOOKSERVER-KIND" ->
                    if (property.value.equals(Kind.GROUP, ignoreCase = true)) kind = Kind.GROUP
                // Members are UID references; CATEGORIES on each member is the other half of it.
                "X-ADDRESSBOOKSERVER-MEMBER" -> property.value?.let { members += memberUid(it) }
                // Android's phonetic-name fields, which DAVx5 and the platform's own vCard writer
                // both spell this way. Without a column of their own they would be written, then
                // dropped by the next sync, then deleted from the vCard by the next edit.
                "X-PHONETIC-FIRST-NAME" -> property.value?.let { phonetics[StructuredName.PHONETIC_GIVEN_NAME] = it }
                "X-PHONETIC-MIDDLE-NAME" -> property.value?.let { phonetics[StructuredName.PHONETIC_MIDDLE_NAME] = it }
                "X-PHONETIC-LAST-NAME" -> property.value?.let { phonetics[StructuredName.PHONETIC_FAMILY_NAME] = it }
                else -> unmapped++
            }
            // Bookkeeping, carried either by the verbatim copy or by an identity column: no loss.
            is Revision, is ProductId, is Source -> Unit
            else -> unmapped++
        }
    }

    val rows = ArrayList<DataRow>()

    // N fills the name components, and FN fills the display name only when Android cannot derive it
    // from those components — deriving is what gives the contact its sort key and name style. The
    // phonetic columns are this row's too, so a card that carries only phonetics still gets one.
    val name = structuredName
    if (name != null || phonetics.isNotEmpty()) {
        rows += structuredNameRow(name, formattedName, phonetics)
    } else if (!formattedName.isNullOrBlank()) {
        // No N at all: without a display name the contact has no name any editor can show.
        rows += structuredNameRow(null, formattedName, phonetics)
    }

    // A handle is the position of the property among the properties of its own name, which is what
    // makes it stable: the same text always yields the same handles, so a row carrying one is a row
    // the current source still spells the same way.
    vcard.getProperties(EzNickname::class.java).forEachIndexed { index, nickname ->
        nickname.values.forEachIndexed { valueIndex, value ->
            if (value.isNullOrBlank()) return@forEachIndexed
            rows += DataRow(
                Nickname.CONTENT_ITEM_TYPE,
                mapOf(Nickname.NAME to value),
                "NICKNAME:$index/$valueIndex",
            )
        }
    }
    vcard.telephoneNumbers.forEachIndexed { index, telephone ->
        phoneRow(telephone, "TEL:$index")?.let { rows += it }
    }
    vcard.emails.forEachIndexed { index, email ->
        emailRow(email, "EMAIL:$index")?.let { rows += it }
    }
    vcard.addresses.forEachIndexed { index, address ->
        addressRow(address, "ADR:$index")?.let { rows += it }
    }
    organizationRow(organization, titles)?.let { rows += it }
    vcard.notes.forEachIndexed { index, note ->
        note.value?.takeIf { it.isNotBlank() }?.let {
            rows += DataRow(Note.CONTENT_ITEM_TYPE, mapOf(Note.NOTE to it), "NOTE:$index")
        }
    }
    vcard.urls.forEachIndexed { index, website ->
        websiteRow(website, "URL:$index")?.let { rows += it }
    }
    vcard.birthdays.forEachIndexed { index, birthday ->
        birthdayRow(birthday, "BDAY:$index")?.let { rows += it }
    }

    return ParsedVCard(
        uid = uid,
        isGroup = kind.equals(Kind.GROUP, ignoreCase = true),
        displayName = formattedName?.takeIf { it.isNotBlank() } ?: joinedName(structuredName),
        rows = rows,
        photo = photo,
        categories = categories.distinct(),
        members = members.distinct(),
        unmappedProperties = unmapped,
    )
}

/**
 * The row for `N`, with `FN` as its display name unless the two say the same thing.
 *
 * Leaving the display name out is not a loss: the provider joins the components itself and records
 * the name style it guessed from them.
 */
private fun structuredNameRow(
    name: EzStructuredName?,
    formattedName: String?,
    phonetics: Map<String, String>,
): DataRow {
    val values = LinkedHashMap<String, String?>()
    if (name != null) {
        name.given?.takeIf { it.isNotBlank() }?.let { values[StructuredName.GIVEN_NAME] = it }
        name.family?.takeIf { it.isNotBlank() }?.let { values[StructuredName.FAMILY_NAME] = it }
        joined(name.prefixes)?.let { values[StructuredName.PREFIX] = it }
        joined(name.additionalNames)?.let { values[StructuredName.MIDDLE_NAME] = it }
        joined(name.suffixes)?.let { values[StructuredName.SUFFIX] = it }
    }
    if (!formattedName.isNullOrBlank() && !derivesDisplayName(name, formattedName)) {
        values[StructuredName.DISPLAY_NAME] = formattedName
    }
    values.putAll(phonetics)
    return DataRow(StructuredName.CONTENT_ITEM_TYPE, values)
}

/** Whether Android's own join of [name]'s components would produce [formattedName] as written. */
private fun derivesDisplayName(name: EzStructuredName?, formattedName: String): Boolean {
    val joined = joinedName(name) ?: return false
    return nameKey(joined) == nameKey(formattedName)
}

/** The components of [name] as Android would join them, or null when there are none. */
private fun joinedName(name: EzStructuredName?): String? {
    if (name == null) return null
    val parts = name.prefixes +
        listOfNotNull(name.given) +
        name.additionalNames +
        listOfNotNull(name.family) +
        name.suffixes
    return joined(parts)
}

/**
 * Compares names the way a reader would: punctuation and capitalisation a server added to `FN` are
 * not a reason to override the components it also sent.
 */
internal fun nameKey(value: String): String =
    value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

private fun joined(parts: List<String?>): String? =
    parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotEmpty() }

private fun phoneRow(telephone: Telephone, handle: String): DataRow? {
    val number = telephone.text?.takeIf { it.isNotBlank() }
        ?: telephone.uri?.number?.takeIf { it.isNotBlank() }
        ?: return null
    val spec = phoneType(telephone.types)
    return DataRow(
        Phone.CONTENT_ITEM_TYPE,
        buildMap {
            put(Phone.NUMBER, number)
            put(Phone.TYPE, spec.type.toString())
            spec.label?.let { put(Phone.LABEL, it) }
        },
        handle,
    )
}

private fun emailRow(email: EzEmail, handle: String): DataRow? {
    val address = email.value?.takeIf { it.isNotBlank() } ?: return null
    val spec = emailType(email.types)
    return DataRow(
        Email.CONTENT_ITEM_TYPE,
        buildMap {
            put(Email.ADDRESS, address)
            put(Email.TYPE, spec.type.toString())
            spec.label?.let { put(Email.LABEL, it) }
        },
        handle,
    )
}

private fun addressRow(address: Address, handle: String): DataRow? {
    val values = LinkedHashMap<String, String?>()
    address.poBox?.takeIf { it.isNotBlank() }?.let { values[StructuredPostal.POBOX] = it }
    joined(listOf(address.extendedAddressFull, address.streetAddressFull))
        ?.let { values[StructuredPostal.STREET] = it }
    address.locality?.takeIf { it.isNotBlank() }?.let { values[StructuredPostal.CITY] = it }
    address.region?.takeIf { it.isNotBlank() }?.let { values[StructuredPostal.REGION] = it }
    address.postalCode?.takeIf { it.isNotBlank() }?.let { values[StructuredPostal.POSTCODE] = it }
    address.country?.takeIf { it.isNotBlank() }?.let { values[StructuredPostal.COUNTRY] = it }
    // The server's own `LABEL`, when it sent one: it knows how the address is written locally.
    address.label?.takeIf { it.isNotBlank() }?.let { values[StructuredPostal.FORMATTED_ADDRESS] = it }
    if (values.isEmpty()) return null
    val spec = addressType(address.types)
    values[StructuredPostal.TYPE] = spec.type.toString()
    spec.label?.let { values[StructuredPostal.LABEL] = it }
    return DataRow(StructuredPostal.CONTENT_ITEM_TYPE, values, handle)
}

/**
 * `ORG` and `TITLE` share one row because they are one thing to a contact: an organisation with a
 * job title. `ORG` is a list — the first level is the company, the rest is where in it the person
 * sits — and a `TITLE` with no `ORG` at all still deserves a row of its own.
 */
private fun organizationRow(organization: EzOrganization?, titles: List<EzTitle>): DataRow? {
    val values = LinkedHashMap<String, String?>()
    organization?.values?.let { levels ->
        levels.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { values[Organization.COMPANY] = it }
        joined(levels.drop(1))?.let { values[Organization.DEPARTMENT] = it }
    }
    joined(titles.map { it.value })?.let { values[Organization.TITLE] = it }
    if (values.isEmpty()) return null
    return DataRow(Organization.CONTENT_ITEM_TYPE, values)
}

private fun websiteRow(url: EzUrl, handle: String): DataRow? {
    val value = url.value?.takeIf { it.isNotBlank() } ?: return null
    val spec = websiteType(url)
    return DataRow(
        Website.CONTENT_ITEM_TYPE,
        buildMap {
            put(Website.URL, value)
            put(Website.TYPE, spec.type.toString())
            spec.label?.let { put(Website.LABEL, it) }
        },
        handle,
    )
}

private fun birthdayRow(birthday: Birthday, handle: String): DataRow? {
    val startDate = startDate(birthday) ?: return null
    return DataRow(
        Event.CONTENT_ITEM_TYPE,
        mapOf(Event.START_DATE to startDate, Event.TYPE to Event.TYPE_BIRTHDAY.toString()),
        handle,
    )
}

private fun photoSource(photo: EzPhoto): PhotoSource? = when {
    photo.data != null -> PhotoSource.Inline(photo.data)
    photo.url != null -> PhotoSource.Link(photo.url)
    else -> null
}

/** Apple's parameter for a birthday with no year: the placeholder year is not the year. */
private const val OMIT_YEAR = "X-APPLE-OMIT-YEAR"

/**
 * The birth date in the form the provider stores dates in: `yyyy-MM-dd`, or `--MM-dd` when the vCard
 * has no year. Anything else about the value is not representable, and is counted instead of
 * guessed at.
 */
private fun startDate(birthday: Birthday): String? {
    birthday.partialDate?.let { return partialDateValue(it) }
    val date = birthday.date
    // Apple's spelling of a year-less birthday, which the platform's own vCard writer produces too: a
    // placeholder year that the parameter says means nothing. Read as a real date it would put the
    // contact's birthday in the year 1604.
    if (date is LocalDate && birthday.getParameter(OMIT_YEAR) == date.year.toString()) {
        return String.format(Locale.ROOT, "--%02d-%02d", date.monthValue, date.dayOfMonth)
    }
    when (date) {
        is LocalDate -> return date.toString()
        is LocalDateTime -> return date.toLocalDate().toString()
        is OffsetDateTime -> return date.toLocalDate().toString()
        is ZonedDateTime -> return date.toLocalDate().toString()
    }
    // A date the parser could not place in a calendar type: one more look at the value as written.
    val text = birthday.text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { PartialDate.parse(text) }.getOrNull()?.let { partialDateValue(it) }
}

private fun partialDateValue(partial: PartialDate): String? {
    val year = partial.year
    val month = partial.month
    val day = partial.date
    return when {
        year != null && month != null && day != null ->
            String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day)
        month != null && day != null -> String.format(Locale.ROOT, "--%02d-%02d", month, day)
        else -> null
    }
}

/** A platform type constant, and the vCard's own word for the type when there is no constant. */
private class TypeSpec(val type: Int, val label: String? = null)

private fun phoneType(types: List<TelephoneType>): TypeSpec {
    for (type in types) {
        when (type.value.uppercase(Locale.ROOT)) {
            "CELL", "MOBILE" -> return TypeSpec(Phone.TYPE_MOBILE)
            "WORK" -> return TypeSpec(Phone.TYPE_WORK)
            "HOME" -> return TypeSpec(Phone.TYPE_HOME)
            "FAX" -> return TypeSpec(Phone.TYPE_FAX_HOME)
            "PAGER" -> return TypeSpec(Phone.TYPE_PAGER)
            "CAR" -> return TypeSpec(Phone.TYPE_CAR)
            "ISDN" -> return TypeSpec(Phone.TYPE_ISDN)
            "MAIN" -> return TypeSpec(Phone.TYPE_MAIN)
            "TEXT", "TEXTPHONE" -> return TypeSpec(Phone.TYPE_TTY_TDD)
            // Modifiers rather than kinds of number: they cannot decide the type on their own.
            "BBS", "MODEM", "MSG", "PCS", "PREF", "VIDEO", "VOICE" -> Unit
            else -> return TypeSpec(Phone.TYPE_OTHER, type.value.uppercase(Locale.ROOT))
        }
    }
    return TypeSpec(Phone.TYPE_OTHER)
}

private fun emailType(types: List<EmailType>): TypeSpec {
    for (type in types) {
        when (type.value.uppercase(Locale.ROOT)) {
            "HOME" -> return TypeSpec(Email.TYPE_HOME)
            "WORK" -> return TypeSpec(Email.TYPE_WORK)
            "CELL" -> return TypeSpec(Email.TYPE_MOBILE)
            "PREF", "INTERNET" -> Unit
            else -> return TypeSpec(Email.TYPE_OTHER, type.value.uppercase(Locale.ROOT))
        }
    }
    return TypeSpec(Email.TYPE_OTHER)
}

private fun addressType(types: List<AddressType>): TypeSpec {
    for (type in types) {
        when (type.value.uppercase(Locale.ROOT)) {
            "HOME" -> return TypeSpec(StructuredPostal.TYPE_HOME)
            "WORK" -> return TypeSpec(StructuredPostal.TYPE_WORK)
            "PREF", "DOM", "INTL", "POSTAL", "PARCEL" -> Unit
            else -> return TypeSpec(StructuredPostal.TYPE_OTHER, type.value.uppercase(Locale.ROOT))
        }
    }
    return TypeSpec(StructuredPostal.TYPE_OTHER)
}

private fun websiteType(url: EzUrl): TypeSpec =
    when (url.type?.uppercase(Locale.ROOT)) {
        "HOME", "HOMEPAGE" -> TypeSpec(Website.TYPE_HOMEPAGE)
        "WORK" -> TypeSpec(Website.TYPE_WORK)
        "BLOG" -> TypeSpec(Website.TYPE_BLOG)
        "FTP" -> TypeSpec(Website.TYPE_FTP)
        "PROFILE" -> TypeSpec(Website.TYPE_PROFILE)
        null -> TypeSpec(Website.TYPE_OTHER)
        else -> TypeSpec(Website.TYPE_OTHER, url.type.uppercase(Locale.ROOT))
    }

/** A `MEMBER` value is usually `urn:uuid:<uid>`; the reference itself is what identifies a member. */
private fun memberUid(value: String): String {
    val trimmed = value.trim()
    val prefix = "urn:uuid:"
    return if (trimmed.startsWith(prefix, ignoreCase = true)) trimmed.substring(prefix.length) else trimmed
}
