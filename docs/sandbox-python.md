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

## Fixes, neither of them free

**Ship python3 inside the rootfs.** `deps/prepare_alpine_rootfs.sh` builds the
image on the host, where apk works, so `apk add --root` at build time would
solve it for every device with no runtime dependency at all. It costs roughly
45MB on a 33MB APK — the app more than doubles — so it is the owner's call, not
a detail to decide in passing.

**Install packages with wget instead of apk.** `wget` works, and an `.apk` file
is a tarball; fetching python3 and its dependencies and unpacking them by hand
would work. It means resolving a dependency graph in shell, which is a package
manager, which is why apk exists. Not recommended.

Until one of those lands, the fetcher runs on any device where python3 is
present and degrades honestly everywhere else.
