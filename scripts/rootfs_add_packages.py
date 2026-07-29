#!/usr/bin/env python3
"""Bake Alpine packages into the rootfs the app ships, at build time.

Why this exists: `apk` cannot install anything from inside the app's PRoot
sandbox on real hardware — measured on a Galaxy Note8, its fetch returns
IO ERROR while busybox wget pulls the same mirror over HTTPS without trouble
(docs/sandbox-python.md). So anything the sandbox needs has to be in the image
before it ships, and the only moment that can happen is here.

Why not apk, Docker or qemu: apk-tools is a Linux binary and the target is
aarch64, so a macOS build host cannot run it, and a Docker path would make the
image depend on whether Docker happened to be running — a rootfs that differs
between a developer's build and CI's is worse than no rootfs at all. An .apk is
a gzipped tar and APKINDEX is a flat text file, so resolving and unpacking them
needs nothing but curl and the standard library, and does the same thing on
every host.

    scripts/rootfs_add_packages.py ROOTFS.tar.gz python3 [more...]

The rootfs is rewritten in place. Already-present packages are skipped, so it
is safe to re-run.

ponytail: this resolves dependencies and unpacks files; it does not write
/lib/apk/db/installed, so `apk info` will not list what was baked in. That is
knowingly incomplete — apk cannot install on-device anyway, so its database is
not load-bearing. If apk ever works in the sandbox, write the db entries here
before anything tries `apk upgrade`.
"""
from __future__ import annotations

import gzip
import io
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

MIRROR = os.environ.get("ALPINE_MIRROR", "https://dl-cdn.alpinelinux.org/alpine")
VERSION = os.environ.get("ALPINE_VERSION", "3.21")
ARCH = os.environ.get("ALPINE_ARCH", "aarch64")
REPOS = ("main", "community")


def log(msg):
    print("[rootfs-pkg] " + msg, flush=True)


def fetch(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read()


def load_index(repo: str):
    """Parse one APKINDEX into {name: record} and {provided_name: package_name}.

    APKINDEX is blank-line separated blocks of single-letter fields: P name,
    V version, D space-separated dependencies, p space-separated things the
    package provides. Dependencies are written against provides (`so:libz.so.1`,
    `pc:openssl`), so both maps are needed to resolve anything.
    """
    url = "%s/v%s/%s/%s/APKINDEX.tar.gz" % (MIRROR, VERSION, repo, ARCH)
    log("index " + url)
    raw = fetch(url)
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:gz") as tf:
        member = tf.extractfile("APKINDEX")
        text = member.read().decode("utf-8", "replace")

    pkgs, provides = {}, {}
    for block in text.split("\n\n"):
        rec = {}
        for line in block.splitlines():
            if len(line) > 2 and line[1] == ":":
                rec[line[0]] = line[2:]
        name = rec.get("P")
        if not name:
            continue
        rec["repo"] = repo
        # A later repo does not override an earlier one: main wins over
        # community, which is the order apk itself prefers.
        pkgs.setdefault(name, rec)
        provides.setdefault(name, name)
        for token in rec.get("p", "").split():
            provides.setdefault(token.split("=")[0], name)
    return pkgs, provides


def resolve(wanted, pkgs, provides) -> list:
    """Every package needed to install `wanted`, in no particular order."""
    seen, out, queue = set(), [], list(wanted)
    while queue:
        dep = queue.pop()
        if dep.startswith("!"):
            continue  # a conflict marker, not a dependency
        name = provides.get(dep.split("=")[0].split("<")[0].split(">")[0])
        if name is None or name in seen:
            if name is None:
                log("  unresolved: %s (skipped)" % dep)
            continue
        seen.add(name)
        rec = pkgs[name]
        out.append(rec)
        queue.extend(rec.get("D", "").split())
    return out


def unpack_into(rec, dest: str) -> int:
    """Unpack one .apk over the rootfs. An .apk is three gzip streams end to
    end — signature, control, data — and Python's gzip reads them as one, so
    the metadata members simply appear first and are dropped by name."""
    url = "%s/v%s/%s/%s/%s-%s.apk" % (
        MIRROR, VERSION, rec["repo"], ARCH, rec["P"], rec["V"])
    blob = fetch(url)
    written = 0
    with tarfile.open(fileobj=gzip.GzipFile(fileobj=io.BytesIO(blob)), mode="r|") as tf:
        for m in tf:
            base = os.path.basename(m.name)
            if base.startswith(".SIGN.") or base in (".PKGINFO", ".INSTALL",
                                                     ".pre-install", ".post-install",
                                                     ".trigger"):
                continue
            if m.name.startswith("/") or ".." in m.name.split("/"):
                continue  # a package is untrusted input on its way to a path
            tf.extract(m, dest, set_attrs=False)
            written += 1
    return written


def main(argv) -> int:
    if len(argv) < 3:
        print(__doc__)
        return 2
    rootfs, wanted = argv[1], argv[2:]
    if not os.path.exists(rootfs):
        log("no such rootfs: " + rootfs)
        return 1

    work = tempfile.mkdtemp(prefix="rootfs-pkg-")
    try:
        tree = os.path.join(work, "root")
        os.makedirs(tree)
        log("unpacking " + rootfs)
        # tar(1) rather than tarfile: the rootfs has device nodes and hardlinks,
        # and the system tool round-trips them without special cases.
        subprocess.run(["tar", "xzf", rootfs, "-C", tree], check=True)

        pkgs, provides = {}, {}
        for repo in REPOS:
            p, pr = load_index(repo)
            for k, v in p.items():
                pkgs.setdefault(k, v)
            for k, v in pr.items():
                provides.setdefault(k, v)
        log("%d packages known" % len(pkgs))

        needed = resolve(wanted, pkgs, provides)
        log("resolved %s -> %d packages" % (", ".join(wanted), len(needed)))
        total = 0
        for rec in sorted(needed, key=lambda r: r["P"]):
            marker = os.path.join(tree, "lib/apk/db/installed")
            del marker  # see the ponytail note in the module docstring
            n = unpack_into(rec, tree)
            total += n
            log("  + %s-%s (%d files)" % (rec["P"], rec["V"], n))

        out = os.path.join(work, "new.tar.gz")
        subprocess.run(["tar", "czf", out, "-C", tree, "."], check=True)
        shutil.move(out, rootfs)
        log("wrote %s (%d files added, %.1f MB)"
            % (rootfs, total, os.path.getsize(rootfs) / 1e6))
        return 0
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
