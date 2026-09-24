# A dirty row clears only on an answered upload, and a conflict goes to the server

v1 pushed nothing: `supportsUploading="false"` kept the framework from asking for an upload-only sync, `res/xml/contacts.xml` declared no `<EditSchema>` so the Contacts app offered the account nowhere, and every sync cleared `DIRTY` and re-applied server state. Write-back inverts all three. The two XML declarations are install-time metadata with no runtime API behind them — nothing could key them to account state — so they are flipped unconditionally, and they say only that an edit *may* be sent, never that one has been. The user's own say is `DavCollection.writable`, stored on the Account record beside the selection and defaulting **off**.

A pending edit is a `DIRTY` row or a tombstone in the provider, and step U sends it before the run's listing, because the fetch's wholesale row replacement would otherwise discard the edit before it left the phone. `DIRTY` is cleared by exactly one thing: the answer to that row's own `PUT`. The read-only backstop is deleted rather than softened, because "clear every row that is not being uploaded" is "clear every row" once uploads exist. A create is `PUT <UID>.vcf` (or `.ics`) under `If-None-Match: *`, with the UID minted once and persisted on the row before the first attempt: the one 412 a create realistically meets is its own answer getting lost, and a retry under a fresh name would duplicate the item that is meant to come back once.

An update carries `If-Match`, and a 412 says the server moved while the edit was pending. **The server wins.** The row is reverted and fetched back in the same run, and the conflict is named in the Collection's report rather than hidden. Client-wins would be exactly the lost update this rule exists to prevent, and keep-both would be a duplicate — for a calendar resource, one with no defined way to fork a single component. A pending upload does *not* withhold the Collection's state write: that rule is about what a run learned from the listing, and a pending edit is not learned that way, being a `DIRTY` row re-read at the start of every run.

## Amendment: the answer names the row it is about

"`DIRTY` is cleared by exactly one thing: the answer to that row's own `PUT`" said which write may
clear the flag, not which row it may clear it on. The row the answer is about is the row as it was
when the body was read, and an editor can change it while the request is in flight; a clear that does
not check drops the edit made during the upload — the same lost update this decision exists to
prevent, in a narrower window.

The fix is per provider, because the two providers keep different facts about a row's movement and
the mechanism has to be one the platform actually maintains.

Contacts carry a counter. `RawContacts.VERSION` moves on every insert, update or delete of a
contact's data rows, for sync adapters as much as for editors, and step U already reads it. Every
write that claims the row is current matches on the version that was read, and the writes that depend
on that claim — the rows re-derived from the sent body, the photo baseline — are in the same batch
under an expected count, so a moved row rolls the claim back whole.

Calendars carry no counter, so the flag carries one bit more. Before a resource's rows are read for
the body, every one of them is set to `DIRTY=2`. Every editor path in `CalendarProvider2` writes the
literal `1` and none reads the column first, so an edit in flight is visible as a row that is no
longer `2`. `MUTATORS` was considered and rejected: the provider appends to it rather than replacing
it, so a second edit by the same app leaves it as it was. The value `2` is invisible to this app's own
readers, which ask only whether the flag is non-zero, and to the provider, which reacts to a sync
adapter's flag only when it is `0`.

A refusal is the exception that had to be found on a device: it sends nothing, so it arms nothing, and
a sentinel guard would have matched no row and reverted nothing. `revertLocalChange` is therefore told
whether a body was sent, and the refused case guards on the row still being dirty — which is all that
can be proven when nothing was in flight, and is enough, because a read-only Collection gives the edit
back by intent.

A guard that matches nothing writes nothing. The revert reports that, and the engine keeps such a row
out of the same run's fetch: reverting an edit newer than the 412 would be client-wins by omission,
and fetching over it would be the wholesale replacement the held set exists to prevent.

## Amendment: a revert is all-or-nothing, and no update is ever sent unconditionally

