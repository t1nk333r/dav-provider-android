# Research: sync adapter vs WorkManager under current background limits (ticket #7)

## Verdict

Use a **sync adapter as the single background mechanism**: `AbstractThreadedSyncAdapter` is *not*
deprecated (neither is `AccountManager` nor any of the sync-scheduling APIs), the framework
schedules every sync as a persisted JobScheduler job, and it is the only mechanism that gives the
app a per-account sync toggle, an `Auto-sync`-respecting periodic schedule, sync-on-provider-change
and the "sync now" contract other apps call into. WorkManager adds nothing this design needs: it is
deferred by exactly the same Doze/JobScheduler rules, and its only genuine advantage (a native
unmetered constraint) can be obtained by gating inside `onPerformSync` and deferring with
`SyncResult.delayUntil`. A hybrid (adapter enqueues a WorkManager worker) is **not** an established
pattern in first-party documentation and costs a second scheduler, truthful-result problems and an
interaction bug with the framework's 60-second no-progress watchdog; it should not be part of v1.

---

## 1. Deprecation status, precisely

Nothing in the sync stack is class-level deprecated as of current AOSP (`refs/heads/main`) or the
current published reference. The sync-adapter guide is still published and maintained under
Connectivity (`https://developer.android.com/training/sync-adapters`, landing page last updated
2023-09-08; `.../creating-sync-adapter` 2024-01-03; `.../creating-authenticator` 2024-01-23). What it
*does* carry is a recommendation, not a deprecation:

> "Note: We recommended WorkManager as the recommended solution for most background processing use
> cases. Please reference the background processing guide to learn which solution works best for you."
> — `https://developer.android.com/training/sync-adapters`

Note the scope of that note: it is about *background processing* generally, and it appears on every
lesson page. Nothing anywhere says "sync adapters are deprecated". The WorkManager overview page
lists only the APIs WorkManager is the declared replacement for, and sync adapters are not among
them: "The WorkManager API is the recommended replacement for previous Android background scheduling
APIs, including FirebaseJobDispatcher and GcmNetworkManager."
(`https://developer.android.com/topic/libraries/architecture/workmanager`) Google's current
"Background work" guide never mentions sync adapters at all
(`https://developer.android.com/develop/background-work/background-tasks`).

### Verified deprecated members (and only these)

From `AbstractThreadedSyncAdapter` — `core/java/android/content/AbstractThreadedSyncAdapter.java`
(AOSP main), which carries exactly one `@Deprecated`:

| Member | Deprecated in | Note |
|---|---|---|
| `LOG_SYNC_DETAILS` (int constant) | API 15 | "Private constant. May go away in the next release." |

The class, its constructors, `onPerformSync`, `getSyncAdapterBinder`, `onSyncCanceled` are not
deprecated. Reference page: `https://developer.android.com/reference/android/content/AbstractThreadedSyncAdapter`

From `AccountManager` — `core/java/android/accounts/AccountManager.java` (AOSP main) has 4
`@Deprecated` declarations, none of them about sync or account creation:

| Member | Deprecated in |
|---|---|
| `getAuthToken(Account, String, boolean, AccountManagerCallback, Handler)` | API 15 (use the `Bundle` overload) |
| `removeAccount(Account, AccountManagerCallback, Handler)` | API 22 (use the `Activity` overload) |
| `removeAccountAsUser(...)` (hidden) | API 22 |
| `newChooseAccountIntent(Account, ArrayList, String[], boolean, ...)` | API 14 → replaced by the `List`/`Build.VERSION_CODES` overload |
| `LOGIN_ACCOUNTS_CHANGED_ACTION` | API 26 (use `addOnAccountsUpdatedListener`) |

`addAccountExplicitly`, `getAccountsByType`, `setUserData`, `blockingGetAuthToken`, the modern
`getAuthToken(Account, String, Bundle, boolean, ...)` are live.
Reference: `https://developer.android.com/reference/android/accounts/AccountManager`

From `ContentResolver` — `core/java/android/content/ContentResolver.java` (AOSP main). The whole
sync-scheduling surface is live; only legacy pre-2011 shape is deprecated:

| Deprecated | In | Replacement |
|---|---|---|
| `SYNC_EXTRAS_ACCOUNT`, `SYNC_EXTRAS_FORCE` | 15 | `requestSync(Account, String, Bundle)` / `SYNC_EXTRAS_MANUAL` |
| `startSync(Uri, Bundle)` | 15 | `requestSync(Account, String, Bundle)` |
| `cancelSync(Uri)` | 15 | `cancelSync(Account, String)` |
| `getCurrentSync()` | 15 | `getCurrentSyncs()` |
| `notifyChange(Uri, ContentObserver, boolean)` (+ `Iterable` variant) | 30 | `notifyChange(Uri, ContentObserver, int)` |

Not deprecated, and load-bearing for this project: `requestSync(Account, String, Bundle)` (API 5),
`requestSync(SyncRequest)` (API 19), `addPeriodicSync` (API 8), `removePeriodicSync`,
`getPeriodicSyncs`, `setSyncAutomatically` / `setSyncAutomaticallyAsUser` (API 5), `setIsSyncable`,
`setMasterSyncAutomatically`, `cancelSync`, `getSyncAdapterTypes`, `isSyncActive`, `isSyncPending`,
`getCurrentSyncs`, `validateSyncExtrasBundle`.
Reference: `https://developer.android.com/reference/android/content/ContentResolver`

`SyncRequest.Builder` (API 19) is live end to end: `syncOnce()`, `syncPeriodic(long, long)`,
`setSyncAdapter`, `setManual`, `setExpedited` (19), `setSyncFlexTime`/`setSyncFlexTime`-equivalent,
`setDoNotRetry`, `setIgnoreBackoff`, `setRequiresCharging` (API 24), `setScheduleAsExpeditedJob`
(API 31). Source: `core/java/android/content/SyncRequest.java`; reference:
`https://developer.android.com/reference/android/content/SyncRequest.Builder`

