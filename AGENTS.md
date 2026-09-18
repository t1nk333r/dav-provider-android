# dav-provider-android

A standalone Android CalDAV/CardDAV sync provider with per-account custom HTTP
headers and client-certificate (mTLS) support. See `README.md` for the problem
statement and scope, and `prompt.md` (untracked, local only) for the environment
specifics and acceptance criteria.

## Versioning

**Every build that produces an APK bumps the version**, in the same change that made
the build worth producing. Both fields live in `app/build.gradle.kts`:

- `versionCode` increases by one, always. Android refuses to install an APK whose
  versionCode is lower than what is already on the device, so it only ever goes up —
  never renumber, never reuse.
- `versionName` follows `major.minor.patch`: patch for a build, minor when a milestone
  lands, major never so far.

The reason is the phone. Several builds a day land on a real device, and a version that
does not move makes "which build is this?" unanswerable from the device itself. The app
shows its own version at the bottom of the settings screen for exactly that check.

## Agent skills

### Issue tracker

Issues and specs live as GitHub issues in `t1nk333r/dav-provider-android`, via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

The five canonical triage roles, used verbatim as GitHub label names. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` plus `docs/adr/` at the repo root. See `docs/agents/domain.md`.
