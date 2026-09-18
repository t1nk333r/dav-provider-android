# Research: is `dav4jvm` usable standalone for custom headers and KeyChain certs?

Ticket: #5 (parent map #1). Source of truth: the library's own code at `https://github.com/bitfireAT/dav4jvm`,
read at tag **4.1.0** and at `main` (commit `0980f6b`, 2026-09-14). Two claims below were additionally
established by executing a throwaway Android build (see "How the runtime claims were checked").

## Verdict

**The `README.md` decision survives, with two corrections to its assumptions.**

1. dav4jvm does not build or own an HTTP client. Every request is issued through a caller-supplied Ktor
   `HttpClient` passed to the constructor (`DavResource(httpClient, location, logger)`), so both hooks the project
   exists for are reachable without patching or forking: arbitrary headers via a Ktor `defaultRequest` plugin or an
   OkHttp `Interceptor`, and a client certificate via an `SSLSocketFactory` built from a KeyChain-backed
   `X509KeyManager`, injected with Ktor's `engine { preconfigured = <OkHttpClient> }`.
2. But the library is **protocol primitives only**. It carries no sync algorithm — no ETag/CTag comparison, no
   sync-token storage, no multiget batching policy, no sync loop. That logic must be written from scratch
   regardless of whether dav4jvm or a hand-rolled OkHttp client is used; dav4jvm does not reduce that cost, and the
   hand-rolled fallback would additionally re-implement the protocol layer that dav4jvm already provides.

Secondary corrections that matter for the spec: since **3.0.0 the OkHttp-based API no longer exists** (Ktor is the
interface), and the app must compile with **Kotlin ≥ 2.4** — AGP 9's built-in Kotlin compiler (2.2.0) rejects
dav4jvm 4.1.0's metadata outright.

## How the runtime claims were checked

Two checks were executed rather than inferred, in a scratch project outside the repository:

* Build A: an Android application module (`com.android.application` 9.4.0, `compileSdk 36`, JDK 21,
  `sourceCompatibility`/`targetCompatibility` = `VERSION_21`), depending on
  `com.github.bitfireAT:dav4jvm:4.1.0` resolved from JitPack plus `io.ktor:ktor-client-okhttp:3.5.2`.
  `:app:assembleDebug` → **BUILD SUCCESSFUL**, all dex tasks ran, and `classes.dex` in the produced APK contains
  `Lat/bitfire/dav4jvm/ktor/DavCollection;` (plus its synthetic lambdas), i.e. D8 accepted the Java 21 class files.
  The same build also succeeds with `targetCompatibility = VERSION_17`.
* Build B: the same module with a Kotlin source file that reproduces the whole chain end to end — build an
  `OkHttpClient` with an `Interceptor` adding two custom headers, add an
  `sslSocketFactory(SSLSocketFactory, X509TrustManager)` where the factory is constructed from a caller-supplied
  `X509KeyManager`, hand it to Ktor via `HttpClient(OkHttp) { engine { preconfigured = ok } }`, then construct
  `DavCollection(httpClient, Url(...))` and call `propfind(depth = 1, ...)` and
  `reportChanges(syncToken = null, infiniteDepth = false, limit = null, ...)`, collecting both flows.
  Result: compiles and dexes (**BUILD SUCCESSFUL**) once the Kotlin compiler is raised to 2.4.10 (see §4.3);
  with AGP 9's default built-in Kotlin it fails with the metadata error quoted in §4.3.

No live DAV request was made to any server in this ticket; the proxy/headers question is a separate ticket.

---

## 1. Licence

