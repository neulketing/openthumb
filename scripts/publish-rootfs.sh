#!/usr/bin/env bash
# Build the rootfs image, publish it as its own release, and print the digest
# the app has to carry.
#
#   scripts/publish-rootfs.sh            # build + checksum only
#   scripts/publish-rootfs.sh --publish  # also upload and create the release
#
# The image is a release of its own rather than an app asset, so a new app build
# does not re-upload 14 MB and re-cutting the image does not need an app
# release. RootfsSource.ROOTFS_TAG and ROOTFS_FILE name it; ROOTFS_SHA256 is
# what this prints. All three have to agree or the download is rejected — which
# is the point, so this script refuses to publish an image whose digest does not
# match the one compiled into the app.
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
SRC="$ROOT/src/android/app/src/main/java/com/fug/openthumb/sandbox/RootfsSource.kt"
OUT_DIR="$ROOT/build/rootfs"

# Reads a Kotlin string constant whether its value sits on the declaration line
# or wraps to the next one. Constraining the source's formatting to keep a sed
# one-liner happy is the wrong way round.
read_const() {
  awk -v key="$1" '
    index($0, "const val " key " =") {
      line = $0
      if (line !~ /"/) { getline; line = $0 }
      if (match(line, /"[^"]*"/)) {
        print substr(line, RSTART + 1, RLENGTH - 2)
        exit
      }
    }
  ' "$SRC"
}
TAG=$(read_const ROOTFS_TAG)
FILE=$(read_const ROOTFS_FILE)
WANT=$(read_const ROOTFS_SHA256)
ALPINE_RELEASE=3.21.3

[ -n "$TAG" ] && [ -n "$FILE" ] || { echo "could not read ROOTFS_TAG/ROOTFS_FILE from $SRC" >&2; exit 1; }

mkdir -p "$OUT_DIR"
IMAGE="$OUT_DIR/$FILE"

if [ -f "$IMAGE" ]; then
  echo "reusing $IMAGE"
else
  echo "downloading alpine-minirootfs-${ALPINE_RELEASE}-aarch64..."
  curl -fSL -o "$IMAGE" \
    "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-${ALPINE_RELEASE}-aarch64.tar.gz"
  # python3 has to be inside the image: apk cannot install anything from the
  # app's PRoot sandbox on real hardware (docs/sandbox-python.md).
  python3 "$ROOT/scripts/rootfs_add_packages.py" "$IMAGE" python3
fi

SUM=$(shasum -a 256 "$IMAGE" | awk '{print $1}')
SIZE=$(du -h "$IMAGE" | cut -f1)

echo
echo "image   $IMAGE  ($SIZE)"
echo "tag     $TAG"
echo "sha256  $SUM"

if [ "$WANT" != "$SUM" ]; then
  echo
  echo "RootfsSource.kt says ROOTFS_SHA256 = \"${WANT:-<empty>}\""
  echo "Put the digest above in that constant, rebuild the app, then re-run."
  echo "An app that ships the wrong digest rejects its own image; one that ships"
  echo "an empty digest refuses to download at all, which is the safe default."
  [ "${1:-}" = "--publish" ] && { echo; echo "not publishing while they disagree."; exit 1; }
  exit 0
fi

echo "digest matches the app. good."
[ "${1:-}" = "--publish" ] || { echo; echo "re-run with --publish to upload."; exit 0; }

echo
if gh release view "$TAG" >/dev/null 2>&1; then
  echo "release $TAG exists — replacing the asset"
  gh release upload "$TAG" "$IMAGE" --clobber
else
  gh release create "$TAG" "$IMAGE" \
    --title "Alpine rootfs $ALPINE_RELEASE (aarch64, python3)" \
    --notes "Sandbox image fetched at first run by builds that do not bundle it —
F-Droid will not ship a tarball of precompiled binaries it cannot rebuild, and
the projects that do get in (Termux, UserLAnd) download theirs too.

Alpine minirootfs $ALPINE_RELEASE for aarch64, with python3 and its dependency
closure baked in because \`apk\` cannot install anything from inside the app's
PRoot sandbox on real hardware (see docs/sandbox-python.md).

    sha256  $SUM

The app verifies this digest before unpacking anything and discards a mismatch.
Not an app release: builds reference it by tag so the image and the app version
move independently."
fi
echo "published $TAG"