Guarding each row of a calendar resource on its own was half a rule. A recurring event whose override
moved while the master's `PUT` was in flight had the master reverted — `DIRTY=0`, and the ETag nulled,
because a null ETag is what makes the listing put the server's copy back — while the override kept its
edit and kept the resource pending. The engine then held the resource out of that run's fetch, exactly
as intended, and the next run queued it as an update with no ETag to send `If-Match` with. The `PUT`
went out unconditionally and the server took the body it had just refused: the lost update this
decision exists to prevent, arrived at through the machinery meant to prevent it. Demonstrated on a
device against a proxy stalling `PUT` for 18 s: `PUT` under `If-Match: "c033…"` answered `412`, an
override edited during the stall, and 2 s later a second `PUT` with no `If-Match` answered `204`, with
the server's version gone.

So the revert of a sent change is all-or-nothing across the resource. The batch begins with an
assertion that no row of the resource has left `DIRTY=2`, expecting no rows; `SQLiteContentProvider`
applies a batch inside one transaction and never marks it successful once an operation throws, so a
resource with any moved row is left exactly as it was — the ETag above all — and the next run meets
the same `412` conditionally, with every row armed this time, and reverts whole. The same device
scenario after the change: `412`, revert withheld, and the next run's `PUT` carrying `If-Match` again.
A refusal stays row-by-row. It sends nothing, so nothing can follow it onto the server, and a
read-only Collection has to give back what it can.

The engine makes the same statement where it cannot be argued away: an update of a resource the server
already holds never leaves without `If-Match`. A row whose ETag is null does not know the server's
version, and there are only three things to do about it — send unconditionally, which is client-wins;
hold the edit forever, which strands it; or ask. It asks, with the `PROPFIND Depth: 0` for `getetag`
the session already had, and sends an ordinary conditional `PUT`. A server that names no ETag at all
leaves `If-Match: *`, which says the one thing still known: the resource exists.

A revert now fetches what it gave back. Reverting was only ever half an answer: it stops the rows
claiming to be current, and leaves the user's rejected text on them until the server's copy arrives.
That arrival was left to the same run's listing, which is sound only for a listing that names every
member. A `sync-collection` delta names what changed since the stored token, so a conflict whose
server-side change an earlier run had already consumed — and a refusal, where the server changed
nothing at all and never will — left the row looking clean while holding an edit nobody kept: silent
divergence, in the one place the design says the server wins. So what a revert left is fetched by
name before the listing, one multiget per fifty, with the ETag that multiget's own response carries.
The alternative considered was withholding the Collection's CTag and sync token
whenever a member was held, and it was rejected: a permanently refused item — a body the server will
not take, a row that will not serialise — would withhold the state on every run for as long as the
refusal lasted, which is the stall this ADR's own rule about pending changes removed. Nothing is owed
to the token, because every way out of a hold ends either in an upload the server answers or in a
revert, and a revert now fetches for itself.

**Amended:** the set is read from the rows at every run rather than handed back by the step U that
made it. A master that is named, clean and carries no ETag is holding text nobody kept — precisely
what `revertLocalChange` leaves — and nothing else produces that shape except a server that names no
ETag for a resource, which RFC 4791 §2 forbids and which already makes every listing fetch every
member. Carrying the set from step U meant one attempt: a multiget that failed, or came back without
the href, left the row diverged for as long as no delta named the resource, which on Radicale was
measured as three later runs before `ec2f2ac` and is unbounded in principle. Read from the rows, the
fetch is made again on the next run and the divergence is bounded by one run. Two records of the same
fact is what let the carried set go stale, so the carried one is gone rather than merged.

That leaves one answer the carried set never had to handle: a resource the server deleted after the
revert. Counting it missing would never converge, because nothing will name it again, so an explicit
`404` for an href — a status the multistatus gives by name, the same one `sync-collection` reports a
removal with — deletes the resource's clean rows. Dirty and deleted rows stay: an edit made since is
newer than the answer. An href the multiget simply did not answer is still ambiguous and is still
only counted, and asked for again next run.

The tombstone case was looked for on a device and does not exist. `CalendarProvider2` hard-deletes an
event row that carries no `_SYNC_ID` instead of marking it `DELETED=1`; an exception never carries one,
since the provider excludes `_SYNC_ID` from what it clones into an exception and this app writes it on
the master alone; and soft-deleting a master hard-deletes the exceptions under it. Deleting an override
row through the provider removed it outright, leaving no row for step U to find, and the stock app's
"delete this occurrence" is an exception inserted with `STATUS_CANCELED` — an ordinary dirty row, which
uploaded and settled. So a resource's only tombstone is its master's, and the `DELETED=0` guards on the
override writes exclude nothing that can arise.

