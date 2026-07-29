# Changelog

All notable changes to OpenThumb. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
versioning is independent of upstream (OpenMinis) since 1.0.0.

## [1.0.1] — 2026-07-29

A safety and correctness patch. **Anyone who installed the v1.0.0 APK should
replace it**: that release attached a debug build, and debug builds run the
JSON-RPC debug server.

### Fixed
- **Releases no longer ship a debug APK.** v1.0.0's only asset was
  `OpenThumb-1.0.0-debug.apk`. Debug builds start the debug server (gated on
  `BuildConfig.DEBUG`), which binds `0.0.0.0:5321` and waives the token check
  for loopback callers — so any app on the phone with INTERNET permission
  could call `provider.export` and read the stored API keys. CI now assembles
  the release variant too, and a new `release-apk` workflow attaches it to
  published releases.
- **Non-ASCII request bodies no longer hang the debug server.** The body was
  read into a `CharArray` sized by `Content-Length`, which counts bytes: one
  Korean character is three UTF-8 bytes, so the read never reached the count,
  blocked, and died on the 30s socket timeout — the caller saw an empty
  response with no error. Every Korean `chat.prompt` failed while English ones
  worked. Headers now parse off the raw stream and the body is read by byte
  count. Verified on a Galaxy Note8 (Android 9).
- **Provider connectivity test stops failing valid endpoints.** The probe
  re-derived a `/v1` suffix that `effectiveBaseURL` had already applied from
  the instance's `appendV1Suffix` setting, so an OpenAI-compatible root that
  ends in something else (z.ai's `/paas/v4`) was probed at `…/v4/v1/models`
  and reported unreachable while real chat traffic to it worked.

### Added
- **Agent replies into the conversation a notification came from.** A trigger
  rule can opt in with `replyToNotification` (default off); the agent's answer
  is posted through the notification's own quick-reply action, so any
  messenger that ships one — KakaoTalk, SMS, LINE, Telegram — becomes a
  two-way channel to the on-device agent. Public Android notification contract
  only: no accessibility injection, no per-app protocol.
- Regression tests for the request-body parsing and the provider probe URL,
  plus a round-trip test for the new rule field.

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
