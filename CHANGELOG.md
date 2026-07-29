# Changelog

All notable changes to OpenThumb. Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
versioning is independent of upstream (OpenMinis) since 1.0.0.

Write what changed under `[Unreleased]`; `scripts/bump-version.sh` turns that
section into the version heading, the release notes and the store changelog.

## [Unreleased]

## [1.0.6] — 2026-07-30

### Added
- `scripts/bump-version.sh` — moves `versionName`, `versionCode`, the CHANGELOG
  heading, the F-Droid changelog and the version on the landing page together.
  They were four hand edits before, and fastlane still carried a changelog for
  versionCode 21 while the app shipped 26. `scripts/test-bump-version.sh`
  checks that all of them move.
- `.github/workflows/release.yml` — a tag push builds the signed APK, publishes
  the release with the notes read out of this file, and mails them. It checks
  that the tag agrees with the `versionName` it was cut from, and the tag is
  annotated because `git push --follow-tags` skips lightweight ones — a
  lightweight tag stays local and the release never fires.
  `release-apk.yml` and `release-announce.yml` are folded into it: both hung off
  `release: published`, which a release created with `GITHUB_TOKEN` does not
  raise, so automating release creation would have silently stopped both.
- The landing page links the changelog, and shows which version is current.

### Fixed
- **Release APK could be published signed with a throwaway key.** Without the
  keystore secrets `build.gradle.kts` falls back to the debug config, and CI
  runners generate a fresh debug key per run — so a published build could not
  be upgraded onto by anyone holding an earlier one. The workflow now signs
  from secrets when they exist and fails rather than attaching a debug-signed
  APK.

## [1.0.5] — 2026-07-29

Documentation, not code. Three things the fork already did but never said.

### Changed
- **README leads with the individual case.** The headline was "One host. Many
  phones", which described the fleet and left a person with one spare phone
  reading someone else's use case. Reviving a drawer phone and answering in the
  messenger a message came from are now stated first; the fleet follows.
- **Account sign-in is documented.** The app ships OAuth for Claude, OpenAI,
  Gemini, xAI, Kimi, OpenRouter and Antigravity — every user-facing document
  said "your own API keys" and mentioned OAuth zero times, so a reader had no
  way to know a subscription they already pay for can drive it.
- **The RPC auth note matches reality.** It still said "no authentication",
  which stopped being true in 1.0.4.
- Notification replies and the release-APK build are listed in the fork's
  delta table.

### Added
- `docs/recipes.md` — starter trigger rules with the exact package name, match
  and prompt for each: reply in KakaoTalk/SMS, meeting-hours auto-reply, filing
  delivery alerts, bank notifications into a ledger, and the morning digest
  that reads what the triggers collected. The rules screen opened on an empty
  list with a free-text prompt box and no example anywhere.

## [1.0.4] — 2026-07-29

Closes the last finding from the fork audit: the debug server's own auth.

### Security
- **The debug server now requires its token on every connection, loopback
  included.** Loopback was exempt on the assumption that 127.0.0.1 meant "adb
  forward, i.e. the developer's machine". It does not: any app on the phone
  holding INTERNET can open a loopback socket, and this RPC exposes
  `provider.export` (stored API keys), `debug.readFile` and sandbox command
  execution. adb keeps working because it can read the token with `run-as` —
  precisely the privilege a co-installed app lacks. Verified on a Note8: an
  unauthenticated `provider.export` over adb-forwarded loopback now returns
  401, and `openthumb-fleet status` still reports normally.
- **`Access-Control-Allow-Origin: *` removed.** It let any page the phone's
  browser loaded call this RPC cross-origin and read the response.

### Changed
- `openthumb-fleet` reads each device's token via `run-as` and sends it with
  every call, cached under `$OPENTHUMB_FLEET_DIR/tokens/`. Plain files rather
  than an associative array — macOS still ships bash 3.2, where `declare -A`
  does not exist and the lookup would trip `set -u`.

## [1.0.3] — 2026-07-29

### Fixed
- **ShellCheck CI is green again and still catches real defects.** 1.0.2 raised
  the severity to `warning` so the fleet script's caller-scope port bug (SC2318)
  would surface, but that also surfaced inherited SC2034/SC2154 noise from the
  upstream build scripts and turned the job red. The check now splits by
  ownership: `tools/` (this fork's) at `warning`, `scripts/` (inherited) at
  `error`. Both verified locally at exit 0.
- The discovered file list is no longer split blind — a path containing a
  space fails the step with a message instead of checking the wrong files.

## [1.0.2] — 2026-07-29

Follow-up to 1.0.1, from a full audit of the fork's own code. Two crash/DoS
paths and one that could put raw JSON into someone else's chat.

### Fixed
- **A failing trigger run no longer crashes the app.** The run coroutine had
  no catch and the scope no handler, so any exception — `startForegroundService`
  being refused on Android 12+, for one — propagated to the thread's default
  handler and killed the process, taking the notification listener with it.
- **Replies never send a raw payload.** If an assistant message failed to
  parse, the fallback returned the stored JSON string, which then went out as
  the reply body into the other person's chat. Unparseable payloads now send
  nothing.
- **A reply failure is recorded as a failure.** The run log marked a run `ok`
  whenever a session existed, even when the reply was never delivered — the
  user read "it ran" while the other side got silence.
- **Rules that reply skip notifications that cannot be replied to.**
  `canReply` existed but was never called, so a bank or delivery push burned a
  full agent run (up to ten minutes, real inference) and the rule's cooldown to
  produce an answer with nowhere to go.
- **The debug server survives a hostile request.** `Content-Length` was
  trusted without limit: one header claiming 2GB allocated a 2GB array, and the
  resulting `OutOfMemoryError` is an `Error` — the catch never saw it, the
  connection coroutine died, and it took the accept loop with it, killing the
  server until the app restarted. Bodies are now capped at 32MB and each
  connection runs under its own supervisor.
- **Upstream tag names can no longer run code in CI.** `upstream-watch`
  interpolated the upstream release tag directly into `run:` blocks on a job
  holding `contents: write`; the tag is now passed through the environment.
- **`ensure_forward` computes the right port.** `local serial="$1" index="$2"
  port=$((BASE_PORT + index))` read the *caller's* `index`, since bash expands
  every word before `local` runs. It worked only because every caller happens
  to use that variable name — renaming one would have sent all devices' RPC to
  the first phone while labelling the output with each device's serial.

### Changed
- CI runs the fork's unit tests (`trigger.*`, `debug.*`) — it never ran any.
  Three inherited suites fail on upstream code and are excluded by name;
  see `docs/upstream-test-baseline.md`.
- ShellCheck runs at `--severity=warning`. At `error` it filtered out the only
  real defect in the fleet script (SC2318, above).

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