**Conclusion for the spec:** the "discouraged" framing is wrong; the accurate statement is
"old but current, with a general nudge toward WorkManager for background processing in general".
There is no deprecation clock on the mechanism this app needs.

---

## 2. What only a sync adapter provides

### 2.1 System-managed scheduling on top of JobScheduler

`SyncManager` (AOSP main, `services/core/java/com/android/server/content/SyncManager.java`) states in
its own class documentation:

> "All scheduled syncs will be passed on to JobScheduler as jobs (See
> `scheduleSyncOperationH(SyncOperation, long)`). This function schedules a job with JobScheduler with
> appropriate delay and constraints (according to backoffs and extras). … **Periodic Syncs:** Each
> periodic sync is scheduled as a periodic job. If a periodic sync fails, we create a new one off
> SyncOperation … We don't allow the periodic job to run while any job initiated by it is pending."

The concrete `JobInfo.Builder` used per sync (`scheduleSyncOperationH`):

```java
JobInfo.Builder b = new JobInfo.Builder(syncOperation.jobId,
        new ComponentName(mContext, SyncJobService.class))
        .setExtras(syncOperation.toJobInfoExtras())
        .setRequiredNetworkType(networkType)          // UNMETERED if the (hidden) meter flag is set
        .setRequiresStorageNotLow(true)
        .setPersisted(true)                            // survives reboot
        .setBias(bias)
        .setFlags(jobFlags);                           // FLAG_EXEMPT_FROM_APP_STANDBY when exempted
// periodic: b.setPeriodic(periodMillis, flexMillis);  one-off: b.setMinimumLatency(minDelay)
// if (syncOperation.hasRequireCharging()) b.setRequiresCharging(true);
```

What the app therefore gets for free, and would have to re-implement with WorkManager:

* **Reboot survival.** Jobs are persisted (`setPersisted(true)`), and `SyncManager` reloads them from
  its own JobScheduler namespace at start-up. `JobInfo.Builder.setPersisted` "Set whether or not to
  persist this job across device reboots. Requires `Manifest.permission.RECEIVE_BOOT_COMPLETED`"
  (API 21) — so the app manifest must declare `RECEIVE_BOOT_COMPLETED` or the persisted job is
  rejected by JobScheduler.
  `https://developer.android.com/reference/android/app/job/JobInfo.Builder#setPersisted(boolean)`
* **Backoff/retry.** Initial 30 s, multiplied by a retry factor, capped at 1 h
  (`SyncManagerConstants`: `DEF_INITIAL_SYNC_RETRY_TIME_IN_SECONDS = 30`,
  `DEF_MAX_SYNC_RETRY_TIME_IN_SECONDS = 60 * 60`). Hard errors stop retries; soft errors retry with
  exponential backoff (`SyncManager.maybeRescheduleSync`).
* **A held wakelock for the duration of the sync** (`ActiveSyncContext` acquires
  `PowerManager.PARTIAL_WAKE_LOCK` named `*sync*/…` for the adapter's UID, and is released when the
  sync finishes or is cancelled).
* **App-standby exemption when a foreground app requests the sync.** `SyncManager.md`
  (same directory): "calls to `ContentResolver.requestSync()` made by foreground apps are special
  cased such that the resulting sync operations will be exempted from app-standby throttling"
  (`SYNC_EXEMPTION_PROMOTE_BUCKET`, `SYNC_EXEMPTION_PROMOTE_BUCKET_WITH_TEMP`, temp allow-list for
  `DEF_EXEMPTION_TEMP_ALLOWLIST_DURATION_IN_SECONDS = 10 * 60`). WorkManager jobs have no equivalent.
* **Force-stop semantics** are the platform's own: `SyncManager` checks
  `mPackageManagerInternal.isPackageStopped(...)` before dispatching, so a force-stopped app is not
  synced until it is launched again.
* **User-visible error reporting through the sync-status API**: `SyncResult` fields such as
  `stats.numAuthExceptions`, `stats.numIoExceptions`, `delayUntil` (API 8),
  `fullSyncRequested`, which drive the sync-status spinner in Settings and the Contacts/Calendar
  apps.
  `https://developer.android.com/reference/android/content/SyncResult`

### 2.2 The per-account sync toggle (and what the account entry itself needs)

The per-account/per-authority sync switch in Settings is built **only** from registered, user-visible
sync adapters. AOSP Settings `AccountSyncSettings.updateAccountSwitches()`
(`packages/apps/Settings/src/com/android/settings/accounts/AccountSyncSettings.java`) does:

```java
SyncAdapterType[] syncAdapters = ContentResolver.getSyncAdapterTypesAsUser(userId);
for (SyncAdapterType sa : syncAdapters) {
    if (!sa.accountType.equals(mAccount.type)) continue;
    if (sa.isUserVisible()) { authorities.add(sa); }        // <- creates a SyncStateSwitchPreference
    else { mInvisibleAdapters.add(sa); }                    // sync-now still forces these
}
...
if (syncState > 0) { addSyncStateSwitch(mAccount, syncAdapter.authority, packageName, uid); }
```

So the toggle requires `android:userVisible="true"` **and** `getIsSyncable() > 0`. The switch writes
`ContentResolver.setSyncAutomaticallyAsUser(...)` and `requestSync`/`cancelSync`.

**Correction to a common assumption:** the account *entry* under Settings → Passwords & accounts does
**not** require a sync adapter. `AccountPreferenceController.getAccountTypePreferences()` draws its
account types from `AuthenticatorHelper`, whose `mEnabledAccountTypes` is built in
`onAccountsUpdated()` purely from the account types of *existing accounts*
(`frameworks/base/packages/SettingsLib/src/com/android/settingslib/accounts/AuthenticatorHelper.java`:
`for (Account account : accounts) if (!mEnabledAccountTypes.contains(account.type)) add(account.type);`).
`AuthenticatorHelper.buildAccountTypeToAuthoritiesMap()` additionally maps account type → sync
authorities from `ContentResolver.getSyncAdapterTypesAsUser()`, and the account list is filtered by
authority only when the screen is opened with an authority filter
(`EXTRA_AUTHORITIES` → `mAuthoritiesCount > 0`).
[INFERENCE] Consequence: an app with an authenticator and an account but no sync adapter appears in
Settings; only the per-authority sync toggle (and the "sync now" affordances) would be missing.

