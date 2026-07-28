# Distribution

Where OpenThumb ships, and what each channel still needs. Owner of all
accounts and keys: neulketing.

## Signing

Release APKs are signed with a 4096-bit RSA key held outside the repo.
`app/build.gradle.kts` picks it up from the environment
(`OPENTHUMB_KEYSTORE`, `OPENTHUMB_KEYSTORE_PASS`, `OPENTHUMB_KEY_ALIAS`);
without those set, release builds fall back to the debug key, so CI and fresh
clones keep working. **Losing the keystore means users cannot upgrade** —
back it up.

## GitHub Releases — live channel

Tag `vX.Y.Z`, attach the locally signed release APK, write notes that state
the upstream version the release is based on. The CI `app-debug` artifact is
for testing only (debug builds expose the local JSON-RPC server; release
builds do not).

## F-Droid — prepared, not yet submitted

Listing text lives in `fastlane/metadata/android/en-US/` (F-Droid reads that
layout from the repo). To submit: open a merge request against
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) with a build recipe.
Points reviewers will care about:

- All dependencies are FOSS; crash reporting (ACRA) writes local files only.
- The build compiles PRoot and the Alpine rootfs from source
  (`deps/build_proot.sh`, `scripts/prepare_android_sandbox.sh`) — the recipe
  must run both before gradle.
- F-Droid signs with its own key unless we opt into reproducible builds.

## Google Play — blocked on owner decisions

Technically ready: targetSdk 35, privacy policy (`PRIVACY.md`), no telemetry.
Still required before submission:

1. **Developer account** — $25 one-time, owner payment (L3).
2. **Accessibility declaration** — the app uses AccessibilityService for user-
   directed automation, not accessibility. Play requires a prominent
   disclosure in-app plus a declaration form; expect review friction and
   possible rejection. This is the highest-risk gate.
3. **Data Safety form** — answers follow PRIVACY.md: no collection, no
   sharing; user-provided keys sent only to user-chosen providers.
4. Review risk to document in the submission notes: the Alpine/PRoot sandbox
   executes code the user asks for, analogous to Termux/UserLAnd, which Play
   distributes.

## Apple App Store — not possible for this fork

The codebase is GPL-3.0 and the copyright belongs to the OpenMinis authors
and other third parties (iSH is GPLv3). GPL terms conflict with Apple's store
terms and only the copyright holder can grant an exception — upstream can ship
to the App Store; a fork cannot. An iOS store presence requires a separately
authored app.
