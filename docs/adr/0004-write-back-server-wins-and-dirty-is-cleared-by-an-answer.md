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

## Consequences

- A conflict loses the phone's edit deliberately, and says so: the log names the resource, and the Collection stays `OK`, because nothing is left to retry.
- Nothing is written to a server until a Collection is turned on. `writable` defaults off, so an upgrade talks to the server exactly as it did before, and the switch is the whole of the user's consent.
- A body the server refuses leaves its row `DIRTY`, so the Collection reports Partial on every run until the body is fixed or the Collection is made read-only. That is visible rather than silent, and it is the price of never discarding an edit.
- An upload-only run uploads and does not list, so it discovers nothing. A run that reverted a conflict is the exception and continues through the listing, so the server's version lands within the interval instead of at the next periodic sync.
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