What the sync adapter's `userVisible` flag is documented to control, in the guide's own words:

> "android:userVisible — Sets the visibility of the sync adapter's account type. By default, the
> account icon and label associated with the account type are visible in the Accounts section of the
> system's Settings app, so you should make your sync adapter invisible unless you have an account
> type or domain that's easily associated with your app."
> — `https://developer.android.com/training/sync-adapters/creating-sync-adapter`

The authoritative AOSP paths are the two above; for a DAV provider whose whole point is to feed
Contacts/Calendar, `userVisible="true"` is what makes the account's sync controls (and the Contacts/
Calendar-app sync integration) appear.

### 2.3 Sync-on-provider-change and tickles

From the `AbstractThreadedSyncAdapter` class documentation (AOSP main; identical on
`https://developer.android.com/reference/android/content/AbstractThreadedSyncAdapter`), on
`android:supportsUploading`:

> "defaults to true and if true an upload-only sync will be requested for all syncadapters associated
> with an authority whenever that authority's content provider does a
> `ContentResolver.notifyChange(android.net.Uri, android.database.ContentObserver, boolean)` with
> syncToNetwork set to true."

and `ContentResolver.setSyncAutomatically`:

> "Set whether or not the provider is synced when it receives a network tickle."

This is the mechanism by which the device's own Contacts/Calendar UI expresses "sync now" against our
account: the platform asks *our* adapter, using our account and authority. No WorkManager job can be
triggered this way.

### 2.4 `CALLER_IS_SYNCADAPTER` write semantics — **not** exclusive to sync adapters

Both providers derive the flag from the URI query parameter alone; neither verifies that the caller
is a registered sync adapter.

* Contacts: `ContactsProvider2.insert/update/delete` read
  `readBooleanQueryParameter(uri, ContactsContract.CALLER_IS_SYNCADAPTER, false)`
  (`packages/providers/ContactsProvider/src/com/android/providers/contacts/ContactsProvider2.java`).
  Effect: rows are not marked dirty (`RawContacts.DIRTY` / `SyncColumns` docs:
  "Sync adapters that modify the raw contact or data tables should always append the string
  CALLER_IS_SYNCADAPTER to the content URI they use. This prevents the provider from marking rows as
  dirty."), and a raw contact can be permanently deleted by deleting again with that parameter
  (`https://developer.android.com/reference/android/provider/ContactsContract.RawContacts`).
* Calendar: `CALLER_IS_SYNCADAPTER` (API 14, value `"caller_is_syncadapter"`) is documented as: "an
  optional insert, update or delete URI parameter… If set to true, the modified row is not marked as
  'dirty' … and when the provider calls `notifyChange`, the third parameter 'syncToNetwork' is set to
  false. Furthermore, if set to true, the caller must also include `Calendars.ACCOUNT_NAME` and
  `Calendars.ACCOUNT_TYPE` as query parameters."
  `https://developer.android.com/reference/android/provider/CalendarContract#CALLER_IS_SYNCADAPTER`
  AOSP `CalendarProvider2` lists it in `ALLOWED_URI_PARAMETERS` and derives
  `getIsCallerSyncAdapter(uri)` from the URI. A sync adapter additionally gets more writable columns
  (calendar colour, timezone, access level, …) than an ordinary app;
  `https://developer.android.com/identity/providers/calendar-provider` ("Sync adapters").

[INFERENCE] Therefore the *write* semantics are available to any app holding the READ_/WRITE_CALENDAR
/READ_/WRITE_CONTACTS privileges; the sync adapter is what makes the system ask us to write. That
distinction matters for the go/no-go: nothing about `CALLER_IS_SYNCADAPTER` forces the sync-adapter
architecture; the account/UI/scheduling integration does.

### 2.5 What the framework takes away (the price of the adapter)

* **No-progress cancellation, verified live in current AOSP.** `SyncManager` posts
  `MESSAGE_MONITOR_SYNC` 60 s after a sync starts (`SYNC_MONITOR_WINDOW_LENGTH_MILLIS = 60 * 1000`)
  and cancels the sync if the adapter's UID transferred `≤ SYNC_MONITOR_PROGRESS_THRESHOLD_BYTES`
  (10 bytes) in that window: "Detected sync making no progress for %s. cancelling." +
  `SyncJobService.callJobFinished(jobId, false, "no network activity")`. The monitor is re-posted
  while the sync makes progress.
  **Spec consequence:** never run a sync as "fetch everything, then write locally for minutes" — a
  >60 s window with ≤10 bytes of network traffic kills the run. Chunk work so every 60 s window
  contains network traffic.
* **Interruption contract.** "A sync is cancelled by issuing a `Thread.interrupt()` on the syncing
  thread. Either your code in `onPerformSync` must check `Thread.interrupted()`, or you must override
  `onSyncCanceled(Thread)`/`onSyncCanceled()`… If your adapter does not respect the cancel issued by
  the framework you run the risk of your app's entire process being killed."
  (`AbstractThreadedSyncAdapter` class docs.)
* **Stale 10-minute timeout claim.** The same class docs say "a sync that was not user-initiated and
  lasts longer than 10 minutes will be considered timed-out and cancelled". That timeout no longer
  exists: Android 4.4 had `MAX_TIME_PER_SYNC` (default `5 * 60 * 1000`, sysprop
  `sync.max_time_per_sync`) used with `mTimeoutStartTime`, while both `android-13.0.0_r1` and
  `refs/heads/main` `SyncManager.java` have no `MAX_TIME_PER_SYNC` at all and keep `mTimeoutStartTime`
  only for `dumpsys` output. Treat the doc sentence as stale; design for chunking anyway.
  [INFERENCE] An OEM fork could reintroduce it, so chunked syncs are the safe design.

---

## 3. What WorkManager provides, and its limits

Verified from `https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work`,
`https://developer.android.com/reference/androidx/work/PeriodicWorkRequest`,
`https://developer.android.com/reference/androidx/work/NetworkType`,
`https://developer.android.com/topic/libraries/architecture/workmanager`:

* **Constraints** (all must hold; if one becomes unmet the worker is stopped and retried later):
  `NetworkType` (`NOT_REQUIRED`, `CONNECTED`, `UNMETERED`, `NOT_ROAMING`, `METERED`,
  `TEMPORARILY_UNMETERED`), `BatteryNotLow`, `RequiresCharging`, `DeviceIdle`, `StorageNotLow`.
* **Backoff**: `setBackoffCriteria(policy, delay, unit)` with `BackoffPolicy.LINEAR|EXPONENTIAL`,
  minimum delay `WorkRequest.MIN_BACKOFF_MILLIS` = 10 s; "Backoff delays are inexact and could vary by
  several seconds".
* **Robust scheduling / reboot survival**: "Scheduled work is stored in an internally managed SQLite
  database and WorkManager takes care of ensuring that this work persists and is rescheduled across
  device reboots." The library's own manifest declares `RECEIVE_BOOT_COMPLETED`, a
  `SystemJobService` with `android:permission="android.permission.BIND_JOB_SERVICE"`, and a boot
  `RescheduleReceiver` (`work/work-runtime/src/main/AndroidManifest.xml`, androidx-main) — on API 23+
  the actual reboot survival is JobScheduler's persisted jobs.
* **Periodic minimum**: "The minimum repeat interval that can be defined is 15 minutes (same as the
  JobScheduler minimum)"; `PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS` is documented as "The
  minimum interval duration for `PeriodicWorkRequest`", with a separate flex interval
  (`MIN_PERIODIC_FLEX_MILLIS`) that must fit inside the repeat interval. "even if the defined repeat
  interval passes, the PeriodicWorkRequest will not run until this condition is met. This could cause
  a particular run of your work to be delayed, or even skipped if the conditions are not met within
  the run interval."
