#!/usr/bin/env bash
# Move the version number in every place that carries it, in one step.
#
# build.gradle.kts is the source of truth. The CHANGELOG heading, the F-Droid
# changelog filename (named after versionCode), and the number printed on the
# landing page all derive from it, and until now each was edited by hand — 1.0.5
# shipped with fastlane still holding a changelog for versionCode 21.
#
#   scripts/bump-version.sh patch    # 1.0.5 -> 1.0.6
#   scripts/bump-version.sh minor    # 1.0.5 -> 1.1.0
#   scripts/bump-version.sh major    # 1.0.5 -> 2.0.0
#   scripts/bump-version.sh 1.2.3    # exactly this
#
# Write what changed under "## [Unreleased]" in CHANGELOG.md first; this script
# turns that section into the release notes and refuses to run if it is empty.
# It commits and tags. Pushing the tag is what publishes the release —
# .github/workflows/release.yml takes the notes from there.
set -euo pipefail

ROOT=$(git rev-parse --show-toplevel)
GRADLE="$ROOT/src/android/app/build.gradle.kts"
CHANGELOG="$ROOT/CHANGELOG.md"
SITE="$ROOT/docs/index.html"
FASTLANE="$ROOT/fastlane/metadata/android/en-US/changelogs"

die() { printf '%s\n' "$*" >&2; exit 1; }
# sed -i differs between BSD and GNU; write through a temp file instead.
edit() { local f=$1; shift; local t; t=$(mktemp); sed "$@" "$f" >"$t"; mv "$t" "$f"; }

[ $# -eq 1 ] || die "usage: ${0##*/} patch|minor|major|X.Y.Z"

cur=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$GRADLE")
code=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$GRADLE")
[ -n "$cur" ] && [ -n "$code" ] || die "could not read versionName/versionCode from $GRADLE"

IFS=. read -r ma mi pa <<<"$cur"
case "$1" in
  major) new="$((ma + 1)).0.0" ;;
  minor) new="$ma.$((mi + 1)).0" ;;
  patch) new="$ma.$mi.$((pa + 1))" ;;
  [0-9]*.[0-9]*.[0-9]*) new=$1 ;;
  *) die "usage: ${0##*/} patch|minor|major|X.Y.Z" ;;
esac
newcode=$((code + 1))

grep -q '^## \[Unreleased\]' "$CHANGELOG" || die "no '## [Unreleased]' section in $CHANGELOG"
body=$(awk '/^## \[Unreleased\]/{p=1;next} p&&/^## /{exit} p' "$CHANGELOG")
# Whitespace-only means nobody wrote down what changed; a release with empty
# notes is worse than no release.
[ -n "$(printf '%s' "$body" | tr -d '[:space:]')" ] || die "the [Unreleased] section is empty — write the notes first"

if ! git diff --quiet || ! git diff --cached --quiet; then
  die "working tree is dirty — commit or stash first"
fi

printf 'v%s (code %s) -> v%s (code %s)\n' "$cur" "$code" "$new" "$newcode"

edit "$GRADLE" -e "s/versionCode = $code/versionCode = $newcode/" \
               -e "s/versionName = \"$cur\"/versionName = \"$new\"/"

# Roll [Unreleased] into the new version and open a fresh empty one above it.
today=$(date +%Y-%m-%d)
tmp=$(mktemp)
awk -v v="$new" -v d="$today" '
  /^## \[Unreleased\]/ { print "## [Unreleased]"; print ""; print "## [" v "] — " d; next }
  { print }
' "$CHANGELOG" >"$tmp"
mv "$tmp" "$CHANGELOG"

# F-Droid reads the file named after versionCode and truncates past 500 chars,
# so send it the prose with the markdown stripped rather than the raw section.
mkdir -p "$FASTLANE"
printf '%s\n' "$body" \
  | sed -e 's/^### /-- /' -e 's/^- \*\*\(.*\)\*\*/- \1/' -e 's/\*\*//g' -e 's/`//g' \
  | awk 'NF||p{print; p=NF}' \
  | cut -c1-500 >"$FASTLANE/$newcode.txt"

edit "$SITE" -e "s|<!--v-->[^<]*<!--/v-->|<!--v-->v$new<!--/v-->|"

git add -A -- "$GRADLE" "$CHANGELOG" "$SITE" "$FASTLANE"
git commit -q -m "chore(release): v$new"
# Annotated, because `git push --follow-tags` — the command printed below and
# the one people reach for — pushes annotated tags only. A lightweight tag
# stays local and the release silently never happens.
git tag -a "v$new" -m "OpenThumb $new"

cat <<EOF
tagged v$new. publish with:

  git push origin main --follow-tags

Then check that the release actually started:

  gh run list --workflow Release --limit 1

A tag push that raises no workflow run looks exactly like a successful release
until someone goes looking for the APK — observed on v1.1.0, where the tag
reached the remote and nothing ran. If the list is empty, start it by hand:

  gh workflow run release.yml --ref v$new
EOF
