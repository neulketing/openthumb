# Watching OpenMinis

OpenMinis is what this project forked from and what it competes with. Both ship
an AI agent that runs on the phone with a real Linux userspace, so anything they
add is either something to take or something to answer.

Run `scripts/upstream_digest.py` for the current picture;
`.github/workflows/upstream-watch.yml` runs it daily and files an issue when
the branch moves.

## Watch the branch, not the tags

Measured 2026-07-30: their newest release was `0.20-preview` from 13 July, and
their default branch had been pushed on the 25th. What landed in between was a
300-file publish. A release-only watcher — which is what this repo had — was
twelve days behind what they were actually shipping, and that gap is the whole
failure mode a fork has to avoid.

The digest also crosses every upstream commit with the files this fork has
rewritten, because those are simultaneously the merge conflicts and the places
where we made a deliberate choice that is now worth re-reading. On the first
run, all four new commits landed on files we had rewritten.

## What changed on 2026-07-25

**They open-sourced the app.** `feat: open-source the Minis app` publishes the
iOS and Android sources, the native dependency build system, the rootfs tooling
and the architecture specs, under GPLv3. 291 of its 300 files are `deps/` — the
build system for iSH, PRoot, FFmpeg, LAME and the Alpine rootfs. Their commit
message says binaries were deliberately not committed because they would carry
the build machine's paths in debug symbols.

**They stopped accepting pull requests.** From their CONTRIBUTING.md:

> This repository is a **mirror**. Development happens in a private tree and is
> published here on each release, so a pull request opened against this
> repository has nowhere to land.

Two consequences worth stating plainly:

- **We cannot contribute upstream any more.** The Android 8/9 startup crash fix
  this fork carries was offered upstream as a patch on OpenMinis#118; that route
  is now closed. Fixes we find stay ours, which means the divergence only grows.
- **We cannot see them working.** A mirror published per release shows squashed
  publishes, not the reasoning. The digest still catches every publish, but
  nobody should expect commit-level insight from it.

Their public tags read `0.x-preview` while the merges came from a `v1.10`
branch, so the version they use internally is ahead of what they publish.

## Where we are ahead, and where they are

Ahead of them, as of this writing: the trigger engine and notification replies,
the fleet tooling, an Android 8/9 startup crash fix, release signing and a
release pipeline that refuses to publish a debug-signed APK, `openthumb-fetch`
and its verdict engine, and python3 baked into the rootfs so the sandbox works
without a network install that cannot succeed on-device.

Behind them, honestly: they have iOS and we do not, they have 2,674 stars to our
zero, and their `deps/` build system is now public and may be better than the
copy we forked. Read their `deps/` changes before rewriting anything there.

## What to do with a digest

For each commit the digest marks with `!`:

- Does it fix something we also have? Take it.
- Does it change something we deliberately rewrote? Say why we keep ours, in
  the issue, so the reason survives to the next merge.
- Does it add a capability we lack? That is the benchmark — decide whether to
  match it or to stay narrow on purpose.

Unmarked commits touch files we never changed and can usually be taken as-is.
When the digest says divergence was not checked, that is not the same as no
conflicts — do not read the absence of a mark as safe.
