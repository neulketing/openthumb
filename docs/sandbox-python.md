# apk cannot install anything on the Note8 sandbox

Measured 2026-07-30 on a Galaxy Note8 (SM-N950U, Android 9, unrooted) running
OpenThumb 1.0.5 debug, through the debug server's `debug.shellExecute`.

## What happens

The Alpine rootfs the app ships is a minirootfs: **15 packages, no python3.**

```
$ apk info | wc -l
15
$ python3 -V
sh: python3: not found        (exit 127)
```

`apk add python3` fails, and so does `apk update` underneath it:

```
fetch https://mirror.kakao.com/alpine/v3.21/main/aarch64/APKINDEX.tar.gz
WARNING: updating and opening https://mirror.kakao.com/alpine/v3.21/main: IO ERROR
ERROR: unable to select packages:
  python3 (no such package)
```

## What it is not

Each of these was checked and ruled out:

| Suspect | Evidence against |
|---|---|
| No network | `wget -O- https://example.com` returns the page. `wget` also downloads `APKINDEX.tar.gz` from the same mirror successfully. |
| TLS / missing CA roots | `ca-certificates-bundle` is installed and `/etc/ssl/certs/ca-certificates.crt` exists. Switching the mirrors to plain `http://` fails identically. |
| IPv6-only DNS | The phone resolves through `2001:270::4:1`. Replacing `/etc/resolv.conf` with `8.8.8.8` and `1.1.1.1` changes nothing. |
| Unwritable cache | `/var/cache/apk` is writable. |
| Missing device nodes | `/dev` has `null`, `zero`, `random`, `urandom`, `tty`, `ptmx`, `pts`, `fd`. |

apk-tools is 2.14.6. `apk info` (local database, no network) works, so only its
fetch path is broken — most likely a syscall or socket option PRoot does not
emulate, since busybox wget over the same transport is fine.

## What it means

Anything in the sandbox that installs itself with `apk` does not work on this
device. That includes `minis-mcp-cli`, which is inherited from upstream and
whose wrapper does `apk add python3 py3-pip` on first use, and it included
`openthumb-fetch` until its failure path was made explicit.

`openthumb-fetch` now prints a JSON object naming the problem and escalating to
`browser_use`, and says in as many words that this is a runtime problem on the
phone rather than the site refusing the request. Reporting a page as blocked
because the fetcher could not start is exactly the failure the tool exists to
prevent, so the degraded path is the one that had to be right.

## The fix that shipped

`scripts/rootfs_add_packages.py` bakes python3 into the rootfs at build time,
where the network works and nothing has to be resolved on a phone.
`scripts/prepare_android_sandbox.sh` calls it right after downloading the
minirootfs, so every build carries it.

It uses neither apk nor Docker. apk-tools is a Linux binary and the target is
aarch64, so a macOS build host cannot run it; Docker would make the image
depend on whether Docker happened to be running, and a rootfs that differs
between a developer's build and CI's is worse than no rootfs at all. An `.apk`
is a gzipped tar and `APKINDEX` is flat text, so resolving and unpacking needs
only curl and the standard library — identical on every host.

Measured cost, resolving python3's full closure:

```
alpine-minirootfs-3.21.3-aarch64      3.7 MB
+ python3 3.12.13 and 17 dependencies  14.6 MB    (913 files)
```

About 11MB, which takes the APK from 33MB to roughly 44MB.

### Verified on the device

Extracted into a Note8's rootfs and driven through the debug server:

```
$ python3 -V
Python 3.12.13

$ python3 /usr/local/lib/openthumb-fetch/test_fetch.py
all checks passed                     (69 checks, exit 0)

$ openthumb-fetch https://example.com
{"ok": true, "status": 200, "verdict": "weak_ok",
 "reasons": ["small_but_complete:559"], "via": "urllib",
 "content": "Example Domain Example Domain This domain is for use in ...",
 "extraction": "visible"}              exit 0

$ openthumb-fetch https://example.com/nope
{"ok": false, "status": 404, "verdict": "not_found", ...}
```

One thing to know when unpacking a rootfs by hand for testing: Android's
toybox `tar` does not preserve the execute bit, so `python3` lands as 644 and
fails with "Permission denied" rather than "not found". The build-time path
does not have this problem — the app unpacks the image itself.

## What is still not fixed

apk still cannot install anything from inside the sandbox. Baking packages in
at build time sidesteps it; it does not repair it. Anything a user wants to
`apk add` at runtime will still fail, and `minis-mcp-cli` — inherited from
upstream, and installing itself the same way — now finds the python3 it needs
but would still fail at its `pip install httpx` step.