* **Doze affects it exactly as it affects sync**: "Doesn't let JobScheduler run. WorkManager uses
  JobScheduler internally, so WorkManager tasks don't run." (Doze restrictions table,
  `https://developer.android.com/training/monitoring-device-state/doze-standby`.) WorkManager
  "adheres to power-saving features and best practices like Doze mode".
* **Execution budget**: long-running work needs `setForeground()` and a notification; Android's
  background-work guidance notes "If a background work task takes longer than 10 minutes to complete,
  it's highly likely to be interrupted"; since Android 15 a `dataSync` foreground service is capped at
  6 hours per 24 for the app (`https://developer.android.com/about/versions/15/behavior-changes-15`).
* **Version floor**: WorkManager 2.11.0 (Oct 2025) raised `minSdk` from API 21 to **API 23**
  ("The minSdk has been updated from API 21 to API 23"),
  `https://developer.android.com/jetpack/androidx/releases/work`.

So on the axis that decides this ticket — constraint handling, reboot survival, Doze behaviour — the
sync framework and WorkManager are the *same machinery* (JobScheduler, persisted jobs). WorkManager's
unique offering is a declaratively expressed unmetered/charging constraint plus a richer backoff
policy; the sync framework's unique offering is everything in §2.

---

## 4. The hybrid pattern

**Not documented as a pattern.** Checked the sync-adapter guide (all five pages), the WorkManager
overview and defining-work pages, the Doze page, and the background-work overview: none describes
combining a sync adapter with WorkManager for the transfer, and the sync-adapter guide's only
cross-reference is the generic "we recommend WorkManager for most background processing" note. The
hybrid is a composition we would be inventing, not established practice we can lean on.

What it would cost (analysis; the mechanics below are sourced, the conclusion is [INFERENCE]):

