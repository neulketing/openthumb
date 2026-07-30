# Submitting OpenThumb to F-Droid

`com.fug.openthumb.yml` in this directory is the build recipe. It lives here
rather than only in a fork of fdroiddata so that it changes when the build
changes — a recipe kept only in someone else's repository rots the first time
the NDK version or a prebuild step moves, and the failure surfaces as a broken
F-Droid build weeks later.

## Why this is possible at all

F-Droid builds every app from source. OpenThumb's obstacle was never the Kotlin:
it was the ~14 MB Alpine image, a tarball of several hundred precompiled
executables that F-Droid cannot rebuild and will not ship. The projects that
clear the same bar — Termux, UserLAnd — download their rootfs at first run, and
as of 1.3.0 this one can too.

Which path a build takes is decided by what the APK contains: the bundled asset
is used when present, and the image is fetched when absent. The recipe simply
does not run `scripts/prepare_android_sandbox.sh`, so the asset is missing and
the app downloads. Verified on a Galaxy Note8 with an APK containing zero rootfs
assets: it fetched 14,589,928 bytes, checked the digest, unpacked, and left a
working `python3` in the sandbox.

## Submitting

```sh
git clone https://gitlab.com/<you>/fdroiddata   # fork of fdroid/fdroiddata
cp tools/fdroid/com.fug.openthumb.yml fdroiddata/metadata/
cd fdroiddata
fdroid readmeta && fdroid lint com.fug.openthumb
fdroid build com.fug.openthumb:31     # in their buildserver VM, not on your host
```

Open the merge request only after `fdroid build` succeeds locally. A recipe that
has never been built is the most common reason a submission sits unreviewed.

`fdroid lint` needs fdroiddata's own `config/` — its `categories.yml` and
`antiFeatures.yml` are what the allowed values are checked against, and without
them every category and anti-feature is reported invalid whatever you wrote.
Run it inside a real fdroiddata checkout, not a directory holding only the
recipe. Lint on this file already caught two things that would otherwise have
reached a reviewer: `Internet` is not a category F-Droid defines (`AI Chat` and
`Development` are), and continuation lines indented under a `Description`
bullet are read as literal leading spaces.

## What a reviewer will ask about, and the honest answers

**The rootfs is not built from source.** Correct. It is Alpine's own published
minirootfs with python3 added, fetched at first run and verified against a
SHA-256 compiled into the app; a mismatch is discarded rather than unpacked.
Building a distro image from source is outside what this project can do. The
digest is the security property that matters here: without it, whoever can
answer for that URL would choose which native binaries run in the sandbox.

**Why python3 is baked into the image rather than installed on demand.** `apk`
cannot install anything from inside the app's PRoot sandbox on real hardware —
measured on a Note8, its fetch returns IO ERROR while busybox wget pulls the
same mirror over HTTPS without trouble. Details and the ruled-out causes are in
`docs/sandbox-python.md`.

**PRoot.** Built from source by `deps/build_proot.sh` from the `deps/proot`
submodule, with talloc from its upstream tarball. The repository also contains a
fallback that unpacks a Termux `.deb`; the recipe never reaches it because
`build_proot.sh` runs first and produces the binary.

**NonFreeNet.** Declared. Nothing proprietary is linked or bundled, but the app
exists to drive a language model and every provider it can reach is a
proprietary service. Users supply their own credentials, the app ships none, and
a self-hosted OpenAI-compatible endpoint works — which is why this is the only
anti-feature listed.

## The signature question, before anyone installs from both places

F-Droid signs with its own key. An APK from F-Droid and one from this
repository's GitHub releases therefore carry different signing certificates, and
Android will not replace one with the other — a user who switches has to
uninstall first and loses their settings and provider credentials.

That is not a bug to be fixed at submission time; it is inherent to being in two
distribution channels. The way out is F-Droid's reproducible-builds path, where
they verify a build matches ours byte for byte and ship our signature instead.
That needs the build to actually be reproducible, which it is not yet — the
rootfs is repacked with `tar czf`, so it records mtimes. Worth doing later,
worth documenting now: **whichever channel a user installs from first is the one
they are committed to.**
