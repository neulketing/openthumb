#!/bin/bash
set -euo pipefail

# One-shot fork rebrand: renames the Android package and app label to ours.
# Run once, by hand, on a clean fork branch — not wired into the build.
#
# JNI symbols encode the package path (Java_com_openminis_app_...), so the cpp
# sources must be rewritten in lockstep or every native call fails to link.
# Change the three knobs below and re-run on a fresh checkout to pick a
# different name.

OLD_PKG="com.openminis.app"
NEW_PKG="com.neulketing.openblue"
NEW_APP_NAME="OpenBlue"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID="$REPO_ROOT/src/android"

OLD_PATH="${OLD_PKG//./\/}"          # com/openminis/app
NEW_PATH="${NEW_PKG//./\/}"          # com/neulketing/openblue
OLD_JNI="Java_${OLD_PKG//./_}"       # Java_com_openminis_app
NEW_JNI="Java_${NEW_PKG//./_}"       # Java_com_neulketing_openblue

echo "[rebrand] $OLD_PKG -> $NEW_PKG (app name: $NEW_APP_NAME)"

# 1. Move package directories in every source set, then prune empty parents.
for set_dir in "$ANDROID"/app/src/*/java; do
  [ -d "$set_dir/$OLD_PATH" ] || continue
  mkdir -p "$set_dir/$(dirname "$NEW_PATH")"
  git -C "$REPO_ROOT" mv "$set_dir/$OLD_PATH" "$set_dir/$NEW_PATH"
  find "$set_dir/com" -type d -empty -delete 2>/dev/null || true
  echo "[rebrand]   moved $(basename "$(dirname "$set_dir")")/java"
done

# 2. Rewrite identifiers in every tracked text source. JNI first so the longer
#    Java_-prefixed symbol is not left half-rewritten by the package pass.
COUNT=0
while IFS= read -r f; do
  case "$f" in
    *.kt|*.java|*.xml|*.kts|*.cpp|*.c|*.h|*.pro|*.txt|*.properties) ;;
    *) continue ;;
  esac
  perl -pi -e "s/\Q$OLD_JNI\E/$NEW_JNI/g; s/\Q$OLD_PKG\E/$NEW_PKG/g" "$REPO_ROOT/$f"
  COUNT=$((COUNT + 1))
done < <(git -C "$REPO_ROOT" ls-files -- src/android)
echo "[rebrand]   rewrote $COUNT files"

# 3. Launcher label only. In-chat "Minis" strings are product copy and are left
#    for a separate content pass.
perl -pi -e "s|(<string name=\"app_name\">)[^<]*(</string>)|\${1}$NEW_APP_NAME\${2}|" \
  "$ANDROID/app/src/main/res/values/strings.xml"

LEFT=$(grep -rl "$OLD_PKG" "$ANDROID" --include='*.kt' --include='*.cpp' --include='*.c' 2>/dev/null | wc -l | tr -d ' ')
echo "[rebrand] done. residual references in sources: $LEFT (expect 0)"