1. **Truthful `SyncResult` requires blocking on the worker.** If `onPerformSync` merely enqueues work
   and returns, the framework records a successful sync, the Settings/Contacts spinner lies, upload
   retries driven by `SyncResult` stats stop working, and errors cannot be surfaced through the sync
   status API. To keep them, `onPerformSync` must enqueue a unique work request and await its
   `WorkInfo` (blocking is permitted — `onPerformSync` runs on the adapter's own thread).
2. **The watchdog then fights the worker.** The 60-second no-progress monitor watches the *app's UID
   traffic*, so a worker that is deferred because its unmetered constraint is unmet produces no
   traffic and the sync is cancelled as "no network activity" while the worker lives on independently.
   The adapter must therefore pre-check that the constraint is satisfiable before enqueuing (i.e. it
   has to do the metered/unmetered check itself anyway — the very thing WorkManager was brought in
   for).
3. **Two schedulers, two policies, two stores.** Interval and constraints would live in two places;
   `Auto-sync`/per-account toggles (which periodic syncs honour: "These periodic syncs honor the
   'syncAutomatically' and 'masterSyncAutomatically' settings", `ContentResolver.addPeriodicSync`
   docs) only govern the sync-framework half, so the app must read
   `getSyncAutomatically`/`getMasterSyncAutomatically` itself for the WorkManager half.
4. **Duplicate-run protection** needs `enqueueUniqueWork(...)`, because `requestSync` can be called
   repeatedly by other apps and by the system.
5. **Debuggability**: `dumpsys content` (sync history, pending syncs) covers one half; the WorkManager
   database the other.

[INFERENCE] Verdict: the hybrid buys one native constraint and costs a false sync status, a watchdog
interaction bug and doubled bookkeeping. If the work must run in a worker, the simpler shape is to
make the *worker* the whole sync path and drop the sync adapter — but then the account's sync toggle,
the `Auto-sync` interaction and "sync now" from Contacts/Calendar all disappear, which is the
integration this app exists to provide.

---

## 5. OEM battery-management reality

Platform-level first, because it is what all OEM behaviour builds on:

* In Doze the system "Suspends network access. … Doesn't let sync adapters run. Doesn't let
  JobScheduler run. WorkManager uses JobScheduler internally, so WorkManager tasks don't run."
  (`https://developer.android.com/training/monitoring-device-state/doze-standby`).
* AOSP adds the definitive note for sync: "When the device is dozing, no sync operations will be
  executed." (`services/core/java/com/android/server/content/SyncManager.md`.)
* The user-visible exemption is the battery-optimisation allow-list: Settings → Battery → Battery
  optimization, checked with `PowerManager.isIgnoringBatteryOptimizations()`, requested with
  `Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` +
  `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (API 23, protection level `normal`).
  "An app that is partially exempt can use the network and hold partial wake locks during Doze and App
  Standby. However, other restrictions still apply to the app… For example, the app's jobs and syncs
  are deferred on API level 23 and below, and its regular AlarmManager alarms don't fire."
  Correspondence in AOSP: `DeviceIdleJobsController` — "When device is dozing, set constraint for all
  jobs, **except whitelisted apps**, as not satisfied"
  (`apex/jobscheduler/service/java/com/android/server/job/controllers/DeviceIdleJobsController.java`).
  So on API 24+ an allow-listed app's JobScheduler jobs — which is what both mechanisms use — run
  during Doze.
* The API reference is explicit that this is not a normal thing to ask for: "Note: most applications
  should not use this; there are many facilities provided by the platform for applications to operate
  correctly in the various power saving modes."
  (`https://developer.android.com/reference/android/provider/Settings#ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`)
* **Play policy risk, which the spec must plan around**: "Google Play policies prohibit apps from
  requesting direct exemption from Power Management features—Doze and App Standby—in Android 6.0 and
  above unless the core function of the app is adversely affected." The acceptable-use table on the
  same page lists only instant messaging/chat/calling (where FCM cannot be used), safety apps,
  task-automation apps and peripheral-companion apps needing to give a peripheral internet access;
  "App only needs to connect to a peripheral device periodically to sync" is explicitly *Not
  Acceptable* (`https://developer.android.com/training/monitoring-device-state/doze-standby`).
  [INFERENCE] A periodic CalDAV/CardDAV sync app does not map onto any listed case; assume the
  exemption will not be granted and design so that its absence degrades gracefully rather than breaks.

OEM layers that survive on top of that (first-party vendor documentation):

* **Samsung** — "Samsung's application management … applies either the sleeping or deep sleeping
  modes through data analysis". Sleeping apps (≈3 days unused): "A bucket restriction applies … and
  features such as Job, Alarm, and Foreground-service are restricted." Deep-sleeping apps (≈16 days):
  "only become active when the user opens them, and become inactive when they go into the background.
  Inactive applications can't perform any activities, including notifications or updates." User
  exemption: Settings > Device care > Battery > Background usage limits → Never sleeping apps; Samsung
  also documents a deeplink (`com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY`, package
  `com.samsung.android.lool`, `activity_type=2`). "Since One UI 6.0, foreground services of apps
  targeting Android 14 will be guaranteed to work as intended so long as they are developed according
  to Android's new foreground service API policy."
  `https://developer.samsung.com/mobile/app-management.html`
* **Xiaomi** — autostart is off by default and is a user decision, not an app permission: "手机将默认
  应用不可自启动，且应用自启动功能需告知用户。如有需要，用户可以自行设置应用允许应用自启动" ("the phone
  makes apps non-autostartable by default, and the autostart capability must be disclosed to the user;
  if needed, the user can allow it themselves").
  `https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1624` (updated 2024-09-25)
* **Huawei** — for an app that "may have been cleared by your phone when it was running in the
  background": enable "Run in background" via Launch (turn off Manage automatically; enable
  Auto-launch, Secondary launch, Run in background); enable "Ignore battery optimization" (Settings →
  Battery optimization → All apps → the app → Don't allow); disable power-saving mode; lock the app in
  Recents. `https://consumer.huawei.com/en/support/content/en-us00420068/`

[INFERENCE] Practical reading: aggressive OEM managers suppress *any* background scheduling — the
mechanism choice does not change exposure — so the app needs (a) a first-run onboarding explanation
with links into each vendor's exemption UI, and (b) a manual "sync now" affordance that must work from
the foreground, and (c) graceful behaviour when sync silently stops. A device test matrix is the only
way to know what actually survives on a given handset.

---

## 6. Manifest and resource surface for the recommended path (sync adapter, no WorkManager)

All element/attribute names below are from AOSP sources, not from prose: the sync framework discovers
the adapter via `SyncAdaptersCache` (`core/java/android/content/SyncAdaptersCache.java`), which defines
`SERVICE_INTERFACE = "android.content.SyncAdapter"`, `SERVICE_META_DATA = "android.content.SyncAdapter"`
and `ATTRIBUTES_NAME = "sync-adapter"`, and parses these attributes (via `attrs.xml`'s `SyncAdapter`
styleable): `contentAuthority`, `accountType`, `userVisible`, `supportsUploading`, `allowParallelSyncs`,
`isAlwaysSyncable`, `settingsActivity`. The authenticator side is named by
`android.accounts.AccountManager`'s `ACTION_AUTHENTICATOR_INTENT` / `AUTHENTICATOR_META_DATA_NAME`
(`core/java/android/accounts/AccountManager.java`) with `AUTHENTICATOR_ATTRIBUTES_NAME =
"account-authenticator"` and the `AccountAuthenticator` styleable in `attrs.xml` (`accountType`,
`label`, `icon`, `smallIcon`, `accountPreferences`, `customTokens`).

**Doc bug to avoid in the spec:** the `AbstractThreadedSyncAdapter` javadoc's sample XML lists
`android:syncAdapterSettingsAction`. No such attribute exists in current AOSP `attrs.xml` (the
`SyncAdapter` styleable has `settingsActivity`), and it did not exist in `android-4.4_r1` either; the
parser reads `SyncAdapter_settingsActivity`. Use **`android:settingsActivity`**.

### 6.1 `AndroidManifest.xml`

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.READ_SYNC_SETTINGS" />   <!-- getIsSyncable(), getSyncAutomatically() -->
    <uses-permission android:name="android.permission.WRITE_SYNC_SETTINGS" />  <!-- addPeriodicSync(), setSyncAutomatically(), setIsSyncable(); NOT needed for requestSync() -->
    <uses-permission android:name="android.permission.READ_SYNC_STATS" />      <!-- isSyncActive(), isSyncPending(), getCurrentSyncs() -->
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" /><!-- required for persisted sync jobs -->
    <uses-permission android:name="android.permission.READ_CONTACTS" />
    <uses-permission android:name="android.permission.WRITE_CONTACTS" />
    <uses-permission android:name="android.permission.READ_CALENDAR" />
    <uses-permission android:name="android.permission.WRITE_CALENDAR" />

    <application android:label="@string/app_name">

        <!-- authenticator: needed to create/hold the account the sync framework keys on -->
        <service
            android:name=".auth.AuthenticatorService"
            android:exported="true">
            <intent-filter>
                <action android:name="android.accounts.AccountAuthenticator" />
            </intent-filter>
            <meta-data
                android:name="android.accounts.AccountAuthenticator"
                android:resource="@xml/authenticator" />
        </service>

        <!-- sync adapter, Contacts authority -->
        <service
            android:name=".sync.ContactsSyncService"
            android:exported="false"
            android:process=":sync">
            <intent-filter>
                <action android:name="android.content.SyncAdapter" />
            </intent-filter>
            <meta-data
                android:name="android.content.SyncAdapter"
                android:resource="@xml/syncadapter_contacts" />
            <!-- optional, Contacts-app integration only -->
            <meta-data
                android:name="android.provider.CONTACTS_STRUCTURE"
                android:resource="@xml/contacts" />
        </service>

        <!-- sync adapter, Calendar authority: a separate service, see note below -->
        <service
            android:name=".sync.CalendarSyncService"
            android:exported="false"
            android:process=":sync">
            <intent-filter>
                <action android:name="android.content.SyncAdapter" />
            </intent-filter>
            <meta-data
                android:name="android.content.SyncAdapter"
                android:resource="@xml/syncadapter_calendar" />
        </service>
    </application>
</manifest>
```

Notes, each with its source:

* `android:exported="false"` is what Google's guide recommends for the sync service
  ("The attribute `android:exported="false"` allows only your app and the system to access the
  Service", `.../creating-sync-adapter`), and it works because the sync framework binds from
  `system_server`: `ActivityManager.checkComponentPermission` grants access to unexported components
  when `canAccessUnexportedComponents(uid)` — i.e. root or `SYSTEM_UID`. The guide also recommends
  `android:process=":sync"`. The AOSP sample (`platform/development/samples/SampleSyncAdapter/AndroidManifest.xml`)
  uses `android:exported="true"`; both work. `android:exported` **must** be declared explicitly for
  components with intent filters, or the app cannot be installed on Android 12+:
  "Warning: If an activity, service, or broadcast receiver uses intent filters and doesn't have an
  explicitly-declared value for android:exported, your app can't be installed on a device that runs
  Android 12 or higher."
  `https://developer.android.com/about/versions/12/behavior-changes-12`
* Nothing else is needed on the services: `BIND_SYNC_ADAPTER` and `BIND_ACCOUNT_AUTHENTICATOR` do not
  exist as platform permissions (no such entries in `core/res/AndroidManifest.xml`), and the
  authenticator service needs no `android:permission`. The authenticator service may also be
  `android:exported="false"` (the Accounts system binds it from `system_server` under the same
  unexported-component rule); `android:exported="true"` — as in the AOSP sample — is the safe default.
* `READ_SYNC_STATS` is required for `isSyncActive()`, `isSyncPending()` and `getCurrentSyncs()` (the
  reference states it per method). The per-authority `ContentResolver.getSyncStatus()` is
  `@hide`/`@UnsupportedAppUsage`, so an ordinary app cannot read the framework's last-sync record;
  the app must keep its own last-run/error state for its UI.
* **Do not declare a stub content provider.** The framework's stub-provider lesson exists only for
  apps that have no provider of their own; here the authorities are the platform's `com.android.contacts`
  and `com.android.calendar`, which already resolve.
* **One authority per service.** `SyncAdaptersCache extends RegisteredServicesCache` and is
  constructed with the single metadata key `"android.content.SyncAdapter"`; discovery is
  `queryIntentServicesAsUser(new Intent("android.content.SyncAdapter"))` and, per resolved service,
  `si.loadXmlMetaData(pm, mMetaDataName)` (`core/java/android/content/pm/RegisteredServicesCache.java`).
  A component's metadata is a single keyed bundle, so two `<meta-data android:name="android.content.SyncAdapter">`
  elements on one service cannot both be read, and a `<sync-adapter>` element names exactly one
  `contentAuthority`. Contacts + Calendar therefore means **two services** (as in the snippet above),
  sharing one adapter implementation and one `:sync` process.
* `AUTHENTICATE_ACCOUNTS`, `MANAGE_ACCOUNTS` and `USE_CREDENTIALS` should **not** be requested: they
  are `@hide`, `android:protectionLevel="normal"`, `android:permissionFlags="removed"` in current
  `core/res/AndroidManifest.xml` and no longer appear in the public permission reference at all. The
  2024 guide text still lists `AUTHENTICATE_ACCOUNTS` — stale. `GET_ACCOUNTS` is also unnecessary for
  the app's own account: "Beginning with Android 6.0 (API level 23), if an app shares the signature of
  the authenticator that manages an account, it does not need GET_ACCOUNTS permission to read
  information about that account."

### 6.2 `res/xml/authenticator.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<account-authenticator xmlns:android="http://schemas.android.com/apk/res/android"
    android:accountType="<reverse-DNS account type>"
    android:icon="@mipmap/ic_launcher"
    android:smallIcon="@mipmap/ic_launcher"
    android:label="@string/account_type_label"
    android:accountPreferences="@xml/account_preferences" />
```

`android:accountPreferences` (a `PreferenceScreen` XML shown inside the account's settings page) is
the documented way to put app-specific settings into Settings → account → *our* account:
AOSP Settings `AccountTypePreferenceLoader.addPreferencesForType()` inflates exactly
`AuthenticatorDescription.accountPreferencesId` out of the authenticator XML.
`android:customTokens` (boolean) is available for authenticators that manage their own token storage.
Attributes verified in `attrs.xml` (`AccountAuthenticator` styleable) and the guide
(`.../creating-authenticator`).

### 6.3 `res/xml/syncadapter_contacts.xml` / `syncadapter_calendar.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<sync-adapter xmlns:android="http://schemas.android.com/apk/res/android"
    android:contentAuthority="com.android.contacts"
    android:accountType="<same value as authenticator.xml>"
    android:userVisible="true"
    android:supportsUploading="false"
    android:allowParallelSyncs="false"
    android:isAlwaysSyncable="true"
    android:settingsActivity="<fully.qualified.SettingsActivity in the same package>" />
```

* `contentAuthority` for calendar is `com.android.calendar`.
* Read-only v1 ⇒ `android:supportsUploading="false"` (the framework will not ask for upload-only
  syncs); `isAlwaysSyncable="true"` means the framework initialises `isSyncable` to 1 for each new
  account instead of issuing an initialisation sync.
* `android:settingsActivity` must live in the same package ("The activity must live in the same
  package as the sync adapter", `AbstractThreadedSyncAdapter` javadoc).
* `userVisible="true"` is what creates the per-authority sync switches (§2.2). Set it `false` if the
  app's own UI is the only intended control surface — the account entry itself will still appear.

### 6.4 `res/xml/contacts.xml` (optional, Contacts-app integration only)

Declared as `<meta-data android:name="android.provider.CONTACTS_STRUCTURE" android:resource="@xml/contacts"/>`
on the sync service. Metadata name verified in AOSP Contacts app source
(`packages/apps/Contacts/src/com/android/contacts/model/account/ExternalAccountType.java`):

```java
private static final String[] METADATA_CONTACTS_NAMES = new String[] {
        "android.provider.ALTERNATE_CONTACTS_STRUCTURE",
        "android.provider.CONTACTS_STRUCTURE"
};
```

Root element and attributes (documented in full at
`https://developer.android.com/identity/providers/contacts-provider`, "<ContactsAccountType> element"
and "<ContactsDataKind> element"): `<ContactsAccountType>` with optional `inviteContactActivity`,
`inviteContactActionLabel`, `viewContactNotifyService`, `viewGroupActivity`, `viewGroupActionLabel`,
`viewStreamItemActivity`, `viewStreamItemPhotoActivity` (note: no `android:` prefix on these), and
zero or more `<ContactsDataKind android:mimeType="…" android:icon="…" android:summaryColumn="…"
android:detailColumn="…"/>` children for custom `ContactsContract.Data` MIME types.
For a plain CardDAV→Contacts sync that uses only standard MIME types, this file is not required.

