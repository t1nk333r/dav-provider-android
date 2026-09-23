# Submitting DAVKeep to F-Droid

## Why the app already qualifies

* GPL-3.0-or-later, licence text in the repository root
* no proprietary dependencies: dav4jvm (MPL-2.0), ical4j (BSD-3), ez-vcard (BSD-2),
  OkHttp/Ktor/commons/AndroidX (Apache-2.0). No Google Play Services, no Firebase
* no ads, no analytics, no crash reporting, no update check: the app opens connections
  only to the servers the user configures
* builds from a clean checkout with no secrets. `keystore.properties` is gitignored, and
  without it there is no release signingConfig, so `assembleRelease` produces an unsigned
  APK for F-Droid to sign

No anti-feature is expected to apply. `TetheredNet` does not: the server is whatever the
user points the app at, including a self-hosted one.

## The submission

1. Fork `gitlab.com/fdroid/fdroiddata`, branch per app.
2. Copy `app.davkeep.yml` here to `metadata/app.davkeep.yml`, setting `commit` to the
   release tag and `CurrentVersion`/`CurrentVersionCode` to that release.
3. `fdroid readmeta && fdroid rewritemeta app.davkeep && fdroid lint app.davkeep`.
4. Test the build: `fdroid build -v -l app.davkeep`.
5. Open a merge request using the *App inclusion* template.

Listing text and images come from `fastlane/metadata/android/en-US/` in this repository;
F-Droid reads them from the tagged commit, so changes there ship with a release.

## What it means for users

F-Droid signs with its own key. Someone who installs from F-Droid cannot update from the
GitHub release, and vice versa — switching channels needs an uninstall, which removes the
accounts. Publishing our own APK on top of F-Droid would require reproducible builds
(`Binaries` plus `AllowedAPKSigningKeys`), which is a separate piece of work.
