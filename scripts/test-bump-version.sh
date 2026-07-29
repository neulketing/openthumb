#!/usr/bin/env bash
# Checks bump-version.sh against a throwaway repo holding only the four files
# it writes. The point is that all four move together — the failure this guards
# against is one of them being left behind, which is exactly how fastlane ended
# up shipping a changelog for versionCode 21 against an app at 26.
#
#   scripts/test-bump-version.sh
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

mkdir -p "$work/scripts" "$work/src/android/app" "$work/docs" \
         "$work/fastlane/metadata/android/en-US/changelogs"
cp "$ROOT/scripts/bump-version.sh" "$work/scripts/"
cp "$ROOT/src/android/app/build.gradle.kts" "$work/src/android/app/"
cp "$ROOT/docs/index.html" "$work/docs/"

cat >"$work/CHANGELOG.md" <<'EOF'
# Changelog

## [Unreleased]

### Added
- A thing worth releasing.

## [1.0.5] — 2026-07-29

- The previous one.
EOF

cd "$work"
git init -q .
git config user.email test@example.invalid
git config user.name test
git add -A
git commit -qm init

before_code=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' src/android/app/build.gradle.kts)
before_name=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' src/android/app/build.gradle.kts)
bash scripts/bump-version.sh patch >/dev/null

fail=0
ck() { if [ "$2" = "$3" ]; then echo "  ok   $1"; else echo "  FAIL $1: want '$3', got '$2'"; fail=1; fi; }

IFS=. read -r ma mi pa <<<"$before_name"
want_name="$ma.$mi.$((pa + 1))"
want_code=$((before_code + 1))

ck "versionName bumped" \
   "$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' src/android/app/build.gradle.kts)" "$want_name"
ck "versionCode bumped" \
   "$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' src/android/app/build.gradle.kts)" "$want_code"
ck "CHANGELOG heading written" \
   "$(grep -c "^## \[$want_name\] — " CHANGELOG.md)" "1"
ck "empty [Unreleased] reopened" \
   "$(awk '/^## \[Unreleased\]/{p=1;next} p&&/^## /{exit} p' CHANGELOG.md | tr -d '[:space:]')" ""
ck "store changelog named after versionCode" \
   "$([ -s "fastlane/metadata/android/en-US/changelogs/$want_code.txt" ] && echo yes)" "yes"
ck "site version rewritten" \
   "$(sed -n 's/.*<!--v-->\(.*\)<!--\/v-->.*/\1/p' docs/index.html)" "v$want_name"
ck "tag created" "$(git tag)" "v$want_name"
# Lightweight tags are skipped by `git push --follow-tags`, so the tag stays
# local and the release never fires. Observed on v1.0.6.
ck "tag is annotated" "$(git cat-file -t "v$want_name")" "tag"

# The release workflow slices its notes out of the same file; a heading it
# cannot match publishes an empty release.
ck "release notes extractable" \
   "$(awk -v v="$want_name" '
        $0 == "## [" v "]" || index($0, "## [" v "] ") == 1 { p = 1; next }
        p && /^## / { exit }
        p
      ' CHANGELOG.md | grep -c 'A thing worth releasing')" "1"

# An empty [Unreleased] must stop the next bump rather than tag empty notes.
if bash scripts/bump-version.sh patch >/dev/null 2>&1; then
  echo "  FAIL refuses to bump on empty [Unreleased]"; fail=1
else
  echo "  ok   refuses to bump on empty [Unreleased]"
fi

[ "$fail" = 0 ] && echo "all checks passed" || exit 1