### 6.5 There is no `calendar.xml`

The Calendar provider guide's sync-adapter section
(`https://developer.android.com/identity/providers/calendar-provider`) documents no
calendar-specific metadata resource; a calendar sync adapter is declared with the generic
`<sync-adapter>` (`android:contentAuthority="com.android.calendar"`) and does its writes with
`CALLER_IS_SYNCADAPTER=true` + `Calendars.ACCOUNT_NAME` + `Calendars.ACCOUNT_TYPE` query parameters.
[INFERENCE] "calendar.xml" in the ticket's scope list does not exist as a platform resource; the only
provider-specific metadata file is Contacts' `contacts.xml`.

### 6.6 Account creation (not manifest, but required for this design)

The system only invokes the adapter for an account that exists:
`AccountManager.addAccountExplicitly(account, password, userData)` from the app's own configuration
flow, with the account type matching `authenticator.xml`/`syncadapter.xml`. Per-account DAV settings
(server URL, headers, chosen client certificate alias) belong in the app's own storage keyed by the
account, or in `AccountManager.setUserData` for small values — never in the sync extras bundle, which
"can be queried by applications with the correct permissions using
`ContentResolver.getPeriodicSyncs(Account, String)`, so no sensitive data should be transferred here"
(`ContentResolver.addPeriodicSync` docs).

