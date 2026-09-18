# Research: on-device header-injecting proxy in front of an existing DAV client (#4)

## Verdict

**Viable-with-caveats.** Every supposed hard blocker was checked against primary source and does not hold for DAVx⁵: it accepts `http://127.0.0.1:<port>/...` base URLs (no host or loopback validation anywhere in its URL path), it permits cleartext app-wide, it trusts user-installed CAs by default, and it already has a first-class HTTP/SOCKS proxy setting — so **no `VpnService` and no client patch are needed**. The real caveats are (a) the proxy must run as a persistent foreground service with a notification, (b) the topology that needs no certificate work at all (client pointed at the loopback origin) is only safe if the server emits path-absolute `href`s — one `PROPFIND` settles that, and (c) the proxy holds the service token (and possibly the client key), so it must be loopback-only and treated as a credential store. Effort is roughly **5–15% of a full sync provider**, and the proxy also delivers the project's stated differentiator (honest error surfacing) more cheaply than the provider would.

Everything below is from DAVx⁵ source, AOSP source, RFCs, or first-party documentation. Inferences are marked.

---

## 1. Will a mainstream Android DAV client accept a loopback base URL?

**Yes, for DAVx⁵ — verified in its source, not inferred.**

- URL handling switches on **scheme only**, and `http` is an accepted scheme for a user-entered base URL: `core/src/main/kotlin/at/bitfire/davdroid/servicedetection/DavResourceFinder.kt:129-160` (`when (baseURI.scheme.lowercase()) { "http", "https" -> … }`). For `http` it skips DNS-based service discovery and simply probes the URL the user gave.
- The login flow likewise accepts `http` and normalises DAV-ish schemes to it (`caldav`→`http`, `caldavs`→`https`): `core/src/main/kotlin/at/bitfire/davdroid/ui/setup/LoginActivity.kt:88-118`.
- There is **no loopback/private-address/host validation**. A repo-wide search for `127.0.0.1`, `localhost`, `loopback`, `InetAddress`, `isSiteLocal*`, private-range checks finds only the DNS resolver (`network/DnsRecordResolver.kt`) and the debug/test network-security comment — the only hits for loopback in production code are the network-security-config file discussed below. Nothing rejects a numeric or localhost host.
- Both a hostname and a port are carried through verbatim (`URI(realScheme, null, uri.host, uri.port, uri.path, uri.query, null)` in `LoginActivity.kt:111-117`).

Caveat (inference, not verified here): this is DAVx⁵-specific. Other Android DAV clients were not checked, and for them the same question has to be answered separately.

Sources — `bitfireAT/davx5-ose` at commit `481868af0a588736e4140e1c54d4756b53153410` (2026-09-17):
<https://github.com/bitfireAT/davx5-ose/blob/481868af0a588736e4140e1c54d4756b53153410/core/src/main/kotlin/at/bitfire/davdroid/servicedetection/DavResourceFinder.kt#L129-L160>,
<https://github.com/bitfireAT/davx5-ose/blob/481868af0a588736e4140e1c54d4756b53153410/core/src/main/kotlin/at/bitfire/davdroid/ui/setup/LoginActivity.kt#L88-L118>

## 2. Cleartext and TLS

**Cleartext: DAVx⁵ permits it app-wide, in release builds.** Its manifest points at a network-security-config, and that file's `base-config` sets `cleartextTrafficPermitted="true"` — deliberately, with the reason written in the comment:

```xml
<!-- We need cleartext traffic because users
1. can use `http://` WebDAV servers (usually in the LAN),
2. can use a HTTP/SOCKS/Tor proxy without encryption (usually, but not necessarily on localhost). -->
<base-config cleartextTrafficPermitted="true">
```

<https://github.com/bitfireAT/davx5-ose/blob/481868af0a588736e4140e1c54d4756b53153410/core/src/main/res/xml/network_security_config.xml>,
manifest wiring: <https://github.com/bitfireAT/davx5-ose/blob/481868af0a588736e4140e1c54d4756b53153410/core/src/main/AndroidManifest.xml#L39>

That file is the only network-security-config in the repository (`find` over the whole tree), so no build flavour narrows it. The HTTP stack also explicitly offers `ConnectionSpec.CLEARTEXT` (`network/HttpClientBuilder.kt:270-277`). Android's default for targetSdk ≥ 28 apps is cleartext disabled ([Network security configuration → Cleartext traffic](https://developer.android.com/privacy-and-security/security-config)), but that default applies to *the client app's own* policy, and DAVx⁵ has opted out of it. **Consequence: for cleartext-to-loopback, no certificate work is needed at all.**

**TLS route, if the proxy terminates TLS instead:** Android 7+ apps do not trust user-installed CAs by default — the docs state that "apps targeting Android 6.0 (API level 23) and lower also trust the user-added CA store by default" ([same page](https://developer.android.com/privacy-and-security/security-config)). DAVx⁵ is not in that group: its network-security-config trusts **both** `system` and `user` anchors in `base-config`, in release builds:

```xml
<trust-anchors>
    <certificates src="system"/>
    <certificates src="user" tools:ignore="AcceptsUserCertificates" />
