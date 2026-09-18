# dav-provider-android

An Android CalDAV/CardDAV sync provider that can attach **arbitrary HTTP headers**
and/or a **client certificate** per account.

Status: **idea only.** Nothing is built and nothing is decided beyond the notes below.

## Why

Mainstream Android DAV clients cannot send arbitrary request headers. That makes a
DAV server sitting behind an identity-aware proxy (for example Cloudflare Access with
service tokens, which expects `CF-Access-Client-Id` / `CF-Access-Client-Secret`)
unreachable from Android without either putting the server on the open internet or
running a system-wide VPN. Client-certificate (mTLS) setups are possible today but
fail opaquely, with no way to tell "no certificate was offered" from "the certificate
was rejected".

This project aims at that narrow gap only. It is not an attempt to replace any
existing DAV client generally.

## Shape

A sync adapter that writes into the platform providers, so existing calendar and
contacts apps see the data with no changes:

- `ContactsContract` — contacts, one account per address book
- `CalendarContract` — events

### Must have

1. Per-account custom HTTP headers, sent on every request (`PROPFIND`, `REPORT`,
   `PUT`, `DELETE`, `OPTIONS`)
2. Client certificate selected from the Android KeyChain, usable with or without
   password auth
3. Basic auth alongside either of the above
4. Manual configuration — explicit base URL, no email-based discovery guessing;
   optional `.well-known` / `current-user-principal` discovery from a given root
5. Honest error surfacing: the actual HTTP status, the first line of the response
   body, and whether a client certificate was offered

### Non-goals

- No task sync (no VTODO)
- No web UI, no server component
- No CalDAV scheduling, free-busy, sharing or ACL management

## Approach

A standalone app, not a fork or patch of an existing client — a fork means tracking
upstream forever. The protocol layer is intended to come from
[`dav4jvm`](https://github.com/bitfireAT/dav4jvm), with a hand-rolled OkHttp client as
the fallback if it proves awkward to consume standalone.

Open question to settle before any app work: whether an identity-aware proxy actually
honours service-token headers on `PROPFIND` and `REPORT`, not just on the methods it
recognises. If it does not, the headers feature is pointless and only the clearer
mTLS handling justifies the project.

## Repo conventions

Agent/tooling conventions live in [`AGENTS.md`](./AGENTS.md) and `docs/agents/`.
