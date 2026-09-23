# Screenshots

Every screen this app publishes names the server it is talking to. The Account card carries the
Account's label, a Collection's screen carries its URL, and the log carries the Origin on every line.
A screenshot taken against a real server therefore publishes that server — and deleting the file
afterwards does not take it out of git history or off a CDN.

So screenshots are produced one way only:

```sh
python3 tools/screenshots/capture.py --apk app/build/outputs/apk/debug/app-debug.apk
```

That stands up a DAV server answering as `dav.example.com`, teaches the emulator to resolve and trust
it, installs the build, configures an Account, drives the screens, and writes the images. Before each
image is written it reads what is on the screen and **refuses to save if anything named there is not
the demo host**. A screenshot of a real server is not something this path can produce.

## What it writes

| Consumer | Path | Size |
| --- | --- | --- |
| F-Droid listing | `fastlane/metadata/android/en-US/images/phoneScreenshots/` | device resolution |
| README | `docs/shots/` | a third |
| The site | `--site-dir <gh-pages checkout>/demo` | a half, as WebP |

`last-capture.json` records the version the set was taken on and a digest per image, so a published
set that has fallen behind a UI change is obvious.

Replacing the site's images: **use a new path**, or purge the CDN. Cloudflare serves cached copies
under the old filenames, which is how withdrawn screenshots stayed live after they were removed.

## Requirements

- `adb`, `docker`, `openssl`, and Python with Pillow.
- An emulator that `adb root` succeeds on: a **Google APIs** system image, not Google Play.

## Proving the guard

A guard that has never fired is not known to work:

```sh
python3 tools/screenshots/capture.py --apk ... --prove-refusal
```

That configures the Account under a label naming another host and requires the run to fail with no
image written. It exits 0 when it was refused, non-zero if anything was saved.

## The fixture

`fixture/manifest.json` describes the Account, its Collections and the state each is left in: the
address book selected and writable, one calendar selected and read-only, one not selected — which is
what makes the Account card show all three row states. `fixture/contacts` and the calendar
directories hold the items, restored on every run so two captures a month apart produce the same
screens.

One contact carries a photo and an `X-` property this app does not map. That is deliberate: the
device checks this project relies on instead of unit tests — write-back, the photo baseline, refusal
behaviour — need exactly that, and each of them previously set up a server by hand.

## Why the emulator preparation looks like that

Three facts, each of which presents as "Couldn't reach the server" and nothing else when missed:

- The app declares no network security configuration, so it trusts the **system** store only. A
  user-installed CA is not enough.
- On current API levels the runtime store is inside the Conscrypt APEX, and an app does not share the
  mount namespace of the shell that mounts over it. The bind must happen in **init's** namespace, and
  the framework is restarted so everything inherits it.
- The files behind those mounts must carry a **system SELinux label**. Without it the resolver and
  the trust store ignore them silently.

## Adding a screen

Add it to `SCREENS`, and drive it in `capture_all` before calling `capture()`. The guard needs no
teaching: it reads the accessibility tree, so a new screen that shows a host is covered already.