</trust-anchors>
```

The lint ignore is only a lint suppression; the config is real. DAVx⁵'s own settings text confirms the semantics from the other side — the "Distrust system certificates" option is described as "System and user-added CAs won't be trusted" (`core/src/main/res/values/strings.xml:213-214`).

Two independent trust paths therefore exist for a locally generated CA/key pair:
1. **User CA store.** Install the proxy's CA as a user CA; DAVx⁵ trusts it by configuration above. (Moot if the loopback-origin topology in §3 is used.)
2. **cert4android interactive acceptance.** The OSE build wires cert4android's `CustomCertManager`/`CustomCertStore` as the trust manager (`app-ose/src/ose/kotlin/com/davx5/ose/di/Cert4AndroidModule.kt:36-60`). Its logic is: explicitly accepted by user → trust; explicitly rejected → reject; else check the platform trust store; else ask the user, via an activity when the app is foreground or a notification otherwise, blocking up to 60 s (`cert4android` at `da1efb901e2edc18dca20526455d8abd413639b7`: `lib/…/CustomCertStore.kt:96-140`, `CustomCertManager.kt:52-70`, `UserDecisionRegistry.kt:61-105`). Note this path needs notification permission or a foreground app, and `trustSystemCerts` is `!DISTRUST_SYSTEM_CERTIFICATES`, default `false` (`Cert4AndroidModule.kt:44-47`, `DefaultsProvider.kt:14`).

So the "user CA that apps do not trust by default" problem is surmountable for DAVx⁵ **without root and without patching**, and it is also avoidable entirely by not terminating TLS on the client leg.

## 3. Does receiving the traffic require a VPN?

**No.** Two topologies work without `VpnService`, because the client can be made to send its traffic to a loopback listener by configuration, not interception:

**Topology A — loopback origin (no TLS on the client leg, no MITM, no certificate work).**
The DAVx⁵ account base URL is `http://127.0.0.1:<port>/<real path>`. Cleartext is permitted by DAVx⁵ (§2), so requests arrive in plain HTTP and the proxy can add headers to them directly. The proxy is a reverse proxy with a *fixed* upstream: it replaces the `Host` header (the client sends the proxy's authority, so the real host cannot be "preserved" here — see §6), connects to the real origin over TLS with the correct SNI, injects the service-token headers, and presents the client certificate if the origin requires mTLS.

