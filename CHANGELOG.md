# Changelog

All notable changes to OpenThumb. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
versioning is independent of upstream (OpenMinis) since 1.0.0.

## [1.0.0] — 2026-07-29

OpenThumb's own version line begins. The fork's user-visible identity is now
fully decoupled from upstream.

### Added
- **Notification trigger hardening** — global quiet hours, per-rule active
  windows, a test-fire button in the rule editor, and a capped run history
  (the trigger counterpart to scheduled tasks' run records).
- **Optional sync (BYO backend)** — `tools/sync-worker`, a Cloudflare Worker
  you deploy to your own account, plus the app-side client (Settings → Sync,
  off by default). v1 pushes trigger rules, trigger run history and scheduled
  tasks.
- **Stats instrumentation (local only)** — schema-first structured events in
  `docs/specs/stats-schema.md`, written to an on-device file that goes
  nowhere. The contract for any future opt-in data tier.
- **Fleet tool depth** — per-device health (RPC + battery), result collection
  (`run`/`collect` writing per-device JSON), and a JSONL job queue
  (`enqueue`/`jobs`/`drain`).
- **Store readiness** — six real device screenshots in the listing and on the
  landing page, Play full description rewritten around triggers, store
  changelog, `openthumb-*` artifact names.

### Changed
- **Version line** — OpenThumb versions independently from 1.0.0
  (upstream base: OpenMinis 0.20-preview).
- **Identity sweep** — debug-server metadata, update checker, About screen
  and bug-report links all point at this repository, not upstream. Upstream
  is credited in-app (GPL-3.0).

### Fixed
- Update checker polled upstream releases whose APKs could never install over
  this package; it now reads this repository's releases.

## [0.1.0] — 2026-07-28

First public release of the fork.

### Added
- **Notification trigger engine** — rules match incoming notifications by app
  package and text and run the agent headlessly on the scheduled-task path,
  with per-rule cooldown, self-notification and group-summary guards, and a
  two-run in-flight cap. Management UI under Scheduled tasks → bell icon.
- **Fleet tool** — `openthumb-fleet` fans JSON-RPC calls out across every
  attached device over adb port-forwards.
- **Play submission prep** — signed release build from environment keystore,
  prominent accessibility disclosure, honest `foregroundServiceType`,
  PRIVACY.md, store graphics, F-Droid metadata, submission checklist.
- **Landing page** (GitHub Pages) and CI (APK build + ShellCheck on push).

### Fixed
- **Android 8/9 startup crash** — `Environment.isExternalStorageLegacy()`
  (API 29) called unguarded on the pre-R branch killed every API 26–28
  device in `Application.onCreate`. Verified on a Galaxy Note8 (Android 9);
  contributed upstream as a patch on OpenMinis#118.
