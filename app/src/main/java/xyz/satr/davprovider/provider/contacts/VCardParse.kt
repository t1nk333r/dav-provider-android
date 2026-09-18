package xyz.satr.davprovider.provider.contacts

import android.content.ContentValues
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
import ezvcard.property.Note as EzNote
import ezvcard.property.Organization as EzOrganization
import ezvcard.property.Photo as EzPhoto
import ezvcard.property.StructuredName as EzStructuredName
import ezvcard.property.Title as EzTitle
import ezvcard.property.Url as EzUrl

/** One `Data` row: the MIME type of a data kind plus the columns of that kind. */
internal class DataRow(val mimeType: String, val values: ContentValues)

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
internal fun parseVCard(text: String): ParsedVCard? {
    val vcard: VCard = try {
        Ezvcard.parse(text).first() ?: return null
    } catch (e: RuntimeException) {
        // ez-vcard reports malformed input as unchecked exceptions of several unrelated types.
        Log.w(LOG_TAG, "Unparseable vCard", e)
        return null
    }
    return mapVCard(vcard)
}

private fun mapVCard(vcard: VCard): ParsedVCard {
    var uid: String? = null
    var kind: String? = null
    var formattedName: String? = null
    var structuredName: EzStructuredName? = null
    var organization: EzOrganization? = null
    var photo: PhotoSource? = null
    val telephones = ArrayList<Telephone>()
    val emails = ArrayList<EzEmail>()
    val addresses = ArrayList<Address>()
    val notes = ArrayList<EzNote>()
    val websites = ArrayList<EzUrl>()
    val nicknames = ArrayList<EzNickname>()
    val titles = ArrayList<EzTitle>()
    val birthdays = ArrayList<Birthday>()
    val categories = ArrayList<String>()
    val members = ArrayList<String>()
    var unmapped = 0

    for (property in vcard.properties) {
        when (property) {
            is EzStructuredName -> if (structuredName == null) structuredName = property
            is FormattedName -> if (formattedName == null) formattedName = property.value
            is Kind -> kind = property.value
            is Uid -> uid = property.value
            is Telephone -> telephones += property
            is EzEmail -> emails += property
            is Address -> addresses += property
            is EzOrganization -> if (organization == null) organization = property
            is EzTitle -> titles += property
            is EzNote -> notes += property
            is EzUrl -> websites += property
            is EzNickname -> nicknames += property
            is Birthday -> birthdays += property
            is Categories -> categories += property.values.filter { it.isNotBlank() }
            is EzPhoto -> if (photo == null) photo = photoSource(property)
            is RawProperty -> when (property.propertyName.uppercase(Locale.ROOT)) {
                // Apple's group convention, used because vCard 3 has no KIND.
                "X-ADDRESSBOOKSERVER-KIND" ->
                    if (property.value.equals(Kind.GROUP, ignoreCase = true)) kind = Kind.GROUP
                // Members are UID references; CATEGORIES on each member is the other half of it.
                "X-ADDRESSBOOKSERVER-MEMBER" -> property.value?.let { members += memberUid(it) }
                else -> unmapped++
            }
            // Bookkeeping, carried either by the verbatim copy or by an identity column: no loss.
            is Revision, is ProductId, is Source -> Unit
            else -> unmapped++
        }
    }

    val rows = ArrayList<DataRow>()

    // N fills the name components, and FN fills the display name only when Android cannot derive it
    // from those components — deriving is what gives the contact its sort key and name style.
    val name = structuredName
    if (name != null) {
        rows += structuredNameRow(name, formattedName)
    } else if (!formattedName.isNullOrBlank()) {
        // No N at all: without a display name the contact has no name any editor can show.
        rows += structuredNameRow(null, formattedName)
    }

    for (nickname in nicknames) {
        rows += textRows(Nickname.CONTENT_ITEM_TYPE, Nickname.NAME, nickname.values)
    }
    for (telephone in telephones) {
        phoneRow(telephone)?.let { rows += it }
    }
    for (email in emails) {
        emailRow(email)?.let { rows += it }
    }
    for (address in addresses) {
        addressRow(address)?.let { rows += it }
    }
    organizationRow(organization, titles)?.let { rows += it }
    rows += textRows(Note.CONTENT_ITEM_TYPE, Note.NOTE, notes.map { it.value })
    for (website in websites) {
        websiteRow(website)?.let { rows += it }
    }
    for (birthday in birthdays) {
        birthdayRow(birthday)?.let { rows += it }
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
private fun structuredNameRow(name: EzStructuredName?, formattedName: String?): DataRow {
    val values = ContentValues()
    if (name != null) {
        name.given?.takeIf { it.isNotBlank() }?.let { values.put(StructuredName.GIVEN_NAME, it) }
        name.family?.takeIf { it.isNotBlank() }?.let { values.put(StructuredName.FAMILY_NAME, it) }
        joined(name.prefixes)?.let { values.put(StructuredName.PREFIX, it) }
        joined(name.additionalNames)?.let { values.put(StructuredName.MIDDLE_NAME, it) }
        joined(name.suffixes)?.let { values.put(StructuredName.SUFFIX, it) }
    }
    if (!formattedName.isNullOrBlank() && !derivesDisplayName(name, formattedName)) {
        values.put(StructuredName.DISPLAY_NAME, formattedName)
    }
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
private fun nameKey(value: String): String =
    value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

private fun joined(parts: List<String?>): String? =
    parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" ").takeIf { it.isNotEmpty() }

private fun textRows(mimeType: String, column: String, values: List<String?>): List<DataRow> =
    values.filterNotNull().filter { it.isNotBlank() }.map { value ->
        DataRow(mimeType, ContentValues().apply { put(column, value) })
    }

private fun phoneRow(telephone: Telephone): DataRow? {
    val number = telephone.text?.takeIf { it.isNotBlank() }
        ?: telephone.uri?.number?.takeIf { it.isNotBlank() }
        ?: return null
    val spec = phoneType(telephone.types)
    val values = ContentValues().apply {
        put(Phone.NUMBER, number)
        put(Phone.TYPE, spec.type)
        spec.label?.let { put(Phone.LABEL, it) }
    }
    return DataRow(Phone.CONTENT_ITEM_TYPE, values)
}

private fun emailRow(email: EzEmail): DataRow? {
    val address = email.value?.takeIf { it.isNotBlank() } ?: return null
    val spec = emailType(email.types)
    val values = ContentValues().apply {
        put(Email.ADDRESS, address)
        put(Email.TYPE, spec.type)
        spec.label?.let { put(Email.LABEL, it) }
    }
    return DataRow(Email.CONTENT_ITEM_TYPE, values)
}

private fun addressRow(address: Address): DataRow? {
    val values = ContentValues()
    address.poBox?.takeIf { it.isNotBlank() }?.let { values.put(StructuredPostal.POBOX, it) }
    joined(listOf(address.extendedAddressFull, address.streetAddressFull))
        ?.let { values.put(StructuredPostal.STREET, it) }
    address.locality?.takeIf { it.isNotBlank() }?.let { values.put(StructuredPostal.CITY, it) }
    address.region?.takeIf { it.isNotBlank() }?.let { values.put(StructuredPostal.REGION, it) }
    address.postalCode?.takeIf { it.isNotBlank() }?.let { values.put(StructuredPostal.POSTCODE, it) }
    address.country?.takeIf { it.isNotBlank() }?.let { values.put(StructuredPostal.COUNTRY, it) }
    // The server's own `LABEL`, when it sent one: it knows how the address is written locally.
    address.label?.takeIf { it.isNotBlank() }?.let { values.put(StructuredPostal.FORMATTED_ADDRESS, it) }
    if (values.size() == 0) return null
    val spec = addressType(address.types)
    values.put(StructuredPostal.TYPE, spec.type)
    spec.label?.let { values.put(StructuredPostal.LABEL, it) }
    return DataRow(StructuredPostal.CONTENT_ITEM_TYPE, values)
}

/**
 * `ORG` and `TITLE` share one row because they are one thing to a contact: an organisation with a
 * job title. `ORG` is a list — the first level is the company, the rest is where in it the person
 * sits — and a `TITLE` with no `ORG` at all still deserves a row of its own.
 */
private fun organizationRow(organization: EzOrganization?, titles: List<EzTitle>): DataRow? {
    val values = ContentValues()
    organization?.values?.let { levels ->
        levels.getOrNull(0)?.takeIf { it.isNotBlank() }?.let { values.put(Organization.COMPANY, it) }
        joined(levels.drop(1))?.let { values.put(Organization.DEPARTMENT, it) }
    }
    joined(titles.map { it.value })?.let { values.put(Organization.TITLE, it) }
    if (values.size() == 0) return null
    return DataRow(Organization.CONTENT_ITEM_TYPE, values)
}

private fun websiteRow(url: EzUrl): DataRow? {
    val value = url.value?.takeIf { it.isNotBlank() } ?: return null
    val spec = websiteType(url)
    val values = ContentValues().apply {
        put(Website.URL, value)
        put(Website.TYPE, spec.type)
        spec.label?.let { put(Website.LABEL, it) }
    }
    return DataRow(Website.CONTENT_ITEM_TYPE, values)
}

private fun birthdayRow(birthday: Birthday): DataRow? {
    val startDate = startDate(birthday) ?: return null
    val values = ContentValues().apply {
        put(Event.START_DATE, startDate)
        put(Event.TYPE, Event.TYPE_BIRTHDAY)
    }
    return DataRow(Event.CONTENT_ITEM_TYPE, values)
}

private fun photoSource(photo: EzPhoto): PhotoSource? = when {
    photo.data != null -> PhotoSource.Inline(photo.data)
    photo.url != null -> PhotoSource.Link(photo.url)
    else -> null
}

/**
 * The birth date in the form the provider stores dates in: `yyyy-MM-dd`, or `--MM-dd` when the vCard
 * has no year. Anything else about the value is not representable, and is counted instead of
 * guessed at.
 */
private fun startDate(birthday: Birthday): String? {
    birthday.partialDate?.let { return partialDateValue(it) }
    when (val date = birthday.date) {
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