* **MPL-2.0.** Stated in `LICENSE` (`Mozilla Public License Version 2.0`,
  <https://github.com/bitfireAT/dav4jvm/blob/main/LICENSE>), in every source file's SPDX header
  (`SPDX-License-Identifier: MPL-2.0`), and in the published POM (`<name>Mozilla Public License 2.0</name>`,
  <https://jitpack.io/com/github/bitfireAT/dav4jvm/4.1.0/dav4jvm-4.1.0.pom>). GitHub's API reports
  `license: MPL-2.0`, `archived: false`.
* **Private, unpublished app: no obligation attaches.** MPL obligations are triggered by *distribution*, defined by
  Mozilla as "delivery of a copy of the software to another person or entity"; internal use or use inside an
  organisation counts as private (FAQ Q6, Q17 — <https://www.mozilla.org/en-US/MPL/2.0/FAQ/>). An app that is never
  handed to anyone else can use dav4jvm with no source-release duty.
* **If it is ever distributed** (APK to a third party, store, F-Droid, or a copy to a tester outside the
  organisation), §3.2(a) requires that the *covered* code be made available in source form and that recipients be
  told where to get it; §3.3 permits the surrounding app to be distributed under any terms, including proprietary
  ones. Mozilla's FAQ Q8 states this directly: "You may distribute any executables you create under a license of
  your choosing, as long as that license does not interfere with the recipients' rights to the source under the
  terms of the MPL", with the duty being to "inform the recipients where they can get the source for the MPLed
  code". The file-level copyleft bites only on **Modifications** (§1.10) to covered files — which the no-fork
  decision avoids entirely, so the practical duty is "point at the upstream repository".
* **No secondary-licence trap:** nothing in the repo declares an incompatible or secondary licence (no
  "Incompatible With Secondary Licenses" notice in the file headers), and the dependency tree below is
  Apache-2.0/EPL-style plus Guava (Apache-2.0); no copyleft is pulled in through dav4jvm.
* Consequence for the effort: distributing the app is compatible with keeping the app source closed, provided the
  app does not modify dav4jvm files in place. Vendoring dav4jvm as source and editing it *would* create
  source-availability duties for the edited files — another argument for the no-fork decision.

## 2. How the HTTP client is constructed (decision-critical)

### 2.1 The library takes the caller's client — there is no internal client

The constructors take an `io.ktor.client.HttpClient` as their first parameter, and nothing in the library
instantiates, copies or re-configures one:

```kotlin
open class DavResource(
    protected val httpClient: HttpClient,
    location: Url,
    protected val logger: Logger = Logger.getLogger(javaClass.name)
)
```
— <https://github.com/bitfireAT/dav4jvm/blob/main/src/main/kotlin/at/bitfire/dav4jvm/ktor/DavResource.kt#L83-L87>

Subclasses pass it straight through:
`DavCollection` (`.../ktor/DavCollection.kt#L32-L36`, `open class DavCollection @JvmOverloads constructor(httpClient, location, logger) : DavResource(httpClient, location, logger)`),
`DavCalendar` (`.../ktor/DavCalendar.kt#L38-L42`), `DavAddressBook` (`.../ktor/DavAddressBook.kt#L31-L35`).

Every request is issued on that same instance — `httpClient.prepareOptions/prepareRequest/prepareHead/prepareGet/
preparePost/preparePut/prepareDelete(location) { ... }` at
[DavResource.kt lines 188, 219, 249, 285, 316, 344, 376, 405, 437, 464, 507, 546, 572](https://github.com/bitfireAT/dav4jvm/blob/main/src/main/kotlin/at/bitfire/dav4jvm/ktor/DavResource.kt)
and `DavCollection.kt#L98`, `DavCalendar.kt#L110`, `DavCalendar.kt#L172`, `DavAddressBook.kt#L67`, `DavAddressBook.kt#L129`.
There is no `HttpClient(...)` construction and no `httpClient.config { ... }` derivation anywhere in
`src/main/kotlin` (verified by exhaustive grep for `httpClient` in the main source set).

The only caller-side obligations the library imposes are documented on `DavResource`: "ATTENTION: dav4jvm handles
redirects itself. Make sure followRedirects is set to FALSE for the httpClient" (`DavResource.kt#L73-L80`), and the
same instruction in the README usage section
(<https://github.com/bitfireAT/dav4jvm/blob/main/README.md>). Redirects are then followed manually, up to
`MAX_REDIRECTS = 5`, with HTTPS→HTTP redirects rejected (`DavResource.kt#L622-L670`).

### 2.2 Consequences for "arbitrary headers on every request"

Because the client belongs to the app, both standard injection points are available and apply to *every* request the
library makes, including `PROPFIND` and `REPORT`:

* Ktor `DefaultRequest` plugin — "allows you to configure default parameters for all requests … To add a specific
  header to each request, use the `header` function" (<https://ktor.io/docs/client-default-request.html>).
* An OkHttp `Interceptor` on the engine's client (below), which is the more robust option because it also covers
  anything the Ktor plugin pipeline does not.

This matters because dav4jvm's own per-request `additionalHeaders: Headers?` parameter is **not** available on the
methods the project needs. It exists only on `mkCol` (`DavResource.kt#L278`), `get` (`#L338`), `getRange` (`#L374`),
`post` (`#L399`), `put` (`#L431`) and `delete` (`#L462`); it is absent from `options` (`#L175`), `head` (`#L314`),
`move` (`#L217`), `copy` (`#L247`), `propfind` (`#L489`), `proppatch` (`#L536`), `search` (`#L571`),
`reportChanges` (`DavCollection.kt#L55`), `calendarQuery` (`DavCalendar.kt#L58`), and both `multiget`s
(`DavCalendar.kt#L137`, `DavAddressBook.kt#L94`). Client-level injection is therefore the only approach that
satisfies must-have #1 ("per-account custom headers, sent on every request").

### 2.3 OkHttp: still reachable, but no longer the library's interface

* Since **3.0.0** dav4jvm uses Ktor and the old API is gone: "This version migrates away from OkHttp to Ktor as the
  underlying HTTP client… The old OkHttp-based API is no longer available, and you will need to migrate your code to
  the new Ktor-based API." (<https://github.com/bitfireAT/dav4jvm/blob/main/CHANGELOG.md>, 3.0.0).
  For contrast, the last OkHttp-era release did take the client directly:
  `open class DavResource(val httpClient: OkHttpClient, location: HttpUrl, val log: Logger)`
  (<https://github.com/bitfireAT/dav4jvm/blob/2.2.1/src/main/kotlin/at/bitfire/dav4jvm/DavResource.kt#L43-L47>).
  Any plan or note assuming the 2.x signature is stale.
* OkHttp is still usable *underneath* Ktor, and the engine exposes the client directly:

  ```kotlin
  // io.ktor.client.engine.okhttp.OkHttpConfig, Ktor 3.5.2
  public var preconfigured: OkHttpClient? = null         // "Allows you to specify a preconfigured OkHttpClient instance."
  public fun config(block: OkHttpClient.Builder.() -> Unit)
  public fun addInterceptor(interceptor: Interceptor)
  ```
  — <https://github.com/ktorio/ktor/blob/3.5.2/ktor-client/ktor-client-okhttp/jvm/src/io/ktor/client/engine/okhttp/OkHttpConfig.kt#L15-L96>

  The engine then builds the effective client as
  `val builder = (config.preconfigured ?: okHttpClientPrototype).newBuilder()` followed by `builder.apply(config.config)`
  — so a caller-supplied `OkHttpClient` is used as the base and the engine's own config block is layered on top
  (<https://github.com/ktorio/ktor/blob/3.5.2/ktor-client-okhttp/jvm/src/io/ktor/client/engine/okhttp/OkHttpEngine.kt#L149-L162>).
  Interceptors, `sslSocketFactory`, `hostnameVerifier` and `proxy` set on that client survive.
* Version note: resolving `io.ktor:ktor-client-okhttp:3.5.2` for Android pulls `com.squareup.okhttp3:okhttp:5.3.2`
  (+ `okhttp-android:5.3.2`). The app does not have to bundle OkHttp itself; it already depends on Kotlin 2.4.x and
  AGP 9-line tooling where this is consistent.

### 2.4 Client certificate from the Android KeyChain

* Android's `KeyChain` is designed for exactly this shape: "Receive a callback from an `X509KeyManager` that a
  private key is requested… Call `choosePrivateKeyAlias` … Call `getPrivateKey(Context, String)` and
  `getCertificateChain(Context, String)` to retrieve the credentials to return to the corresponding `X509KeyManager`
  callbacks" (<https://developer.android.com/reference/android/security/KeyChain>). Such a key manager plugs into an
  `SSLContext`, whose `SSLSocketFactory` goes onto the OkHttp client as in §2.3.
* Error-surfacing caveat that is directly relevant to must-have #5: `getPrivateKey` "Returns the `PrivateKey` for the
  requested alias, or null if the alias does not exist or the caller has no permission to access it"; on Android
  versions prior to Q the same condition throws `KeyChainException` whose cause is an `IllegalStateException` when
  the caller lacks a grant. It also throws `IllegalStateException` if called from the main thread
  (same page). So "no certificate was offered" is distinguishable from "certificate rejected" only by handling
  both the null and the pre-Q exception path — a spec decision, not a library limitation.
* Reference implementation exists in a shipping app of the same authors (not a dependency): DAVx⁵ implements
  `ClientCertKeyManager : X509ExtendedKeyManager` whose `getCertificateChain`/`getPrivateKey` call
  `KeyChain.getCertificateChain`/`KeyChain.getPrivateKey`
  (<https://github.com/bitfireAT/davx5-ose/blob/main/core/src/main/kotlin/at/bitfire/davdroid/network/ClientCertKeyManager.kt>),
  builds the Ktor client itself with the OkHttp engine and calls
  `okBuilder.sslSocketFactory(securityContext.sslSocketFactory, securityContext.trustManager)`
  (<https://github.com/bitfireAT/davx5-ose/blob/main/core/src/main/kotlin/at/bitfire/davdroid/network/HttpClientBuilder.kt>,
  `buildConnectionSecurity`), and hands that client to dav4jvm. That is the pattern this project needs; it is
  evidence the pattern works, not something dav4jvm provides.
* **Not verified here:** the actual KeyChain consent flow and certificate selection on a device (`choosePrivateKeyAlias`
  prompt, grant persistence, per-collection certificate selection). That needs a device test; this ticket only
  establishes that the library does not obstruct it.
* Basic auth alongside mTLS is supported by the library's own Ktor `AuthProvider`s — `PreemptiveBasicDigestAuthProvider`
  (tries Basic preemptively, switches to Digest) and `DomainAuthProvider` (restricts auth to one domain)
  (<https://github.com/bitfireAT/dav4jvm/tree/main/src/main/kotlin/at/bitfire/dav4jvm/ktor>).

## 3. Scope of the library: primitives, not a sync engine (decision-critical)

The whole main source set is **94 files / 6,039 lines**: 60 property classes (`property/webdav` 19, `caldav` 16,
`push` 18, `carddav` 6, `common` 1), 26 files under `ktor/` (4 resource classes, parsers, Ktor helpers, exceptions)
and 8 root utilities. The package map is documented by the project itself in
<https://github.com/bitfireAT/dav4jvm/blob/main/AGENTS.md>.

**Present — protocol primitives:**

| Capability | Where |
| --- | --- |
| `PROPFIND`, `PROPPATCH`, `SEARCH`, OPTIONS/MOVE/COPY/MKCOL/MKCALENDAR/HEAD/GET/PUT/POST/DELETE | `DavResource.kt` (`propfind` #L489, `proppatch` #L536, `search` #L571, `options` #L175 …) |
| `sync-collection` REPORT request construction (`sync-token`, `sync-level`, `limit`/`nresults`, `prop`) | `DavCollection.reportChanges`, <https://github.com/bitfireAT/dav4jvm/blob/main/src/main/kotlin/at/bitfire/dav4jvm/ktor/DavCollection.kt#L55-L104> |
| `calendar-query`, `calendar-multiget`, `addressbook-query`, `addressbook-multiget` REPORT construction | `DavCalendar.kt#L58`, `DavCalendar.kt#L137`, `DavAddressBook.kt#L46`, `DavAddressBook.kt#L94` |
| Multi-Status parsing as a cold `Flow` (`Response`, `PropStat`, `ExtraProperty` incl. `sync-token`) | `MultiStatusParser.kt`, `ResponseParser.kt`, `PropStatParser.kt`, `MultiStatusItem.kt` |
| Property model + registry for ~60 WebDAV/CalDAV/CardDAV/WebDAV-Push properties | `property/**`, `PropertyRegistry.kt` |
| Redirect handling (own, max 5, HTTPS→HTTP blocked) | `DavResource.kt#L622-L670` |
| Error surfacing: typed exceptions carrying status code, request excerpt, response excerpt (≤20 KiB) and parsed `DAV:error` elements | `ktor/exception/DavException.kt`, `HttpException.kt`, `HttpResponseInfo.kt`, `Error.kt` |

**Absent — everything that makes a sync client:**

* No ETag or CTag comparison anywhere. `GetETag`, `GetCTag`, `SyncToken`, `ScheduleTag` exist as *parsers* only
  (e.g. `property/caldav/GetCTag.kt`, `property/webdav/SyncToken.kt`); the only non-request-building occurrence of
  `sync-token` is emitting it as `MultiStatusItem.ExtraProperty` while parsing
  (`MultiStatusParser.kt#L42-L43`). A grep across the main source set for
  `etag|ctag|synctoken` outside XML building and the property registry returns nothing else.
* No storage of sync tokens / CTags / member lists; no database, no local store, no state machine. Grepping the
  main source set for `sqlite|database|room|sharedpref|syncer|sync(` returns a single unrelated code comment
  (`DavResource.kt#L542`, "room for further improvement: handle not only 207 Multi-Status").
* No batching policy: `multiget(urls: List<Url>, ...)` builds one request from whatever list the caller passes; chunk
  size, retry-on-failure splitting and progress accounting are the caller's.
* No discovery flow: `current-user-principal` exists as a property + property name
  (`property/webdav/CurrentUserPrincipal.kt`, `WebDAV.kt#L80`) and `UrlUtils` has URL helpers, but there is no
  `.well-known` → principal → home-set → collection walk (grep for `well-known|discover` finds only an unrelated
  `SEARCH` doc comment).
* No sync loop, no conflict handling, no Android provider integration.

Corroboration that the sync algorithm lives elsewhere by design: DAVx⁵ — the library's origin — implements it in its
own repository, on top of dav4jvm. Its app-side sync/resource layer is **74 files / ≈375 KB**:
`core/src/main/kotlin/at/bitfire/davdroid/sync/**` (43 files, ≈231 KB; e.g. `Syncer.kt`, `SyncManager.kt`,
`ContactsSyncManager.kt`, `CalendarSyncManager.kt`) and `core/src/main/kotlin/at/bitfire/davdroid/resource/**`
(31 files, ≈144 KB; `local/*` = ContactsContract/CalendarContract side, `remote/*` = a CalDAV/CardDAV layer that
imports `at.bitfire.dav4jvm.ktor.DavResource`/`MultiStatusItem` and adds collection-level logic, e.g.
<https://github.com/bitfireAT/davx5-ose/blob/main/core/src/main/kotlin/at/bitfire/davdroid/resource/remote/BaseWebDavCollection.kt>).

**Cost consequence.** The sync algorithm is the same work under either option. If the hand-rolled OkHttp fallback were
taken, the *additional* work is the protocol layer dav4jvm already provides: XML request builders for PROPFIND /
PROPPATCH / SEARCH / sync-collection / calendar-query / calendar-multiget / addressbook(-multiget), a Multi-Status
parser incl. `propstat` handling, a property model of ~60 properties, redirect handling, error-body capture, and
basic/digest auth providers — i.e. re-implementing the 26 `ktor/` files plus 60 property files (5,387 of the
library's 6,039 lines), and then maintaining it. That is the real cost of the fallback, and it is what justifies the
decision to depend on the library rather than hand-roll.

## 4. Android coupling and consumption

### 4.1 Pure JVM, no Android APIs

* Built with the plain Kotlin JVM plugin (`alias(libs.plugins.kotlin.jvm)` in
  <https://github.com/bitfireAT/dav4jvm/blob/main/build.gradle.kts>); no Android plugin, no `android { }` block, no
  AAR packaging. CI runs on `temurin 21` (<https://github.com/bitfireAT/dav4jvm/blob/main/.github/workflows/test.yml>).
* Import census over `src/main/kotlin`: no `android.*` / `androidx.*` import exists at all. Imports are
  `io.ktor` (118), `org.xmlpull` (74), `java.util` (20), `kotlinx.coroutines` (11), `java.io` (10), `java.time` (9),
  `kotlinx.io` (3), `java.net` (2), one Guava import. The Android-specific pieces of DAVx⁵ (KeyChain, providers,
  WorkManager, Hilt) are all outside the library, which is what makes it reusable standalone.
* It works with no DAVx⁵ abstractions: build B (§"How the runtime claims were checked") used `DavCollection` with a
  Ktor client and nothing else. DAVx⁵'s own layer (`network/HttpClientBuilder`, `resource/remote/*`, `sync/*`) sits
  *above* dav4jvm and is not required.

### 4.2 Bytecode level: Java 21 (verified)

* Gradle module metadata declares `"org.gradle.jvm.version" : "21"` for both variants
  (<https://jitpack.io/com/github/bitfireAT/dav4jvm/4.1.0/dav4jvm-4.1.0.module>).
* Measured on the downloaded artifact: `dav4jvm-4.1.0.jar` (sha256 `35fd0d49…120d`, matching the published
  `.module`) has class-file major version **65** (= Java 21).
* Practical effect, verified empirically: AGP 9.4.0 does **not** request `org.gradle.jvm.version` (the
  `dependencyInsight` attribute table shows it under "Provided", with an empty "Requested" column), so variant
  resolution succeeds regardless of the module's `targetCompatibility` (the same app built with 21 and with 17).
  D8 successfully dexed the Java 21 classes. **Inference:** this is AGP-9 behaviour with this artifact; a project on
  a much older AGP could still hit an "unsupported class file version 65" from its bundled R8 — not tested here.
* `java.time` is used in six files, including `DavCalendar` (time-range formatting), `HttpUtils`, `XmlReader`,
  `property/webdav/GetLastModified.kt` and `ktor/exception/ServiceUnavailableException.kt` — it needs
  `minSdk >= 26` or core-library desugaring
  (<https://developer.android.com/studio/write/java8-support>). [INFERENCE] For an Android app with
  `minSdk < 26` this is a build-configuration requirement, not a library defect.

### 4.3 Kotlin metadata: the app needs Kotlin ≥ 2.4 (verified; easy to miss)

The library is compiled with Kotlin 2.4.10 and its classes carry Kotlin metadata version **2.4.0** (`mv = {2, 4, 0}`
on `DavCollection.class`; POM requests `kotlin-stdlib:2.4.10`). AGP 9.4.0's built-in Kotlin compiles with
**2.2.0**, which cannot read it. Observed verbatim in build B with built-in Kotlin:

```
Class 'at.bitfire.dav4jvm.ktor.DavCollection' was compiled with an incompatible version of Kotlin.
The actual metadata version is 2.4.0, but the compiler version 2.2.0 can read versions up to 2.3.0.
```

Two workarounds exist; the first is the documented forward path and is the one verified here:

* Put a newer KGP on the buildscript classpath (root build file:
  `buildscript { dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10") } }`), keeping
  `com.android.application` as the module's only plugin — build B then succeeds. AGP 9's own release notes describe
  this classpath mechanism for supplying a newer KGP
  (<https://developer.android.com/build/releases/agp-9-0-0-release-notes>), and the migration doc documents the
  built-in-Kotlin defaults and the error text above
  (<https://developer.android.com/build/migrate-to-built-in-kotlin>).
* Or opt out of built-in Kotlin (`android.builtInKotlin=false`) and apply `org.jetbrains.kotlin.android` 2.4.x — not
  the recommended route (the opt-out disappears in AGP 10).

A plain JVM/Gradle module has no such problem: pinning the Kotlin compiler to ≥ 2.4 is enough.

### 4.4 Other consumption details worth speccing

* **SLF4J appears on the classpath** because Ktor logs through `slf4j-api` (resolved 2.0.18 in the Android build).
  dav4jvm's own code does not import it. An Android app should add a binding (`slf4j-android`/`slf4j-nop`) or expect
  the usual no-binding warning on the first request (<https://www.slf4j.org/android/>).
* **Guava is a real runtime dependency**, though it is used in exactly one place: `UrlUtils.hostToDomain` calls
  `com.google.common.net.InetAddresses.isInetAddress`
  (<https://github.com/bitfireAT/dav4jvm/blob/main/src/main/kotlin/at/bitfire/dav4jvm/ktor/UrlUtils.kt#L13-L36>),
  which `DomainAuthProvider` relies on for domain-scoped auth (`DomainAuthProvider.kt#L45`). Excluding Guava to save
  APK size would break that path — do not exclude it casually.
* **XPP3 ships as a compile dependency** (`api(libs.xpp3)`, and `org.ogce:xpp3:1.1.6` with `compile` scope in the
  POM) even though the README says Android already provides a `XmlPullParser` ("On Android, the system already comes
  with XPP and you don't need to include one"). The Android build resolved and dexed it without duplicate-class
  errors (platform `org.xmlpull.v1` classes win at runtime), so leaving it in is safe; excluding it is optional
  tidiness, not a requirement.

## 5. Distribution, current version, cadence, maintenance

* **Not on Maven Central.** `https://search.maven.org/solrsearch/select?q=dav4jvm` returns `numFound: 0`. The
  distribution channel is **JitPack**: `maven("https://jitpack.io")` +
  `implementation("com.github.bitfireAT:dav4jvm:<version>")`, per the README install section and `jitpack.yml`
  (JDK 21). JitPack's build list marks `3.0.0`, `3.0.1`, `3.0.2`, `4.0.0`, `4.0.1`, `4.1.0` and `HEAD` as built
  (`ok`) — <https://jitpack.io/api/builds/com.github.bitfireAT/dav4jvm>. The 4.1.0 POM/module/jar are served from
  JitPack and resolve in a Gradle build (verified). Consequences to note in the spec: JitPack builds on demand, so
  CI needs network access to `jitpack.io` and should pin the exact version (and ideally the resolved artifact hash);
  a first-time version may be built on first request.
* **Vendoring as source is also proven** in the ecosystem: DAVx⁵'s `settings.gradle.kts` carries a commented-out
  composite-build block substituting the module with a local checkout —
  `includeBuild("../dav4jvm") { dependencySubstitution { substitute(module("com.github.bitfireAT:dav4jvm")).using(project(":")) } }`
  (<https://github.com/bitfireAT/davx5-ose/blob/main/settings.gradle.kts>). This is the escape hatch if JitPack ever
  proves unsuitable (e.g. for reproducible/offline builds), and it is a *Gradle* substitution, not a fork.
* **Current version: 4.1.0, released 2026-09-01.** `main` is one commit past that tag (`0980f6b`, 2026-09-14).
  Version list from tags: 1.0, 1.0.1, 2.0, 2.1, 2.1.1–2.1.4, 2.2, 2.2.1, 3.0.0, 3.0.1, 3.0.2, 4.0.0, 4.0.1, 4.1.0.
  GitHub *Releases* exist only for 3.0.0, 4.0.0 and 4.1.0 — the intermediate tags are real but unreleased in
  GitHub's sense, so the tag list, not the release list, is the version source of truth.
* **Cadence and maintenance: active.** Release dates: 2.2.1 2023-01-09 → 3.0.0 2026-07-07 → 3.0.1 2026-07-09 →
  3.0.2 2026-07-10 → 4.0.0 2026-07-27 → 4.0.1 2026-08-01 → 4.1.0 2026-09-01. Commits on `main` by window:
  2023-01-10→2024-01-01: 13; 2024: 22; 2025: 59; 2026-01-01→2026-07-07: 44; 2026-07-07→now: 28. Repository state
  (GitHub API): not archived, 104 stars, 4 open issues, `pushed_at` 2026-09-17.
* **API-stability risk to record.** 3.0.0 announced "the library is now in a stable state, and we will follow
  semantic versioning from now on" (CHANGELOG), yet two further majors landed within three weeks: `3.0.0` replaced
  OkHttp with Ktor, and `4.0.0` replaced the synchronous callback API with `suspend` functions and `Flow`s
  ("API change: the old synchronous, non-suspending callback pattern (like `MultiResponseCallback`) has been
  replaced by `suspend` functions and `Flow`s"). Pin the exact version, avoid `main-SNAPSHOT`, and expect the
  consumed API surface (`propfind`/`reportChanges`/`multiget` flows, exception types) to be the parts that move.

## 6. vCard / iCal parsing

* **Not in the library, and no third-party parser is pulled in.** The payload properties carry opaque text:
  `data class AddressData(val card: String?)` (<https://github.com/bitfireAT/dav4jvm/blob/main/src/main/kotlin/at/bitfire/dav4jvm/property/carddav/AddressData.kt#L16-L21>)
  and `data class CalendarData(val iCalendar: String?)` (`property/caldav/CalendarData.kt#L16-L21`), with
  `content-type`/`version` attributes for the multiget request only — the parsers read the element text and stop.
  Neither `ez-vcard` nor `biweekly` appears anywhere in the POM, module metadata or the resolved classpath. Parsing
  vCard/iCal into contacts/events is a decision this project has to make separately (it needs a JVM parser, since
  neither vendor library is on the tree).
* **What the dependency tree actually pulls in** (from the published POM/module for 4.1.0, and confirmed by a real
  Gradle resolution of the Android runtime classpath, 74 modules total, versions elided):
  * `api` (compile): `org.jetbrains.kotlinx:kotlinx-coroutines-core`, `io.ktor:ktor-client-core`,
    `com.github.spotbugs:spotbugs-annotations` (annotations only), `org.ogce:xpp3:1.1.6`,
    `org.jetbrains.kotlin:kotlin-stdlib`
  * `runtime`: `com.google.guava:guava` (+ `failureaccess`, `listenablefuture`, `jspecify`, `error_prone_annotations`,
    `j2objc-annotations`), `io.ktor:ktor-client-auth`, `io.ktor:ktor-client-encoding`
  * transitively via Ktor: `slf4j-api`, `ktor-http/io/utils/network/events/websocket-serialization`,
    `kotlinx-io-core`, `kotlinx-serialization-core`
  * plus, only if the OkHttp engine is chosen: `com.squareup.okhttp3:okhttp:5.3.2` (`okhttp-android` on Android)
* No text-parsing, Android, or vendor library is hidden in the tree; the APK size impact is dominated by Guava and
  Ktor, not by dav4jvm itself (the jar is 438,584 bytes).

## 7. What is not settled here

* No DAV request was exercised against a real server; whether the identity-aware proxy honours service-token headers
  on `PROPFIND`/`REPORT` is a different ticket's question.
* KeyChain behaviour on a device (consent prompt, grant survival across process death, selecting a certificate per
  account, and what `getPrivateKey` does when the grant has lapsed) needs a device test. The library-side plumbing is
  proven; the platform-side behaviour is not.
* The exact way to disable Ktor's own redirect plugin on the *client* level has to be part of the spec: dav4jvm
  requires `followRedirects = false` on the `HttpClient` (README, `DavResource` KDoc) because it follows redirects
  itself and must see the 3xx. OkHttp-level redirects are already off by the engine's defaults, but the Ktor
  `HttpRedirect` plugin operates above the engine and would hide them.
* Whether to keep the OkHttp engine (needed for `Interceptor` + custom `SSLSocketFactory`) or use Ktor's Android
  engine (headers via `defaultRequest`; certificate handling would then need engine-specific wiring) — both satisfy
  must-haves #1–#3 in principle, but only the OkHttp route was verified end to end here.

## 8. Verdict on the README decision

The decision "protocol layer from dav4jvm, hand-rolled OkHttp as the fallback" **survives**, and the escape hatch in
the README should not be needed. Concretely:

* **Custom headers: yes, unrestricted.** The library never owns the client, so an app-level `Interceptor` (or
  `defaultRequest`) covers `PROPFIND`, `REPORT` and everything else — which the library's own
  `additionalHeaders` parameters would not.
* **KeyChain certificate: yes, via the same client.** `engine { preconfigured = <OkHttpClient with sslSocketFactory> }`
  is a supported Ktor API, and the KeyChain `X509KeyManager` shape Android documents is exactly what an
  `SSLContext`-derived `SSLSocketFactory` needs. Compile+dex verified on Android; device behaviour still to test.
* **Cost of the library being "only primitives": unchanged by the library choice.** The sync algorithm
  (CTag/ETag/sync-token handling, member diffing, provider writes) has to be written either way. The fallback would
  add ~4,000 lines of re-implemented protocol and property code plus its maintenance on top of that — the concrete
  content of "what the hand-rolled OkHttp fallback actually costs".
* **Two constraints to bake into the spec, both cheap:** the Android app must compile with Kotlin ≥ 2.4 (AGP 9's
  built-in Kotlin 2.2 fails on dav4jvm's 2.4.0 metadata — put `kotlin-gradle-plugin:2.4.x` on the buildscript
  classpath), and the build must resolve JitPack (pin `com.github.bitfireAT:dav4jvm:4.1.0`; add
  `slf4j-android`/`nop`; keep Guava).