**Amended:** "no tombstone" cuts both ways. An override row an editor deletes outright leaves nothing
behind — no tombstone, nothing dirty, and a master the provider never touches — so the queue had no
signal at all that an occurrence's override had gone, and the serializer, which walks the rows and
only ever *claims* components of the stored text, kept sending the component back. The stock app does
not reach that state: `DeleteEventHelper.deleteExceptionEvent` **updates** the exception to
`STATUS_CANCELED` rather than deleting it, which is an ordinary dirty row and already uploaded as a
cancelled component. A row delete is what other editors do — Fossify Calendar deletes an event by id,
exceptions included — and `content delete` reaches it as well. So the master carries
`Events.SYNC_DATA4`: how many override components its stored text gave rows to, written wherever that
text is written (the fetch, and the answer to an accepted upload) through one helper, so the two
cannot drift. Fewer live override rows than that count queues the resource, and the serializer drops
every component no row claims. No `EXDATE` is synthesised for the dropped one: an exception row is
what removed the master's own occurrence from the expansion, so deleting it brings that occurrence
back locally, and the server should hold what the phone shows. The alternatives were to diff the
stored text against the rows on every run — dragging every resource's bytes through a CursorWindow,
which `localItems` exists to avoid — or to do nothing and leave a divergence no listing ever corrects,
since the master's ETag does not move.

## Consequences

- A conflict loses the phone's edit deliberately, and says so: the log names the resource, and the Collection stays `OK`, because nothing is left to retry.
- Nothing is written to a server until a Collection is turned on. `writable` defaults off, so an upgrade talks to the server exactly as it did before, and the switch is the whole of the user's consent.
- A body the server refuses leaves its row `DIRTY`, so the Collection reports Partial on every run until the body is fixed or the Collection is made read-only. That is visible rather than silent, and it is the price of never discarding an edit.
- An upload-only run uploads and does not list, so it discovers nothing beyond what it sent — and what a revert left, which it fetches by href before it stops. That is how the server's version lands within the interval rather than at the next periodic sync, and it costs one multiget instead of a listing.
- The account declares write capability unconditionally, so an Account whose Collections are all read-only still claims it can write. The engine, not the declaration, is what refuses.
- Contacts cannot be told per address book — `<EditSchema>` is per account type — so a read-only address book reverts what it holds and counts it refused. Calendars are told, through `CALENDAR_ACCESS_LEVEL`, which is why the stock app greys out a calendar that may not be edited.
- A locally created contact with no writable address book has nowhere to go: it stays `DIRTY` and is never deleted, and the log names the reason.

- A row edited while its upload was in flight is uploaded twice: once with the older body, once with
  the newer. The second `PUT` carries the ETag the first earned, so nothing is clobbered and nothing
  is lost. Observed on a device: the server took `EventC` while the phone held `EventD` and stayed
  dirty, then took `EventD` eleven seconds later.
- The contacts photo baseline lives on `RawContacts.SYNC4`, not on the photo row, because it has to be
  written inside the version-guarded batch and a raw-contact write moves no version. A contact synced
  before this change carries no baseline there, and an absent baseline means "the photo changed", so
  the first edit to each such contact — even one that never touched the photo — would have replaced
  the server's `PHOTO` with a re-encode. A clean contact without a baseline is therefore given one,
  the digest of the photo it already holds, before anything it holds can be uploaded: a clean
  contact's rows are the server's, so that states a fact rather than assuming one. A dirty contact is
  skipped, because its photo may be the edit waiting to be sent, and claiming it matches the server
  would drop that edit silently. Verified by upgrading a device from the previous release: the first
  sync adopted the baseline, and a name-only edit afterwards left the server's `PHOTO` byte-identical.
- `CAN_PARTIALLY_UPDATE` stays 0 on every calendar this app creates. Turning it on would make the
  provider keep `LAST_SYNCED` copies that sync-adapter queries see, which `rowsClaiming` does not
  expect.