---

## 7. Can the two nice-to-haves be expressed?

**User-chosen interval: yes, natively.** `SyncRequest.Builder.syncPeriodic(pollFrequency, beforeSeconds)`
(API 19) or `ContentResolver.addPeriodicSync(account, authority, extras, pollFrequencySeconds)`
(API 8). Both are documented and both are clamped: `ContentService.addPeriodicSync` calls
`clampPeriod(pollFrequency)` which raises anything below `JobInfo.getMinPeriodMillis() / 1000` and logs
"Requested poll frequency … being rounded up". The public doc states it plainly: "On Android API level
24 and above, a minimum interval of 15 minutes is enforced. On previous versions, the minimum interval
is 1 hour." Also documented on the same page: periodic syncs "honor the 'syncAutomatically' and
'masterSyncAutomatically' settings", "it may take longer for it to actually be started if other syncs
are ahead of it in the sync operation queue. This means that the actual start time may drift", and
several extras are rejected for periodic syncs (`SYNC_EXTRAS_DO_NOT_RETRY`, `SYNC_EXTRAS_IGNORE_BACKOFF`,
`SYNC_EXTRAS_IGNORE_SETTINGS`, `SYNC_EXTRAS_INITIALIZE`, `SYNC_EXTRAS_FORCE`, `SYNC_EXTRAS_EXPEDITED`,
`SYNC_EXTRAS_MANUAL`, `SYNC_EXTRAS_SCHEDULE_AS_EXPEDITED_JOB`).
[INFERENCE] Spec consequence: expose the interval as a duration the user picks, but state the 15-minute
floor in the UI and clamp before calling; do not promise wall-clock precision.

