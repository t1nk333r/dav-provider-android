# Research: does `ContactsContract` round-trip vCard 4.0 faithfully? (server → phone)

Ticket: #6 (wayfinder map #1). Scope: **v1 is read-only**, so this is server → phone fidelity only.
Method: Android platform reference documentation and AOSP source, plus RFC 6350. No secondary write-ups.
Nothing here was tested on a device; every claim is either quoted from source or explicitly marked as
inference.

## Verdict

The provider's *model* is wide enough for a typical address book — name, org, phones, emails,
addresses, URLs, notes, events, photos and group membership all have first-class columns, and the
`Data` table is documented as open-ended, so anything without a column can be kept in a custom MIME
row. What is **not** faithful is (a) every vCard 4.0 *metadata* construct (`KIND`, `MEMBER`, `ALTID`,
`PID`/`CLIENTPIDMAP`, `PREF` levels, `UID`, `GEO`/`TZ`/`LANG`, arbitrary `X-`) — Android's own
importer drops them, and its 4.0 parser is marked "DO NOT USE IN PRODUCTION" — and (b) binary
fidelity of `PHOTO` and precision of date/time values, both of which the provider re-encodes. Since
v1 is read-only and we write rows ourselves rather than driving `com.android.vcard`, "dropped" only
means "dropped *unless we store it ourselves*"; the decision-critical facts are that
`RawContacts.SOURCE_ID` + `SYNC1`/`SYNC2` is the place for UID/href/ETag, and that **any
`ACCOUNT_TYPE`/`ACCOUNT_NAME` pair not registered with `AccountManager` is deleted, together with its
groups and raw contacts, by a background task** — so an unregistered account must not be used as a
convenience scoping key.

## 1. The model we are mapping onto

