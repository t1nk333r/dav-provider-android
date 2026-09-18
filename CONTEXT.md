# DAV Provider

An Android sync provider that syncs contacts and calendars from a CalDAV/CardDAV server into the platform providers, where the server is reached through an identity-aware proxy that requires custom HTTP headers or a client certificate.

## Language

**Account**:
One configured server, holding its base URL, credentials and collection selections. Exactly one `AccountManager` account, so the platform's Account and ours are the same object.
_Avoid_: Connection, server entry, profile

**Collection**:
One address book or one calendar on the server. Never the account that contains it.
_Avoid_: Folder, calendar (when speaking generally), address book (when speaking generally)

**Credentials**:
The collective term for everything that authenticates an Account: its headers, its client certificate and its password. Always plural when collective; name the individual mechanism otherwise.
_Avoid_: Auth profile, auth mode, secrets

**Header**:
One name/value pair sent on every request for an Account. The mechanism that makes identity-aware-proxy service tokens usable.
_Avoid_: Custom header, extra header

**Client certificate**:
A certificate and private key held by the Android KeyChain and selected for an Account by **alias**. The app stores the alias; it never holds the key.
_Avoid_: mTLS cert, cert, certificate (unqualified, when a server certificate could be meant)

**Alias**:
The KeyChain's identifier for a client certificate. The only part of a certificate an Account persists.

**Discovery**:
Resolving a user-supplied base URL to a set of Collections. Optional; an Account may name a Collection URL directly.
_Avoid_: Auto-configuration, autodiscovery

**Origin**:
The DAV server itself, as distinct from the identity-aware proxy in front of it. Load-bearing when describing failures: a `DAV:` XML error body means the Origin answered, whatever the status code.
_Avoid_: Backend, upstream, server (unqualified, in error handling)