**Topology B — explicit proxy configured inside DAVx⁵ (client keeps its real `https://` URL; TLS interception needed).**
DAVx⁵ has an app-wide proxy setting with system-default / none / HTTP / SOCKS: `settings/Settings.kt:14-20` (`-1/0/1/2`), defaults `PROXY_TYPE_SYSTEM` + host `localhost` (`DefaultsProvider.kt:21-22,30`), UI at `ui/AppSettingsScreen.kt:344-395`, documented in the manual's "Override proxy settings" ("you have to specify a HTTP(S) proxy … Can be used to route all DAVx⁵ traffic through a certain proxy")
(<https://manual.davx5.com/settings.html#app-wide-settings>). The value reaches the network stack: `network/HttpClientBuilder.kt:295-316` builds a `java.net.Proxy` and assigns it to the Ktor engine config, and Ktor's OkHttp engine applies it with `config.proxy?.let { builder.proxy(it) }`
(<https://github.com/ktorio/ktor/blob/898de8b1fb67ceeacf144dff7ce0eac24e33c2b8/ktor-client/ktor-client-okhttp/jvm/src/io/ktor/client/engine/okhttp/OkHttpEngine.kt#L164>).

For an `https://` target through an HTTP proxy, OkHttp opens a `CONNECT` tunnel (`createTunnelRequest(route)` when `route.requiresTunnel()` is true, i.e. `proxy.type() == HTTP && address.sslSocketFactory != null`) — so the proxy sees only ciphertext and **cannot inject headers unless it terminates TLS**:

- <https://github.com/square/okhttp/blob/759a4e5b2d6d2a5b99c243f757de7fcf8903bf88/okhttp/src/commonJvmAndroid/kotlin/okhttp3/Route.kt#L76-L86>
- call site: `okhttp/…/internal/connection/RealRoutePlanner.kt:233-237`
- HTTP-level rationale: <https://www.rfc-editor.org/rfc/rfc9112#section-3.2.2> (`CONNECT` uses authority-form; non-CONNECT proxied requests use absolute-form, which is what makes a *cleartext* proxied request inspectable).

Therefore: **HTTP-proxy-typed loopback proxy + real https URL ⇒ TLS termination required** (§2 makes that workable); **loopback base URL ⇒ no TLS work**, at the price of the origin-substitution caveats in §6. SOCKS (`PROXY_TYPE_SOCKS`) is a TCP relay and is useless for header injection — it can only tunnel.

A third, client-settings-free variant exists — pointing a Wi-Fi network's proxy at the loopback listener, honoured through `ProxySelector` since DAVx⁵ defaults to `PROXY_TYPE_SYSTEM` (`HttpClientBuilder.kt:299`) — but it also lands in the CONNECT/MITM case and only works on networks where such a proxy is configured. Not recommended over Topology B.

## 4. Background survival

**Viable, via a foreground service; the persistent notification is mandatory.** An always-listening socket cannot be started on demand, and Android has no "start the app when something connects" mechanism, so the listening socket must exist whenever sync can run.

- **Process/socket survival:** App Standby explicitly does not treat an app as idle while it "has a process currently in the foreground, either as an activity or **foreground service**" (<https://developer.android.com/training/monitoring-device-state/doze-standby#understand_app_standby>). Background (non-foreground) services are the ones that get killed.
- **Network during Doze — verified in AOSP source, not just docs.** Android's own summary table lists a foreground-service app state as **Network: "No restrictions"** (<https://developer.android.com/topic/performance/power/power-details#app-state>) while the device-state table lists network as "Restricted during doze" for apps generally. AOSP confirms the FGS wins: when Doze is active, `updateRulesForAllowlistedPowerSaveUL(uid, enabled, FIREWALL_CHAIN_DOZABLE)` grants `FIREWALL_RULE_ALLOW` to a UID that is power-save-allowlisted **or** `isUidForegroundOnRestrictPowerUL(uid)` (<https://github.com/aosp-mirror/platform_frameworks_base/blob/0ab54d6c305bce6d850ac97515afaaad95fc8b6c/services/core/java/com/android/server/net/NetworkPolicyManagerService.java#L4847-L4856>), and the blocked-state computation clears `BLOCKED_REASON_DOZE` when `ALLOWED_REASON_FOREGROUND` is set (same file, `getEffectiveBlockedReasons`, lines 6821-6825). `isUidForegroundOnRestrictPowerUL` is true for proc-states ≤ `FOREGROUND_THRESHOLD_STATE` = `PROCESS_STATE_BOUND_FOREGROUND_SERVICE` (same file lines 4362-4378; <https://github.com/aosp-mirror/platform_frameworks_base/blob/38779c1743cec876e023a3c67d777c85397902ab/core/java/android/net/NetworkPolicyManager.java#L184-L190> and `#L830-L838`), and `PROCESS_STATE_FOREGROUND_SERVICE` sits below that threshold in the enum ordering. **So a running foreground service keeps network access during Doze on AOSP.**
- **Which FGS type:** Android 14+ requires a declared type. `dataSync` is the obvious-looking choice and is the wrong one: since Android 15 it is limited to a total of 6 hours per 24 (`<https://developer.android.com/about/versions/15/behavior-changes-15#datasync-timeout>`), which kills a permanently listening proxy. `specialUse` has no timeout, needs `FOREGROUND_SERVICE_SPECIAL_USE` plus a `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE">` explanation, and is reviewed on Play submission (<https://developer.android.com/develop/background-work/services/fgs/service-types#special-use>). Also note Android 15 forbids `BOOT_COMPLETED` receivers from launching `dataSync` — `specialUse` is not on that list, so boot-restart is possible (<https://developer.android.com/about/versions/15/behavior-changes-15#fgs-boot-completed-restrictions>), and boot-time FGS start is otherwise an explicit exemption (<https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start#wiu-restrictions-exemptions>).
- **Extra, optional:** requesting the battery-optimization exemption is the documented way to use the network during Doze and is the pattern DAVx⁵ itself uses (`ui/intro/BatteryOptimizationsPage.kt:44`, `ui/AppSettingsViewModel.kt:62`); per the AOSP analysis above it is not strictly required for an FGS app, but OEM ROMs add their own killers, so ask for it (inference about OEM ROMs).
- **Honest framing of the cost:** the app is a permanent-notification app. That is the price of the whole approach; there is no way to have a listening socket on modern Android without it.

## 5. Prior art

Verified via the GitHub API (stars / last push as of 2026-09-18):

| Project | What it is | Status |
| --- | --- | --- |
| [`wanghongenpin/proxypin`](https://github.com/wanghongenpin/proxypin) | Android/desktop MITM capture app with **request rewriting and header modification**, JS scripting, domain filtering. Android capture uses a `ProxyVpnService` (`android/app/src/main/kotlin/com/network/proxy/ProxyVpnService.kt`) | Apache-2.0, ~14k stars, pushed 2026-09-18 — actively maintained |
| [`httptoolkit/httptoolkit-android`](https://github.com/httptoolkit/httptoolkit-android) | Android interception app; header editing happens on the desktop side | AGPL-3.0, ~630 stars, pushed 2026-09-03 — maintained |
| [`mitmproxy/mitmproxy`](https://github.com/mitmproxy/mitmproxy) | Reference local MITM proxy with a built-in `modifyheaders` addon (`mitmproxy/addons/modifyheaders.py`) for adding/removing/replacing arbitrary headers; runs on a desktop or (unsupported-but-common) on-device | actively maintained |
| [`raise-isayan/TunProxy`](https://github.com/raise-isayan/TunProxy) | `VpnService` interceptor that forwards app traffic to an HTTP/HTTPS proxy | ~530 stars, pushed 2026-09-06 |
| [`madeye/proxydroid`](https://github.com/madeye/proxydroid) | Classic global proxy redirector, root/iptables era | ~2.5k stars, last push 2026-05-12 but it is a legacy codebase |
| [`hect0x7/android-proxy-server`](https://github.com/hect0x7/android-proxy-server) | Local HTTP/SOCKS **server** on Android, explicitly *not* a VPN, run as a foreground service with a partial wake lock and a battery-optimization prompt; it binds `0.0.0.0` and deliberately **rejects connections originating from the phone itself** | Apache-2.0, created 2026-07-18, pushed 2026-09-15 |
| [`abdoxfox/HTTP-CUSTOM-HEADERS-VPN`](https://github.com/abdoxfox/HTTP-CUSTOM-HEADERS-VPN) | Despite the name, an SSH/SNI payload tunnel for mobile-data tricks, root required — not a header injector | Apache-2.0, ~200 stars, pushed 2025-06-25 |

**Gap:** I found no maintained app that is a **no-VPN, loopback-only, header-injecting reverse proxy** intended to sit in front of another app's own DAV/HTTP traffic. The header-rewriting apps of note (ProxyPin, HTTP Toolkit) are `VpnService`-based; the no-VPN local proxy app is a LAN-server, not an injector. The mechanics this ticket needs — foreground service + partial wake lock + battery-exemption request for a long-lived listener — are demonstrated by `hect0x7/android-proxy-server`, including its note that "otherwise Doze may suspend network access during extended idle periods".

## 6. The rewriting problem

The relevant norm is RFC 4918 §8.3 *URL Handling*: a server chooses between a relative reference and a full URI — `Simple-ref = absolute-URI | ( path-absolute [ "?" query ] )` — and "A server MUST ensure that every 'href' value within a Multi-Status response uses the same format"; the `href` element itself is defined as "MUST contain a URI or a relative reference" (§14.7). <https://www.rfc-editor.org/rfc/rfc4918#section-8.3>, <https://www.rfc-editor.org/rfc/rfc4918#section-14.7>

**How a real client handles both — verified in dav4jvm's parser:** every `href` is resolved against the *request* URL (`ResponseParser.resolveHref` → `Url.resolve`, implemented with Ktor's `URLBuilder(this).takeFrom(relative)`), with an explicit comment that only absolute paths are permitted as relative URLs but some servers send other relative paths anyway.
<https://github.com/bitfireAT/dav4jvm/blob/0980f6bf16187293de6ece72587f1f8b0db6cd66/src/main/kotlin/at/bitfire/dav4jvm/ktor/ResponseParser.kt#L145>, <https://github.com/bitfireAT/dav4jvm/blob/0980f6bf16187293de6ece72587f1f8b0db6cd66/src/main/kotlin/at/bitfire/dav4jvm/ktor/UrlUtils.kt#L100>, and its tests: a path-absolute href resolves against the base, an absolute URI resolves to that other origin (<https://github.com/bitfireAT/dav4jvm/blob/0980f6bf16187293de6ece72587f1f8b0db6cd66/src/test/kotlin/at/bitfire/dav4jvm/ktor/UrlUtilsTest.kt#L111-L140>).

Consequences:

- **Topology A (client pointed at the loopback origin):** if the server emits **path-absolute** hrefs (the common case, and the format RFC 4918 shows in its own examples), every discovered collection/object URL resolves against the request URL — i.e. it stays on the loopback origin — so **no body rewriting is needed and no request escapes the proxy**. A *pure header-adding pass-through* is therefore sufficient. If the server instead emits absolute URIs, those resolve to the real origin and the client's later `PROPFIND`/`REPORT`/`GET` calls go direct, bypassing the proxy — sync fails at Cloudflare Access rather than at the client. That is why I do not call this topology unconditionally safe: **it must be checked with one `PROPFIND` against the real server and a look at the `href` form**, after which that topology is either clean or needs a bounded `href` rewrite.
- **"Preserving the real `Host`" cannot be done by the client in Topology A** — the client addresses the proxy, so the `Host` it sends is `127.0.0.1:<port>`, and the proxy must substitute the upstream authority (and SNI) when it connects. That is header substitution, not body rewriting, and it is unavoidable in that topology. In Topology B the client's `Host` and origin are already the real ones and nothing at all needs rewriting — which is the strongest argument for Topology B despite the certificate work.
- **Redirects** are the other escape hatch: an absolute `Location:` from the server (e.g. for `/.well-known/…`) would move the client off the proxy origin in Topology A. Note DAVx⁵ does not follow redirects by default (`HttpClientBuilder.kt:111`, `followRedirects: Boolean = false`); it opts in only where it asks for them (`webdav/WebDavUrlChecker.kt:50`, `sync/ResourceRetriever.kt:70`, `network/NextcloudLoginFlow.kt:133`). In Topology B this is a non-issue: every request goes back through the same configured proxy.
- **If a rewrite is needed**, it is cheap in Topology A: the client leg is plaintext, so a substitution over `href` values (or a `Location` header rewrite) costs a few lines — the only care needed is re-framing the response (recompute `Content-Length`, or switch to chunked; and either strip `Accept-Encoding` for XML responses or decompress before rewriting).
- Read-only v1 helps here: no request-side path rewriting is needed for `PUT`/`MOVE`/`COPY` `Destination` URLs, which are the other place absolute URLs appear in DAV.

## Effort estimate relative to building a sync provider

- **Topology A proxy, working end-to-end:** loopback HTTP/1.1 listener with a fixed upstream, `Host`/SNI substitution, header injection, an upstream TLS client (optional client certificate from Keychain or a bundled PKCS#12), streaming body forwarding with correct framing, one-screen configuration, `specialUse` foreground service with notification, battery-exemption prompt. **~400–900 lines of Kotlin; days, not weeks.**
- **Topology B adds:** `CONNECT` handling, on-the-fly leaf-certificate generation per host (Bouncy Castle or equivalent), CA onboarding and trust instructions, HTTP/1.1-over-TLS framing (HTTP/2 optional; DAVx⁵ already disables HTTP/2 when a client certificate is used — `network/ConnectionSecurityManager.kt:70`, consumed at `network/HttpClientBuilder.kt:285`), plus the redirect/`Location` handling that comes free. **~1,200–2,500 lines; one to two weeks of agent work.**
- **The README's sync provider**, by contrast, needs two platform sync adapters (`ContactsContract`, `CalendarContract`), discovery, a read-only sync engine with ETag bookkeeping, per-account header/certificate/auth configuration, background scheduling, and an error taxonomy — order-of-magnitude **10–20k lines and weeks-to-months**, plus the ongoing cost of owning DAV protocol correctness (parsing, recurrence, iCalendar/vCard edge cases).

So the proxy is on the order of **5–15% of the provider build** and removes the riskiest and most expensive part of it (sync correctness, which DAVx⁵ already owns). Worth recording for the map: the proxy also delivers the project's stated differentiator — honest error surfacing (status line, first line of the body, whether a client certificate was offered) — because the proxy sits on the request path and can distinguish those cases as easily as a provider could.

## Caveats that belong in any go/no-go on this route

1. **Permanent notification.** Non-negotiable on modern Android (§4). Play submission with `specialUse` requires a justification declaration; sideloading avoids that.
2. **The proxy becomes a credential store.** It holds the service-token headers and, if mTLS is used, a client key. The listener must bind **loopback only** — the reference local-proxy app binds `0.0.0.0` and is explicitly *not* a model here — and, because any app on the device can reach a loopback port, consider requiring its own proxy credential so the token cannot be exercised by arbitrary on-device code. (Inference: I found no prior art that solves this on-device-sharing problem; it is a design decision, not a researched fact.)
3. **Two DAVx⁵-specific dependencies**, both ordinary configuration rather than patching, but neither a public contract: its permissive cleartext policy, and its HTTP/SOCKS proxy setting. A future release could tighten either. This route is calibrated for DAVx⁵; the same trick for another client depends on that client's network-security-config and whether it offers a proxy setting.
4. **Client-certificate handling moves into the proxy.** For mTLS, the proxy must hold the private key (Android `KeyChain` alias granted to the proxy app, or a bundled keystore). That is a relocation, not extra work — but it is a separate app holding the key.
5. **OEM battery managers** are outside everything documented above; an FGS plus the battery-optimization exemption is the strongest available ask, not a guarantee.

## What would settle the remaining uncertainty

1. **One `PROPFIND`** (plus one `OPTIONS`) against the real server through a plain client, inspecting whether `href`s are path-absolute or absolute URIs, and whether anything answers with an absolute `Location`. This decides whether Topology A is clean or needs a bounded rewrite, i.e. whether the zero-certificate variant is usable.
2. **A device test:** run a throwaway loopback reverse proxy that injects one fixed marker header, add a DAVx⁵ account pointed at `http://127.0.0.1:<port>/…`, and confirm (a) the upstream log shows the marker header on `PROPFIND`/`REPORT`, and (b) a sync completes with the account's collections populated. This also exercises the port/host handling in DAVx⁵'s login flow that is only read from source here.
3. Optionally the same test with a foreground service left running over a Doze cycle (`adb shell dumpsys deviceidle force-idle`) to confirm the socket survives on the actual device/OEM ROM.

## Versions inspected

- `bitfireAT/davx5-ose` @ `481868af0a588736e4140e1c54d4756b53153410` (2026-09-17)
- `bitfireAT/cert4android` @ `da1efb901e2edc18dca20526455d8abd413639b7` (2026-09-01)
- `bitfireAT/dav4jvm` @ `0980f6bf16187293de6ece72587f1f8b0db6cd66` (2026-09-14)
- AOSP `platform_frameworks_base` @ `0ab54d6c305bce6d850ac97515afaaad95fc8b6c` (`NetworkPolicyManagerService.java`), `38779c1743cec876e023a3c67d777c85397902ab` (`NetworkPolicyManager.java`)
- `square/okhttp` @ `759a4e5b2d6d2a5b99c243f757de7fcf8903bf88` (`Route.kt`); `ktorio/ktor` @ `898de8b1fb67ceeacf144dff7ce0eac24e33c2b8` (`OkHttpEngine.kt`)
- RFCs: 4918 (§8.3, §14.7), 9112 (§3.2.2)
- Android developer documentation: network security configuration, Doze/App Standby, power management resource limits, foreground service types, FGS background-start restrictions, Android 15 behavior changes