Three tiers, quoted from the `ContactsContract` class documentation
([`ContactsContract.java`, class javadoc](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/ContactsContract.java),
mirrored at <https://developer.android.com/reference/android/provider/ContactsContract>):

> A row in the `Data` table can store any kind of personal data, such as a phone number or email
> addresses. **The set of data kinds that can be stored in this table is open-ended. There is a
> predefined set of common kinds, but any application can add its own data kinds.**
>
> A row in the `RawContacts` table represents a set of data describing a person and associated with a
> single account (for example, one of the user's Gmail accounts).
>
> A row in the `Contacts` table represents an aggregate of one or more RawContacts presumably
> describing the same person.

Two consequences that shape everything below:

* A vCard `UID` has no dedicated column; the sync-adapter-owned columns are the carrier (§7).
* Because aggregate `Contacts` rows are created *automatically* by the aggregator, inserting a second
  `RawContacts` row for the same person does not necessarily produce a second visible contact — it may
  be silently merged into the existing aggregate. That partially masks duplication bugs and makes
  "update vs duplicate" a matter of row-keying in *our* account, not of visible output
  ([`AggregationExceptions`](https://developer.android.com/reference/android/provider/ContactsContract.AggregationExceptions),
  doc: "manual aggregation and disaggregation").

## 2. Version mismatch: what Android's own vCard code does with 4.0

Android's vCard implementation is `frameworks/opt/vcard` (`com.android.vcard`), a platform
`java_library` used by the platform Contacts app, whose implementation classes are marked `@hide`
([`Android.bp`](https://android.googlesource.com/platform/frameworks/opt/vcard/+/refs/heads/main/Android.bp)) —
i.e. an internal library rather than a dependency a third-party app is meant to build on. Its own
source labels the 4.0 support as unfinished:

* [`VCardParser_V40.java`](https://android.googlesource.com/platform/frameworks/opt/vcard/+/refs/heads/main/java/com/android/vcard/VCardParser_V40.java):
  "vCard parser for vCard 4.0. **DO NOT USE IN PRODUCTION.** … this parser is based on vCard 4.0
  specification rev 15 (partly). Note that some of current implementation lack basic capability
  required in vCard 4.0. (e.g. PHOTO has data parameter in rev 15 while this implementation requires
  "ENCODING=b")".
* [`VCardParserImpl_V40.java`](https://android.googlesource.com/platform/frameworks/opt/vcard/+/refs/heads/main/java/com/android/vcard/VCardParserImpl_V40.java)
  is 91 lines and overrides only the version string, the known-property set and `\N` unescaping
  ("TODO: more strictly, vCard 4.0 requires different type of unescaping rule toward each property");
  its `@hide` javadoc says "vCard 4.0 is not published yet. Also this implementation is premature."
* The default type is not even 3.0: `VCardConfig.VCARD_TYPE_DEFAULT = VCARD_TYPE_V21_GENERIC`
  ([`VCardConfig.java`](https://android.googlesource.com/platform/frameworks/opt/vcard/+/refs/heads/main/java/com/android/vcard/VCardConfig.java)),
  and the platform Contacts app exports with the resource string
  `config_export_vcard_type = "default"`
  ([`Contacts/res/values/donottranslate_config.xml`](https://android.googlesource.com/platform/packages/apps/Contacts/+/refs/heads/main/res/values/donottranslate_config.xml),
  consumed by
  [`ExportProcessor.java`](https://android.googlesource.com/platform/packages/apps/ContactsCommon/+/refs/heads/main/src/com/android/contacts/common/vcard/ExportProcessor.java)),
  i.e. Android's own contact export is vCard **2.1**.

Per-construct behaviour of the importer, from
[`VCardEntry.addProperty`](https://android.googlesource.com/platform/frameworks/opt/vcard/+/refs/heads/main/java/com/android/vcard/VCardEntry.java)
(the single dispatch method for every parsed property):

* **Parameter syntax is tolerated but not understood.** `VCardParserImpl_V21.handleParams` throws
  `VCardException("Unknown type …")` for any parameter that is not `TYPE`/`VALUE`/`ENCODING`/
  `CHARSET`/`LANGUAGE`/`X-*`, but
  [`VCardParserImpl_V30.handleParams`](https://android.googlesource.com/platform/frameworks/opt/vcard/+/refs/heads/main/java/com/android/vcard/VCardParserImpl_V30.java)
  catches that exception and stores the parameter anyway under its own name ("maybe IANA type"), and
  `VCardParserImpl_V40` inherits it. So `PREF=1`, `ALTID=1`, `PID=1`, `MEDIATYPE=…` parse without
  error — and are then never read. Only `PREF` *inside* the `TYPE` collection is honoured
  (`VCardEntry` checks `PARAM_TYPE_PREF` only while iterating `paramMap.get("TYPE")`, inside the
  branches for `N`, `EMAIL`, `ORG`, `PHOTO`, `TEL` and `IMPP`/`X-SIP`). `ALTID`, `PID` and
  `CLIENTPIDMAP` have no consumer at all, so
  grouping of alternative representations and parameter-instance pairing are lost with no trace.
* **`KIND` and `MEMBER` are in `VCardParser_V40.sKnownPropertyNameSet` but handled nowhere in
  `VCardEntry`** — no branch matches, so they fall through the final `} else { }`. Same for `UID`,
  `GENDER`, `GEO`, `TZ`, `LANG`, `KEY`, `XML`, `FBURL`, `CALURI`, `PRODID`, `SOURCE`, `REV`,
  `CLIENTPIDMAP` and `RELATED`. (`RELATED` is a special case: the provider *does* have a
  `CommonDataKinds.Relation` kind, and the stock exporter even emits it as an `X-ANDROID-CUSTOM`
  row — see `VCardBuilder.sAllowedAndroidPropertySet` — but the importer never reads the 4.0
  `RELATED` property.)
* **`ROLE` is deliberately discarded** — the source comment is literally
  "This conflicts with TITLE. Ignore for now…".
* **`TEL`**: `VCardEntry` strips the 4.0 URI wrapper — `tel:` is removed, `sip:` is diverted to
  `SipAddress`, and any other scheme "we may still have non-URI phone number. To keep given data as
  much as we can, just save original value here" — so a `TEL;VALUE=uri:tel:+1-555-0100` becomes the
  string `+1-555-0100`, which is correct, while e.g. a `mailto:`-style URI would be stored verbatim
  as a phone number.
* **`BDAY` / `ANNIVERSARY`**: no date parsing or validation happens at import; the raw string is
  written to `Event.START_DATE` (`BirthdayData`/`AnniversaryData`, `Event.TYPE_BIRTHDAY` /
  `Event.TYPE_ANNIVERSARY`). The `Event` doc says only "The event start date as the user entered it.
  Type: TEXT"
  ([`ContactsContract.CommonDataKinds.Event`](https://developer.android.com/reference/android/provider/ContactsContract.CommonDataKinds.Event)).
  Which RFC 6350 forms *render* is decided by the consumer, and the platform UI's accepted set is
  narrow — `ContactsCommon`
  [`DateUtils.DATE_FORMATS`](https://android.googlesource.com/platform/packages/apps/ContactsCommon/+/refs/heads/main/src/com/android/contacts/common/util/DateUtils.java)
  is `{yyyy-MM-dd, yyyy-MM-dd'T'HH:mm:ss.SSS'Z', yyyy-MM-dd'T'HH:mm'Z', yyyyMMdd,
  yyyyMMdd'T'HHmmssSSS'Z', yyyyMMdd'T'HHmmss'Z', yyyyMMdd'T'HHmm'Z'}` plus
  `--MM-dd` (`CommonDateUtils.NO_YEAR_DATE_FORMAT`, with a hand-written special case for `--02-29`).
  So RFC 6350's basic-format `19850412` and `--MM-dd` parse; the RFC 6350 forms `1985-04`,
  `--0412`, `---12` and bare `1985` do **not** and are displayed as raw text.
* **`PHOTO`**: bytes are only produced by the parser when the property carries `ENCODING=b`/`BASE64`
  (`VCardParserImpl_V21.handlePropertyValue` chooses the byte path on the current encoding). A 4.0
  `data:` URI or a `VALUE=uri` reference yields a string value, `PhotoData.mBytes` stays null,
  `PhotoData.isEmpty()` is true, and `InsertOperationConstrutor.onElement` skips empty elements —
  the photo is dropped. The one guard in the code checks `paramMap.get("VALUE").contains("URL")`
  (upper-case), which the 4.0 spelling `VALUE=uri` does not match.
* **Unknown `X-` properties *are* collected** — `VCardEntry` keeps `(name, value)` pairs in
  `mUnknownXData` with the comment "Catch all for X- properties. The caller can decide what to do
  with these." — but nothing in the stock import path reads `getUnknownXData()`
  (`VCardEntryCommitter` only calls `constructInsertOperations`), so in stock Android they are lost
  too. Only the Android-specific `X-ANDROID-CUSTOM` container is round-tripped, and it is written by
  the importer as a `Data` row with the MIME type embedded in the value; the exporter will only emit
  it back for `Nickname`, `Event` and `Relation` (`VCardBuilder.sAllowedAndroidPropertySet`).

**Bottom line for #6's first bullet:** the version mismatch is not a hard parse failure — it is
silent, selective loss. Nothing in the provider stops *us* from keeping any of it; we simply cannot
delegate the work to Android's vCard code, and we must not expect stock Android to have preserved
these fields if a contact was ever imported by the platform Contacts app.

## 3. Property-by-property mapping table

"Stock importer" = what `com.android.vcard` + `VCardEntryCommitter` does today; "provider" = what the
`ContactsContract` schema can hold if we write the rows ourselves. Status is judged on the provider
column set (v1 writes its own rows), with the stock importer's behaviour noted because it is evidence
of what the platform considers representable.

| vCard 4.0 construct (RFC 6350) | Provider representation | Status | Evidence |
|---|---|---|---|
| `VERSION` | nothing (ignored) | mapped (n/a) | `VCardEntry.addProperty`: "vCard version. Ignore this." |
| `FN` | `StructuredName.DISPLAY_NAME` | mapped | `NameData.constructInsertOperation` |
| `N` (family;given;additional;prefix;suffix) | `StructuredName` `FAMILY_NAME`/`GIVEN_NAME`/`MIDDLE_NAME`/`PREFIX`/`SUFFIX` | mapped | `NameData.constructInsertOperation`; [StructuredName doc](https://developer.android.com/reference/android/provider/ContactsContract.CommonDataKinds.StructuredName) |
| `N;SORT-AS=` | `StructuredName.PHONETIC_*` (stock) — no sort key column; `RawContacts.SORT_KEY_ALTERNATIVE` is provider-computed | mapped-with-loss | `buildSinglePhoneticNameFromSortAsParam`; `ContactsContract.ContactsColumns.SORT_KEY_ALTERNATIVE` |
| `SORT-STRING` (3.0) | stock importer puts it in `StructuredName.PHONETIC_GIVEN_NAME` when no phonetic names exist | mapped-with-loss | `NameData.constructInsertOperation` ("SORT-STRING is used only when phonetic names aren't specified") |
| `NICKNAME` | `Nickname.NAME` (`DATA1`) | mapped | `VCardEntry.addNickName`; `CommonDataKinds.Nickname` |
| `ORG` (organization;unit…) | `Organization.COMPANY` (`DATA1`), `Organization.DEPARTMENT` (`DATA5`) | mapped (extra ORG components beyond the second are dropped) | `handleOrgValue`; [Organization doc](https://developer.android.com/reference/android/provider/ContactsContract.CommonDataKinds.Organization) |
| `TITLE` | `Organization.TITLE` (`DATA4`) | mapped | `handleTitleValue` |
| `ROLE` | none | **unmappable** | `VCardEntry`: "This conflicts with TITLE. Ignore for now…" |
| `TEL` (`tel:` URI, `TYPE=`) | `Phone.NUMBER` (`DATA1`) + `Phone.TYPE` (`DATA2`) / `LABEL` (`DATA3`) | mapped | `VCardEntry` TEL branch; [Phone types](https://developer.android.com/reference/android/provider/ContactsContract.CommonDataKinds.Phone) (HOME…ASSISTANT, MMS, custom+label) |
| `TEL` non-`tel:` URI scheme | stored verbatim as the number string | mapped-with-loss | `VCardEntry`: "just save original value here" |
| `PREF=1` (parameter) | none — must arrive as `TYPE=PREF` to set `Data.IS_PRIMARY` | mapped-with-loss | `VCardEntry` reads `PREF` only inside the `TYPE` collection |
| `EMAIL` | `Email.ADDRESS` (`DATA1`) + `Email.TYPE` (`DATA2`) | mapped | `VCardEntry` EMAIL branch |
| `IMPP` | `SipAddress.SIP_ADDRESS` **only if the value starts with `sip:`**; all other schemes dropped by the stock importer | mapped-with-loss (xmpp/other IMPP must be mapped by us to `Im` with `PROTOCOL_CUSTOM`) | `VCardEntry` IMPP branch; [SipAddress](https://developer.android.com/reference/android/provider/ContactsContract.CommonDataKinds.SipAddress), [Im](https://developer.android.com/reference/android/provider/ContactsContract.CommonDataKinds.Im) |
| `X-AIM`/`X-MSN`/… | `Im.DATA` + `Im.PROTOCOL_*` (`sImMap`) | mapped (legacy, not 4.0) | `VCardEntry.sImMap` |
| `ADR` (pobox;ext;street;locality;region;code;country) | `StructuredPostal` `POBOX`(`DATA5`), `STREET`(`DATA4`), `CITY`(`DATA7`), `REGION`(`DATA8`), `POSTCODE`(`DATA9`), `COUNTRY`(`DATA10`), plus `FORMATTED_ADDRESS` (`DATA1`) | mapped-with-loss (the `ext` component has no column; stock importer folds it into `STREET`) | `PostalData.constructInsertOperation` ("streetString = mStreet + " " + mExtendedAddress"); [StructuredPostal](https://developer.android.com/reference/android/provider/ContactsContract.CommonDataKinds.StructuredPostal) |
| `URL` | `Website.URL` (`DATA1`) + `TYPE` | mapped | `VCardEntry` URL branch; `CommonDataKinds.Website` |
| `NOTE` | `Note.NOTE` (`DATA1`) | mapped | `CommonDataKinds.Note` |
| `BDAY` | `Event.START_DATE` + `TYPE_BIRTHDAY`, raw text | mapped-with-loss (unparsed 4.0 date forms render as text, §2) | `BirthdayData`; `CommonDataKinds.Event` |
| `ANNIVERSARY` | `Event.START_DATE` + `TYPE_ANNIVERSARY`, raw text | mapped-with-loss | `AnniversaryData` |
| `GENDER` | none | **unmappable** (custom MIME row only) | not handled in `VCardEntry`; absent from `CommonDataKinds` |
| `PHOTO` inline base64 | `Photo.PHOTO` (`DATA15` = thumbnail) + `Photo.PHOTO_FILE_ID` (`DATA14` = hi-res file) | mapped-with-loss: rescaled to ≤96 px thumbnail in `DATA15`, ≤480/720 px re-encoded JPEG in a file | §5 |
| `PHOTO;VALUE=uri` / `MEDIATYPE=` | `Photo` row with `PHOTO` null and the URL in `Data.SYNC1..4` is the documented pattern | mapped **if we fetch it**; the URI itself has no column | `CommonDataKinds.Photo` doc: "A common pattern is to use columns `Data.SYNC1` through `Data.SYNC4` to store temporary data, e.g. the image URL or ID … It is allowed for the `PHOTO` to be null." |
| `KIND` | none | **unmappable** (no `KIND`/kind-of-object column; `RawContacts`/`Groups`/`Data` rows are the only kinds) | not handled in `VCardEntry`; `KIND:group` handled structurally via `Groups` (§4) |
| `MEMBER` | `GroupMembership` data row (`GROUP_ROW_ID`=`DATA1` / `GROUP_SOURCE_ID`) pointing at a `Groups` row | mapped **with UID→id indirection** | §4 |
| `CATEGORIES` | none first-class; app-level mapping to `GroupMembership` rows (DAVx5's "CATEGORIES" group method) | mappable by us, unmappable in stock | `ContactsSyncManager` group-method doc, DAVx5 |
| `RELATED` | `Relation` kind exists (`Relation.NAME`=`DATA1`, `TYPE_*`) but the stock importer drops `RELATED` | mappable by us, unmappable in stock | absent from `VCardEntry`'s dispatch; `CommonDataKinds.Relation` |
| `UID` | none first-class → `RawContacts.SYNC1` by convention | mapped by convention (§7) | §7 |
| `REV`, `PRODID`, `SOURCE`, `XML`, `KEY`, `FBURL`, `CALURI` | none | **unmappable** (custom MIME row) | not handled in `VCardEntry` |
| `LANG`, `TZ`, `GEO` | none (`TZ`/`GEO` have no kind; per-property language is not modelled) | **unmappable** (custom MIME row) | not handled in `VCardEntry`; no `CommonDataKinds` equivalent |
| `ALTID`, `PID`, `CLIENTPIDMAP` | none | **unmappable** — parsed into the parameter map and never read, so even property grouping is lost | `VCardParserImpl_V30.handleParams` fallback + no consumer in `VCardEntry` |
| arbitrary `X-*` | a `Data` row with a custom MIME type — the documented open-ended extension point | storable; **invisible to stock apps** | `ContactsContract` class doc ("any application can add its own data kinds"); §6 |
| `AGENT` (3.0), `SOUND`/`LOGO` | folded or ignored (`LOGO` treated as `PHOTO`; `SOUND;X-IRMC-N` becomes phonetic name) | mapped-with-loss / unmappable | `VCardEntry` SOUND/LOGO branches |

## 4. Groups: `KIND:group` + `MEMBER` ↔ `Groups` / `GroupMembership`

RFC 6350 makes a group a *vCard object*: `KIND:group`, `MEMBER` values are URIs that identify members,
"MEMBER … MUST NOT be present unless the value of the KIND property is 'group'"
([RFC 6350 §6.6.5](https://www.rfc-editor.org/rfc/rfc6350.html#section-6.6.5), and
[§6.1.4](https://www.rfc-editor.org/rfc/rfc6350.html#section-6.1.4) for `KIND`).

The provider has no notion of a group vCard. It has:

* `ContactsContract.Groups` — a table with `TITLE`, `NOTES`, `SYSTEM_ID`, `FAVORITES`, `AUTO_ADD`,
  `GROUP_VISIBLE`, `SHOULD_SYNC`, and (via `SyncColumns`) `ACCOUNT_TYPE`/`ACCOUNT_NAME`/`SOURCE_ID`/
  `SYNC1..4`; the class doc adds "The current API does not support the notion of groups spanning
  multiple accounts."
* `CommonDataKinds.GroupMembership` — a `Data` row with MIME `vnd.android.cursor.item/group_membership`
  where "Exactly one of [`GROUP_ROW_ID` (`DATA1`)] or [`GROUP_SOURCE_ID`] must be set when inserting a
  row" (<https://developer.android.com/reference/android/provider/ContactsContract.CommonDataKinds.GroupMembership>).

So mapping 4.0 groups requires translating a UID-set into *two different row types*:

1. One `Groups` row per group vCard, with `TITLE` from the group's `FN` and the group's vCard `UID`
   stored in a sync column (§7), keyed by account.
2. One `GroupMembership` data row per member, on the **member's raw contact**, referencing the group.

**Ordering.** The membership row is attached to a *raw contact id* and a *group id*; the member's raw
contact must therefore already exist, and the group row must exist — unless you use the
`GROUP_SOURCE_ID` form, which the provider resolves itself:

> [`DataRowHandlerForGroupMembership.getOrMakeGroup`](https://android.googlesource.com/platform/packages/providers/ContactsProvider/+/refs/heads/android13-release/src/com/android/providers/contacts/DataRowHandlerForGroupMembership.java):
> "Returns the group id of the group with sourceId and the same account as rawContactId. **If the
> group doesn't already exist then it is first created.**"

with `resolveGroupSourceIdInValues` rewriting `GROUP_SOURCE_ID` into `GROUP_ROW_ID` before insert, and
throwing `IllegalArgumentException` if both or neither are set. The group is looked up *in the raw
contact's account*, so a group and its members must share an account.

Because `MEMBER` carries UIDs and Android needs ids, the practical shape is a **two-pass sync**: pass
one inserts/updates all individual contacts (recording UID → raw contact id, or simply storing the
UID in `SYNC1` and looking it up by query), pass two resolves each group's member UIDs into membership
rows. This is exactly what the most widely deployed open-source CardDAV client does, and its source
documents the reasoning:

> DAVx5 [`ContactsSyncManager.kt`](https://github.com/bitfireAT/davx5-ose/blob/main/core/src/main/kotlin/at/bitfire/davdroid/sync/ContactsSyncManager.kt):
> "Groups as separate VCards: individual and group contacts (with a list of member UIDs) are
> distinguished. … When downloading remote contacts, groups (+ member information) may be received by
> the actual members. Thus, the member lists have to be cached until all VCards are received. This is
> done by caching the member UIDs of each group in `AddressContract.GroupColumns.PENDING_MEMBERS`. In
> `postProcess`, these "pending memberships" are assigned to the actual contacts and then cleaned up."

That client stores that deferred member list in the group row itself
(`Groups.SYNC3`, "List of member UIDs, as sent by server"), and reads membership back by joining
`GroupMembership.GROUP_ROW_ID` to the member's stored UID
([`AndroidGroup.kt`](https://github.com/bitfireAT/davx5-ose/blob/main/synctools/src/main/kotlin/at/bitfire/synctools/storage/contacts/AndroidGroup.kt)).

**Must contacts be inserted before groups can reference them?** Strictly, yes for the *contact* side:
`raw_contact_id` is a numeric id produced by the insert, and membership rows are `Data` rows hanging
off it, so you cannot write a membership before its contact exists. On the *group* side, no: the
`GROUP_SOURCE_ID` path auto-creates the group when the first membership referencing it is written.
A `MEMBER` entry that points at a UID you never received cannot be represented at all (v1 read-only:
accept the asymmetry and either drop it or keep the group's raw member list in a custom MIME row).

## 5. Photos

The split is documented on `CommonDataKinds.Photo`
(<https://developer.android.com/reference/android/provider/ContactsContract.CommonDataKinds.Photo>):

* `Photo.PHOTO` = `DATA15` — "By convention, binary data is stored in DATA15. **The thumbnail of the
  photo is stored in this column.**"
* `Photo.PHOTO_FILE_ID` = `DATA14` — "ID of the hi-res photo file."
* and the explicit statement that a photo row may have no bytes at all: "It is allowed for the
  `PHOTO` to be null."

What actually happens when we insert a photo blob, from
[`DataRowHandlerForPhoto`](https://android.googlesource.com/platform/packages/providers/ContactsProvider/+/refs/heads/android13-release/src/com/android/providers/contacts/DataRowHandlerForPhoto.java)
and
[`PhotoProcessor`](https://android.googlesource.com/platform/packages/providers/ContactsProvider/+/refs/heads/android13-release/src/com/android/providers/contacts/PhotoProcessor.java):

1. `preProcessPhoto` → `processPhoto`: the bytes are decoded, and `PhotoProcessor` produces a
   **display photo** (scaled to fit `getMaxDisplayPhotoDim()`), a **thumbnail**
   (`getMaxThumbnailPhotoDim()`), and re-encodes both as JPEG.
2. `PhotoStore.insert` writes the display photo to a file under the provider's photo store and returns
   a file id; if the display photo "is already thumbnail-sized or smaller, this will do nothing (and
   will return 0)" — then `PHOTO_FILE_ID` is set to null.
3. `Data` gets `PHOTO = <compressed thumbnail bytes>` and `PHOTO_FILE_ID = <file id>`.

Limits, from `PhotoProcessor`'s static initialiser: `DEFAULT_THUMBNAIL = 96` px;
`DEFAULT_DISPLAY_PHOTO_MEMORY_CONSTRAINED = 480` px, `DEFAULT_DISPLAY_PHOTO_LARGE_MEMORY = 720` px
chosen by reported RAM (`LARGE_RAM_THRESHOLD = 640 MiB`); compression quality 75 for the display
photo, 90/95 for the thumbnail. Both are overridable by device property
(`ContactsProperties.thumbnail_size()` / `.display_photo_size()`), and the effective values are
queryable at runtime without blocking the UI:
`ContactsContract.DisplayPhoto.CONTENT_MAX_DIMENSIONS_URI` returns `DISPLAY_MAX_DIM` /
`THUMBNAIL_MAX_DIM`, with the doc note "Larger photos will be down-sized to fit within a square of
this many pixels" (<https://developer.android.com/reference/android/provider/ContactsContract.DisplayPhoto>;
provider side: `ContactsProvider2` case `PHOTO_DIMENSIONS`).

**"A photo too large for the thumbnail column"** therefore does not fail — it is *scaled*, twice: the
full-size image goes to a byte-capped file (≤ max display dim) and `DATA15` receives a ≤96 px JPEG.
The original bytes are never preserved, so byte-exact round-trip of `PHOTO` is off the table. Two
further behaviours matter:

* If the image cannot be decoded (unsupported format, corrupt data), `DataRowHandlerForPhoto.insert`
  returns 0 — **no exception, no photo row**. A silent failure mode worth logging on our side.
* A URI-referenced photo (`PHOTO;VALUE=uri:https://…`) has no column; the documented representation is
  a photo row with null `PHOTO` plus the URL and download state in `Data.SYNC1..4`.

[INFERENCE] Photos must pass through Binder as part of the `ContentValues` of the insert, so sending
several megabytes per contact is a bad idea independent of the provider's own limits; downscaling
before insert (and optionally keeping the original server-side or in a custom MIME row) is the
pragmatic choice. This is not stated in the sources above.

## 6. Custom and unmappable fields

Yes — a custom MIME type in `Data` is the sanctioned place. This is not folklore: it is in the
`ContactsContract` class documentation itself ("The set of data kinds that can be stored in this table
is open-ended … any application can add its own data kinds", §1), and the provider implements it
generically:

* [`ContactsProvider2.getDataRowHandler`](https://android.googlesource.com/platform/packages/providers/ContactsProvider/+/refs/heads/android13-release/src/com/android/providers/contacts/ContactsProvider2.java)
  returns `new DataRowHandlerForCustomMimetype(..., mimeType)` for any MIME type it has no
  specialised handler for — there is no whitelist and no rejection.
* [`DataRowHandlerForCustomMimetype`](https://android.googlesource.com/platform/packages/providers/ContactsProvider/+/refs/heads/android13-release/src/com/android/providers/contacts/DataRowHandlerForCustomMimetype.java)
  is an empty subclass of `DataRowHandler`, i.e. arbitrary MIME types are first-class `Data` rows.
* `ContactsDatabaseHelper.getMimeTypeId(mimeType)` inserts an unknown MIME type into the provider's
  `mimetypes` table on demand.
* The columns available to such a row are `DATA1..DATA15` (with `DATA15` conventionally BLOBs) plus
  `SYNC1..SYNC4`, `IS_PRIMARY`, `IS_SUPER_PRIMARY`, `IS_READ_ONLY`, `RES_PACKAGE`.

The deployed convention (DAVx5) is a synthetic vendor MIME per purpose — e.g.
`x.davdroid/unknown-properties` with the serialized properties in `DATA1`, and
`x.davdroid/cached-group-membership` with the group id in `DATA1`
([`AddressContract.kt`](https://github.com/bitfireAT/davx5-ose/blob/main/synctools/src/main/kotlin/at/bitfire/synctools/storage/contacts/AddressContract.kt)).
That file's header comment describes precisely this use ("How synctools uses some Android contacts
sync columns and data rows"), and it is the single best worked example to copy for our own layout.

**How stock apps treat such rows: they ignore them.** The platform Contacts UI resolves a data row to
a UI element through a `DataKind` registered by an account type; unknown MIME types resolve to null
and are simply not rendered or edited:

> [`AccountTypeManager.getKindOrFallback`](https://android.googlesource.com/platform/packages/apps/ContactsCommon/+/refs/heads/main/src/com/android/contacts/common/model/AccountTypeManager.java):
> "Find the best `DataKind` matching the requested … `mimeType`. If no direct match found, we try
> searching `FallbackAccountType`." → on failure it logs `"Unknown type=…, mime=…"` and returns null.

Consequences for us: (1) custom rows are invisible to stock UI and cannot confuse it, and they are not
a documented liability for stock editing — but "the stock editor preserves rows whose MIME type it has
no `DataKind` for" is **[INFERENCE]**, not something these sources state, and OEM editors are a
separate unknown (§10); (2) because they are invisible, the user will never see `MEMBER`/`X-`/`GEO`
data on the phone — if a field matters to the user, prefer a stock kind plus a custom row holding the
exact original; (3) `Data.IS_READ_ONLY = 1` marks a row as modifiable only by a sync adapter (§8),
which is the documented way to protect our rows from other writers.

## 7. Identity and update semantics — the decision-critical part

**Nothing in vCard maps to a provider key automatically.** `UID` is read by no Android code (§2), and
the stock importer's insert operations contain only `ACCOUNT_NAME`, `ACCOUNT_TYPE` and `STARRED` —
[`VCardEntry.constructInsertOperations`](https://android.googlesource.com/platform/frameworks/opt/vcard/+/refs/heads/main/java/com/android/vcard/VCardEntry.java)
never writes `SOURCE_ID` or `SYNC*` — which is why two stock imports of the same file produce two raw
contacts. (The library even carries the comment `@hide this interface may be changed for better
support of vCard 4.0 (UID)` on `getChildlen()`.) If we insert rows the same way, sync #2 duplicates
sync #1.

The provider's own key for this is documented under
[`ContactsContract.SyncColumns`](https://developer.android.com/reference/android/provider/ContactsContract.SyncColumns):

> `SOURCE_ID` — "String that uniquely identifies this row to its source account."
> `ACCOUNT_NAME` / `ACCOUNT_TYPE` — "identifies a specific account."
> `SYNC1..SYNC4` (`BaseSyncColumns`) — "Generic column for use by sync adapters. The specific functions
> of these columns are private to the sync adapter. Other clients of the API should not attempt to
> either read or write this column."
> `DIRTY` — "Flag indicating that `VERSION` has changed, and this row needs to be synchronized by its
> owning account."

`SyncColumns` is mixed into both `RawContacts` and `Groups`. `Data` has its own `SYNC1..4`
(`data_sync1..4`) for per-attribute state.

### Recommended convention (UID + ETag)

Identical layout for contacts and groups, matching the deployed DAVx5 layout
([`AddressContract.RawContactColumns` / `GroupColumns`](https://github.com/bitfireAT/davx5-ose/blob/main/synctools/src/main/kotlin/at/bitfire/synctools/storage/contacts/AddressContract.kt)):

| Server-side fact | Provider column | Why |
|---|---|---|
| resource href / file name (`SOURCE_ID` role) | `RawContacts.SOURCE_ID` (groups: `Groups.SOURCE_ID`) | It is the column whose *documented purpose* is "uniquely identifies this row to its source account"; DAVx5 maps "file name (vCard FILENAME)" onto exactly this column. Mutable hrefs are rarer than mutable UIDs, and the href is what you need to issue a GET. |
| vCard `UID` | `RawContacts.SYNC1` (groups: `Groups.SYNC1`) | Stable contact identity, needed to resolve `MEMBER` by UID and to detect a server-side href change. |
| HTTP `ETag` | `RawContacts.SYNC2` (groups: `Groups.SYNC2`) | Row-level concurrency/change detection; the value `If-Match` needs. |
| misc flags / cached state | `SYNC3`, `SYNC4` | DAVx5 uses `SYNC3` for a data hash and `SYNC4` for sync flags; for groups it uses `Groups.SYNC3` for the pending `MEMBER` UID list. |
| collection-level sync token / CTag | `ContactsContract.SyncState` (per account, `byte[]` via `SyncStateContract`) or an app-private store | Documented as "A table provided for sync adapters to use for storing private sync state data for contacts" (<https://developer.android.com/reference/android/provider/ContactsContract.SyncState>). Note it is keyed by *account*, not by collection; with multiple collections we need our own keying (or one account per collection). |

**Lookup for sync #2** (this is the update-instead-of-duplicate step): query
`RawContacts.CONTENT_URI` with `SOURCE_ID = <href>` (fallback `SYNC1 = <UID>`) scoped to the account —
either by adding `ACCOUNT_TYPE`/`ACCOUNT_NAME` to the selection, or by putting them in the URI query
parameters, which the provider reads as a filter/assignment for collection-level URIs
(`ContactsProvider2.resolveAccount`; DAVx5 does exactly this in `asSyncAdapter(account)`). If a row is
found, update its `Data` rows (diff by MIME + values, or delete-and-reinsert within the same raw
contact) and write back the new ETag into `SYNC2`; if not found, insert a new raw contact with all of
`ACCOUNT_*`, `SOURCE_ID`, `SYNC1`, `SYNC2`.

Two caveats:

* `SOURCE_ID` is *not* unique in the provider schema across accounts, and there is no unique
  constraint enforcing it: the provider creates a **non-unique** index
  `raw_contacts_source_id_account_id_index ON raw_contacts (sourceid, account_id)` and
  `groups_source_id_account_id_index ON groups (sourceid, account_id)`
  ([`ContactsDatabaseHelper.java`](https://android.googlesource.com/platform/packages/providers/ContactsProvider/+/refs/heads/android13-release/src/com/android/providers/contacts/ContactsDatabaseHelper.java)),
  in deliberate contrast to `raw_contacts_backup_id_account_id_index`, which *is* `CREATE UNIQUE`.
  The pair `(account, SOURCE_ID)` is therefore the shape the provider expects and optimises for, but
  it is a convention our sync code must keep true — not a guarantee. If a server reuses hrefs across
  collections, use one account per collection or include the collection in the stored value.
* `RawContacts` rows for the same person from *different* accounts will be aggregated by the platform
  aggregator; that is expected and not a duplication bug, but it means "one address book = one
  account" is the clean mental model.

## 8. Account ownership, `CALLER_IS_SYNCADAPTER`, and account removal

* **Binding rows to an account**: `RawContacts.ACCOUNT_TYPE` + `ACCOUNT_NAME` (plus the optional
  `DATA_SET` for multiple sync adapters per account type). They may be supplied either in the
  `ContentValues` or as URI query parameters; supplying only one of the two, or mismatching the two
  sources, throws: `ContactsProvider2.resolveAccount` → "Must specify both or neither of
  ACCOUNT_NAME and ACCOUNT_TYPE" / "When both specified, ACCOUNT_NAME and ACCOUNT_TYPE must match".
  If neither is supplied, `resolveAccount` returns null and
  `ContactsDatabaseHelper.getOrCreateAccountIdInTransaction(null)` maps it to
  `AccountWithDataSet.LOCAL` — i.e. "no account" is not an error, it silently becomes local storage.
* **Is `CALLER_IS_SYNCADAPTER` required for our writes?** Not for permission — it is a query parameter,
  evaluated in the provider, not a permission check
  ([`ContactsContract.CALLER_IS_SYNCADAPTER`](https://developer.android.com/reference/android/provider/ContactsContract):
  "An optional URI parameter for insert, update, or delete queries that allows the caller to specify
  that it is a sync adapter. The default value is false."). It is effectively required for *correct*
  sync-adapter behaviour, for four distinct reasons visible in the source:
  1. Without it, every raw contact we touch is added to the transaction's dirty set —
     [`TransactionContext.markRawContactDirtyAndChanged`](https://android.googlesource.com/platform/packages/providers/ContactsProvider/+/refs/heads/android13-release/src/com/android/providers/contacts/TransactionContext.java):
     `if (!isSyncAdapter) { mDirtyRawContacts.add(rawContactId); }` — so rows we download get
     `DIRTY = 1` and any real sync adapter for that account will later treat them as local edits.
  2. Without it, `updateData` / `updateRawContacts` add `Data.IS_READ_ONLY = 0` /
     `RawContacts.RAW_CONTACT_IS_READ_ONLY = 0` to the selection, so read-only rows (including the ones
     we mark ourselves) are silently skipped (the update affects 0 rows, no exception).
  3. Without it, inserting a raw contact or a group triggers non-sync-adapter side effects: favourite
     group membership for `STARRED` raw contacts, auto-add group membership, and the "insert all
     starred contacts into the favourites group" behaviour of `insertGroup`.
  4. Without it, `mSyncToNetwork` is set, so `notifyChange` requests a network sync that nobody
     wants.
  Practical consequence: our writes MUST append `CALLER_IS_SYNCADAPTER=true` (DAVx5's
  `Uri.asSyncAdapter()` helper exists solely for this, with the comment "Appends
  `ContactsContract.CALLER_IS_SYNCADAPTER` to prevent dirty-marking"). This is independent of whether
  we register an actual `SyncAdapter` service.
* **What happens to the data when the account is removed** — the sharp edge. The provider listens for
  account changes (`ContactsProvider2.initialize()`, called from `onCreate`, schedules
  `BACKGROUND_TASK_UPDATE_ACCOUNTS`; `onAccountsUpdated` re-schedules it, and the task registers the
  `AccountManager` listener on first run). The background task
  [`updateAccountsInBackground`](https://android.googlesource.com/platform/packages/providers/ContactsProvider/+/refs/heads/android13-release/src/com/android/providers/contacts/ContactsProvider2.java)
  computes the accounts present in the contacts DB and *not* present in `AccountManager` (excluding the
  local account and SIM accounts) and deletes them:

  > `db.execSQL("DELETE FROM " + Tables.GROUPS + " WHERE " + GroupsColumns.ACCOUNT_ID + " = ?", …)`
  > … then the raw contacts of that account id, with singleton aggregate contacts deleted through
  > `ContactsTableUtil.deleteContact` ("Contacts are deleted by a trigger on the raw_contacts table").

  There is no confirmation prompt and no "recover later" path — this runs in the background after
  account changes and also at provider start (`haveAccountsChanged` compares the system account list
  against a stored snapshot in the DB property `known_accounts`).

  Therefore: **if we bind rows to an `ACCOUNT_TYPE`/`ACCOUNT_NAME` that is not registered in
  `AccountManager`, the provider will treat that account as stale and delete all of its groups and raw
  contacts.** Two legitimate designs avoid this:
  * *Register a real account* (`AccountManager.addAccountExplicitly`, typically as part of a
    `AbstractAccountAuthenticator`/sync-adapter setup). The data then survives until the user removes
    the account — and when they do remove it, the deletion above is the documented, intended
    behaviour (and a user-visible "this will delete your contacts" flow is the norm).
  * *Use no account at all* (local account) — data is not tied to a removable account, but then
    `Groups`/`GroupMembership` lose their account scoping and `SOURCE_ID`-scoped identity has no
    account to be scoped to; the provider's default-account API also refuses such accounts
    (`setDefaultAccountSetting`: "Cannot set default account for invalid accounts." for anything that
    is neither local nor present in `AccountManager`). Not recommended once groups are in play.

  Note also `ContactsProperties.keep_stale_account_data()` ("debug.contacts.ksad"), a debug-only flag
  that suppresses this cleanup; it must never be relied on.

## 9. Concrete gotcha list (each with a citation)

1. `UID` is dropped by every Android vCard code path — `VCardEntry.addProperty` has no `UID` branch, and
   `constructInsertOperations` writes no sync columns. Never trust "Android imported it" as a source of
   identity.
2. `KIND`, `MEMBER`, `GENDER`, `GEO`, `TZ`, `LANG`, `KEY`, `XML`, `FBURL`, `CALURI`, `PRODID`, `SOURCE`,
   `REV`, `CLIENTPIDMAP`, `RELATED` fall through the same empty `else` in `VCardEntry.addProperty`.
3. `PREF=1` (RFC 6350 §5.3) is parsed into the parameter map but read by nobody; only `TYPE=PREF` sets
   `Data.IS_PRIMARY`. `<https://www.rfc-editor.org/rfc/rfc6350.html#section-5.3>`
4. `ALTID` (RFC 6350 §5.4) is likewise accepted and ignored, so multi-language alternates become
   unrelated duplicate rows. `<https://www.rfc-editor.org/rfc/rfc6350.html#section-5.4>`
5. `ROLE` is dropped on purpose ("This conflicts with TITLE. Ignore for now…").
6. The second `ADR` component (extended address) has no `StructuredPostal` column; the stock importer
   concatenates it into `STREET`, so a naive round-trip mangles addresses.
7. `IMPP` with anything other than a `sip:` URI is dropped by the stock importer.
8. `BDAY:19850412` parses for display but `1985-04`, `--0412`, `---12` and `1985` do not — see
   `DateUtils.DATE_FORMATS` / `CommonDateUtils.NO_YEAR_DATE_FORMAT`.
9. Photo bytes are re-encoded to JPEG and rescaled: ≤96 px into `DATA15`, ≤480/720 px into the file
   store (`PhotoProcessor`); originals are never kept. A photo row can also silently fail to appear if
   decoding fails (`DataRowHandlerForPhoto.insert` returns 0).
10. `DataRowHandlerForGroupMembership` requires exactly one of `GROUP_ROW_ID` / `GROUP_SOURCE_ID`
    (else `IllegalArgumentException`), and resolves groups *within the raw contact's account*.
11. Without `CALLER_IS_SYNCADAPTER`, our writes set `DIRTY=1` on every touched raw contact
    (`TransactionContext.markRawContactDirtyAndChanged`), and read-only rows are silently skipped by
    update/delete selections.
12. Unregistered `ACCOUNT_TYPE`/`ACCOUNT_NAME` ⇒ groups + raw contacts deleted in the background
    (`updateAccountsInBackground`, triggered by `onAccountsUpdated`).
13. `Contacts` (aggregate) rows cannot be inserted directly — `insertContact` throws
    `UnsupportedOperationException("Aggregate contacts are created automatically")`.
14. `RawContacts` deletion is two-phase: `resolver.delete` marks `DELETED` and detaches from the
    aggregate; only a second delete with `CALLER_IS_SYNCADAPTER` removes the row
    (`ContactsContract.RawContacts.DELETED` doc). Irrelevant for v1 read-only, but it is the reason a
    "delete locally" in a future version does not delete anything on its own.
15. `SORT-STRING` is stored in `StructuredName.PHONETIC_GIVEN_NAME` when no phonetic names exist — a
    real quirk that shows up as a wrong sort/pronunciation column.

## 10. Not settled here / what would settle it

* **Byte-exact photo preservation** is impossible in the stock `Photo` kind by construction; whether
  the *Contacts UI* is happy with a photo row whose `PHOTO` is null but `SYNC1` holds a URL was not
  verified against a device — that needs a device test (insert such a row, open the contact, confirm
  no crash and no placeholder weirdness).
* The exact **render behaviour of every RFC 6350 date form** was derived from `DateUtils` source, not
  observed on a device; `1985-04` and `--0412` should show as raw text, which is worth confirming once
  on the target device since OEM Contacts apps differ.
* **OEM behaviour with custom MIME rows / `IS_READ_ONLY` rows** (skins such as Samsung/OnePlus
  contacts) is not covered by AOSP sources; a device test with a custom `x.…/…` row would settle it.
* **Whether a stock editor preserves `Data` rows whose MIME type has no `DataKind` when the user edits
  and saves a contact** is asserted here only as inference (§6). Settle it by inserting a
  custom-MIME row with `adb`/a test app, editing the contact name in the stock Contacts app, saving,
  and re-reading the row.
* Whether the platform aggregator will merge our raw contacts with a *different* account's raw
  contacts for the same person depends on the aggregation algorithm's match scoring, which is not
  documented normatively; treat "one address book = one account" as the safe design (aggregation rules
  are described only as "automatic" in the `ContactsContract` docs, with `AggregationExceptions` for
  manual overrides).

## References

* `ContactsContract` reference (model, `CALLER_IS_SYNCADAPTER`, `SyncColumns`, `RawContacts`,
  `Groups`, `Data`, `CommonDataKinds.*`, `DisplayPhoto`, `SyncState`):
  <https://developer.android.com/reference/android/provider/ContactsContract>
  and its source
  <https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/ContactsContract.java>
* Contacts provider implementation (branch `android13-release`):
  `ContactsProvider2.java`, `ContactsDatabaseHelper.java`, `DataRowHandlerForPhoto.java`,
  `DataRowHandlerForCustomMimetype.java`, `DataRowHandlerForGroupMembership.java`,
  `DataRowHandler.java`, `PhotoProcessor.java`, `PhotoStore.java`, `TransactionContext.java` —
  <https://android.googlesource.com/platform/packages/providers/ContactsProvider/+/refs/heads/android13-release/src/com/android/providers/contacts/>
* Android vCard library (branch `main`): `VCardEntry.java`, `VCardEntryCommitter.java`,
  `VCardBuilder.java`, `VCardConfig.java`, `VCardConstants.java`, `VCardParserImpl_V21/V30/V40.java`,
  `VCardParser_V40.java` —
  <https://android.googlesource.com/platform/frameworks/opt/vcard/+/refs/heads/main/java/com/android/vcard/>
* Platform Contacts app / ContactsCommon: `CommonDateUtils.java`, `DateUtils.java`,
  `AccountTypeManager.java`, `ExportProcessor.java`, `res/values/donottranslate_config.xml` —
  <https://android.googlesource.com/platform/packages/apps/ContactsCommon/+/refs/heads/main/> and
  <https://android.googlesource.com/platform/packages/apps/Contacts/+/refs/heads/main/>
* RFC 6350 (vCard 4.0): <https://www.rfc-editor.org/rfc/rfc6350.html> — `PREF` §5.3, `ALTID` §5.4,
  `KIND` §6.1.4, `TEL` §6.4.1, `MEMBER` §6.6.5, `PHOTO` §6.2.4, `DATE`/`DATE-AND-OR-TIME` §4.3.1/§4.3.4
* DAVx5 (open-source CardDAV sync adapter, the deployed reference for the column conventions):
  `synctools/.../AddressContract.kt`, `AndroidContact.kt`, `AndroidGroup.kt`,
  `core/.../ContactsSyncManager.kt`, `core/.../LocalAddressBook.kt` —
  <https://github.com/bitfireAT/davx5-ose>