**"Only on unmetered": no supported API — implement it in the adapter.** There is no public sync extra
for network metering. What exists is hidden: `ContentResolver.SYNC_EXTRAS_DISALLOW_METERED =
"allow_metered"` is `/** {@hide} */` in `ContentResolver.java`, and `SyncOperation.isNotAllowedOnMetered()`
returns `mImmutableExtras.getBoolean(SYNC_EXTRAS_DISALLOW_METERED, false)`, which
`SyncManager.scheduleSyncOperationH` turns into `JobInfo.NETWORK_TYPE_UNMETERED`; extras survive as
`mImmutableExtras = new Bundle(extras)`, so passing the literal string today would work. Note the
name/value mismatch ("allow_metered" ⊃ disallow metered) — evidence that this was never a supported
contract. Recommendation for the spec:

1. Preferred: inside `onPerformSync`, inspect the active network
   (`ConnectivityManager.getNetworkCapabilities(...)` → `NET_CAPABILITY_NOT_METERED`). If metered and
   the user asked for unmetered-only, set `syncResult.delayUntil = (System.currentTimeMillis()/1000) + N`
   and return without doing work. `SyncResult.delayUntil` is documented for exactly this: "Used to
   indicate to the SyncManager that future sync requests that match the request's Account and authority
   should be delayed until a time in seconds since Java epoch… By default, when a sync fails, the system
   retries later with an exponential back-off with the system default initial delay time, which always
   wins over delayUntil." Two caveats to record: the effective delay can be longer than the value you
   ask for, and the app must surface "deferred until an unmetered network" in its own UI, because the
   account's sync-status UI has no notion of "deliberately skipped".
2. Do not depend on `"allow_metered"`: it is not in the SDK, so it may change without notice;
   if it is used, it should be an additive optimisation (the system will not even schedule the job on
   metered networks) with the in-app gate as the real guarantee.
3. If the transfer is ever moved to a WorkManager worker, `Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED)`
   expresses it declaratively (`https://developer.android.com/reference/androidx/work/NetworkType`).

**Charging** (not requested, but relevant): `SYNC_EXTRAS_REQUIRE_CHARGING` / `SyncRequest.Builder.setRequiresCharging(true)`
(API 24) — "the sync request will be scheduled only when the device is plugged in"; mutually exclusive
with `SYNC_EXTRAS_SCHEDULE_AS_EXPEDITED_JOB`.

**Long initial sync**: two documented levers instead of a second scheduler —
`SyncResult.fullSyncRequested` ("If set the SyncManager will request an immediate sync with the same
Account and authority (but empty extras Bundle) as was used in the sync request") to chain chunked
full-sync passes, and `SYNC_EXTRAS_MANUAL` + `SYNC_EXTRAS_EXPEDITED` for a user-initiated run from a
button. Both require the transfer to keep the UID's network traffic above 10 bytes per 60 s window.

---

## 8. Android version floor the choice implies

* Sync-framework minimum: API 5 for the framework; **API 19** for `SyncRequest`/`syncPeriodic`, which
  is the API to use for a flex-window periodic schedule.
* **API 24**: `SYNC_EXTRAS_REQUIRE_CHARGING` / `setRequiresCharging` (API 24), and the 15-minute
  periodic clamp (before API 24 it is 1 hour).
* **API 23**: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` / `isIgnoringBatteryOptimizations` (API 23), and
  WorkManager 2.11+'s minSdk (API 23).
* **API 21**: `JobInfo.Builder.setPersisted` (reboot survival) — below that, persisted jobs are not
  available.
* **Recommendation: `minSdk 24` (Android 7.0).** It is the lowest version at which every mechanism in
  this design behaves as documented (aggregated periodic clamp, charging constraint, exemption API,
  persisted jobs) and it removes a whole class of pre-7.0 divergence from the spec. If the project
  decides to support API 23, the only documented loss is the charging constraint and the shorter
  periodic clamp.

---

## 9. Not settled by primary sources — needs a device test

1. **What actually survives on an OEM handset** (Samsung sleeping/deep-sleeping, Xiaomi autostart
   off by default, Huawei/EMUI launch manager) with and without the battery-optimisation exemption.
   What would settle it: install the app on one device per OEM, schedule a short periodic sync
   (minutes), and observe over ≥24 h idle whether `dumpsys content` shows syncs and the adapter runs.
2. **Whether the 60-second no-progress watchdog bites in practice** on a large initial sync
   (a collection large enough that a local write phase exceeds 60 s). What would settle it: run the
   initial sync against a large collection with `adb shell dumpsys content` logging and watch for
   "Detected sync making no progress".
3. **Whether an OEM build re-introduced the pre-13 per-sync timeout** the class docs still describe
   (AOSP main and Android 13 have no `MAX_TIME_PER_SYNC`). Same device test as (2).
4. **Whether `"allow_metered"` still maps to `NETWORK_TYPE_UNMETERED` on the target OEM/API levels.**
   What would settle it: `adb shell dumpsys jobscheduler` / `dumpsys content` on a metered network and
   check the sync job's required network type. Not required for v1 if the in-app gate is the guarantee.
5. **Whether Play review accepts a battery-optimisation exemption for this app.** What would settle
   it: the Play Console declaration flow; per the doze-page table, assume "no" and design for graceful
   degradation.
