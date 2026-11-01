#!/usr/bin/env python3
"""Re-apply OpenThumb's name and contact points after an upstream merge.

Upstream's sources say "Minis". This fork keeps their package names and file
paths untouched, so `git merge upstream/main` stays cheap, and rewrites only
the text a user can see. Run it after every merge; it is idempotent.

    scripts/rebrand.py          rewrite in place
    scripts/rebrand.py --check  exit 1 and list anything still unbranded (CI)
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "src/android/app/src"

NAME = re.compile(r"(?<![A-Za-z_.])(?:Open )?Minis(?![A-Za-z_]| Skills)")
# Wire protocols, persisted markers and log tags: renaming these breaks old
# chats or the debug tooling, and no user reads them.
KEEP = re.compile(
    r"X-Minis-Token|Minis runtime reminder|Minis context|REALM|Minis Debug"
    r"|Minis legacy|newWakeLock|createWifiLock"
)
LITERAL = re.compile(r'"(?:[^"\\]|\\.)*"')
COMMENT = re.compile(r"\s*(//|\*|/\*)")
CONTACT = [
    ("dev@openminis.app", "neulketing@gmail.com"),
    ("https://openminis.github.io/privacy-policy.html",
     "https://github.com/neulketing/openthumb/blob/main/PRIVACY.md"),
]
APK_NAME = "MinisApp-"


def rename(text):
    return NAME.sub("OpenThumb", text)


def kotlin(text, path):
    out, raw = [], False
    for line in text.split("\n"):
        was_raw = raw
        if line.count('"""') % 2:
            raw = not raw
        if KEEP.search(line) or (not was_raw and COMMENT.match(line)):
            out.append(line)
        elif was_raw:
            out.append(rename(line))
        else:
            out.append(LITERAL.sub(lambda m: rename(m.group(0)), line))
    text = "\n".join(out)
    for old, new in CONTACT:
        text = text.replace(old, new)
    # Attribution sent to providers. About keeps its credit to upstream.
    if path.name != "AboutScreen.kt":
        text = text.replace("https://github.com/OpenMinis/OpenMinis",
                            "https://github.com/neulketing/openthumb")
    if "UpdatePolicy" in path.name:
        text = text.replace(APK_NAME, "OpenThumb-")
    return text


def targets():
    for f in sorted(SRC.glob("main/res/values*/strings.xml")):
        yield f, lambda t, p: rename(t)
    for base in ("main/java", "test/java", "androidTest/java"):
        for f in sorted((SRC / base).rglob("*.kt")):
            yield f, kotlin
    for f in sorted((SRC / "main/assets/default_mount/usr/local/bin").iterdir()):
        if f.is_file() and not f.is_symlink():
            yield f, lambda t, p: rename(t)


def main():
    check = "--check" in sys.argv[1:]
    stale = []
    for f, fix in targets():
        before = f.read_text(errors="surrogateescape")
        after = fix(before, f)
        if after != before:
            stale.append(f.relative_to(ROOT))
            if not check:
                f.write_text(after, errors="surrogateescape")
    if check and stale:
        print("unbranded:", *stale, sep="\n  ")
        return 1
    print(f"{'stale' if check else 'rewrote'} {len(stale)} files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
