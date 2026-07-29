"""URL rewrites worth trying when the first address is blocked.

Ported from insane-search (MIT). Every transform is a domain-agnostic *rule* —
none of them names a site. A transform either applies and returns a new URL, or
does not apply and returns None.

`m.` hosts are worth trying because a mobile-first site often server-renders
what its desktop SPA hides behind JavaScript, and because a WAF rule written
for the www host frequently was not copied to the mobile one.
"""
from __future__ import annotations

from typing import Optional
from urllib.parse import urlsplit, urlunsplit

_VOID = None


def _with_host(url: str, host: str) -> str:
    parts = urlsplit(url)
    if parts.port:
        host = "%s:%d" % (host, parts.port)
    return urlunsplit(parts._replace(netloc=host))


def _original(url: str) -> Optional[str]:
    return url


def _mobile_subdomain(url: str) -> Optional[str]:
    """www.example.com -> m.example.com"""
    host = urlsplit(url).hostname or ""
    return _with_host(url, "m." + host[4:]) if host.startswith("www.") else _VOID


def _am_prefix(url: str) -> Optional[str]:
    """example.com -> m.example.com, for apex-like hosts only."""
    host = urlsplit(url).hostname or ""
    if not host or host.startswith("m.") or host.startswith("www."):
        return _VOID  # www is mobile_subdomain's job
    if host.count(".") >= 2:
        return _VOID  # already a subdomain of something; prefixing is a guess
    return _with_host(url, "m." + host)


def _drop_www(url: str) -> Optional[str]:
    host = urlsplit(url).hostname or ""
    return _with_host(url, host[4:]) if host.startswith("www.") else _VOID


TRANSFORMS = {
    "original": _original,
    "mobile_subdomain": _mobile_subdomain,
    "am_prefix": _am_prefix,
    "drop_www": _drop_www,
}

DEFAULT_ORDER = ["original", "mobile_subdomain", "am_prefix", "drop_www"]


def apply_transform(name: str, url: str) -> Optional[str]:
    fn = TRANSFORMS.get(name)
    if fn is None:
        raise ValueError("unknown transform %r; known: %s" % (name, list(TRANSFORMS)))
    return fn(url)


def iter_transformed(url: str, order=None):
    """(name, url) pairs, skipping inapplicable transforms and duplicate URLs —
    `original` and `drop_www` collapse to the same address on an apex host."""
    seen, out = set(), []
    for name in order or DEFAULT_ORDER:
        new = apply_transform(name, url)
        if new is None or new in seen:
            continue
        seen.add(new)
        out.append((name, new))
    return out
