#!/usr/bin/env python3
"""What the upstream project shipped, and whether it lands where we diverged.

A fork's real risk is not missing a release — it is drifting for months and
discovering the divergence only when a merge is unavoidable. Watching releases
alone does not catch it: on 2026-07-30 upstream's newest release was 0.20-preview
from 13 July while its default branch had been pushed on the 25th, so a
release-only watcher was twelve days behind what they were actually building.

So this reads commits, not just tags, and crosses them with the files this fork
has changed. A commit touching a file we rewrote is the interesting one — it is
both the merge conflict and the place where we made a deliberate choice that is
now worth defending or revisiting. A commit touching files we never touched is
free to take.

    scripts/upstream_digest.py [--since-sha SHA] [--json]

Needs `gh` (authenticated) and, for the divergence column, an `upstream` remote.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys

UPSTREAM = "OpenMinis/OpenMinis"
UPSTREAM_URL = "https://github.com/%s.git" % UPSTREAM
MARKER = "docs/upstream-last-seen.txt"


def gh(path: str):
    out = subprocess.run(["gh", "api", path], capture_output=True, text=True)
    if out.returncode != 0:
        raise SystemExit("gh api %s failed: %s" % (path, out.stderr.strip()))
    return json.loads(out.stdout)


def git(*args, check=True) -> str:
    out = subprocess.run(["git", *args], capture_output=True, text=True)
    if check and out.returncode != 0:
        return ""
    return out.stdout.strip()


def diverged_files() -> set:
    """Files this fork changed since it split from upstream.

    Returns an empty set when the upstream remote is missing, and the caller
    reports that rather than quietly showing every commit as safe to take —
    "no conflicts" and "conflicts not checked" must not look the same.
    """
    if UPSTREAM not in git("remote", "-v"):
        git("remote", "add", "upstream", UPSTREAM_URL, check=False)
    if not git("fetch", "--quiet", "upstream", check=False) and not git(
            "rev-parse", "--verify", "-q", "upstream/main"):
        return set()
    base = git("merge-base", "upstream/main", "HEAD")
    if not base:
        return set()
    return set(git("diff", "--name-only", "%s..HEAD" % base).splitlines())


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--since-sha", default="", help="default: the recorded marker")
    p.add_argument("--json", action="store_true")
    args = p.parse_args(argv)

    marker = args.since_sha
    if not marker:
        try:
            marker = open(MARKER, encoding="utf-8").read().strip()
        except OSError:
            marker = ""

    repo = gh("repos/%s" % UPSTREAM)
    head = gh("repos/%s/commits/%s" % (UPSTREAM, repo["default_branch"]))["sha"]

    # The marker was a release tag before it was a commit; resolve either.
    commits = []
    if marker and marker != head:
        try:
            cmp_ = gh("repos/%s/compare/%s...%s" % (UPSTREAM, marker, head))
            commits = cmp_.get("commits", [])
        except SystemExit:
            commits = gh("repos/%s/commits?per_page=20" % UPSTREAM)

    ours = diverged_files()
    rows = []
    for c in commits:
        sha = c["sha"]
        files = [f["filename"] for f in
                 gh("repos/%s/commits/%s" % (UPSTREAM, sha)).get("files", [])]
        overlap = sorted(set(files) & ours)
        rows.append({
            "sha": sha[:9],
            "subject": (c["commit"]["message"].splitlines() or [""])[0][:100],
            "date": c["commit"]["author"]["date"][:10],
            "files": len(files),
            "touches_ours": overlap,
        })

    releases = [r for r in gh("repos/%s/releases?per_page=5" % UPSTREAM)
                if not r.get("draft")]
    result = {
        "upstream": UPSTREAM,
        "stars": repo["stargazers_count"],
        "head": head,
        "marker": marker,
        "latest_release": releases[0]["tag_name"] if releases else "",
        "latest_release_date": releases[0]["published_at"][:10] if releases else "",
        "pushed_at": repo["pushed_at"][:10],
        "new_commits": rows,
        "divergence_checked": bool(ours),
        "our_changed_files": len(ours),
    }

    if args.json:
        print(json.dumps(result, indent=2, ensure_ascii=False))
        return 0

    print("upstream %s — %d stars, last push %s"
          % (UPSTREAM, result["stars"], result["pushed_at"]))
    print("latest release %s (%s); default branch at %s"
          % (result["latest_release"], result["latest_release_date"], head[:9]))
    if not rows:
        print("nothing new since %s" % (marker or "the beginning"))
        return 0
    print("\n%d commits since %s:" % (len(rows), marker))
    conflicts = [r for r in rows if r["touches_ours"]]
    for r in rows:
        mark = "!" if r["touches_ours"] else " "
        print("  %s %s %s  %s (%d files)"
              % (mark, r["date"], r["sha"], r["subject"], r["files"]))
        for f in r["touches_ours"]:
            print("        also changed here: %s" % f)
    if not result["divergence_checked"]:
        print("\nDivergence NOT checked — no upstream remote, so nothing above is "
              "marked. Do not read the absence of ! as 'safe to merge'.")
    elif conflicts:
        print("\n%d of %d land on files this fork rewrote — read those first."
              % (len(conflicts), len(rows)))
    else:
        print("\nNone of them touch the %d files this fork changed."
              % result["our_changed_files"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
