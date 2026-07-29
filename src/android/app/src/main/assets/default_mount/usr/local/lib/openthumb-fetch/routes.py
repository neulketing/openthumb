"""Say what has NOT been tried yet, so giving up is never mistaken for finished.

Ported from insane-search's failure gate (MIT). The idea it encodes: an agent
that reports "the site is blocked" after one 403 is wrong far more often than
the site is actually blocked, and no amount of prompting fixes that reliably.
What fixes it is the tool refusing to return a bare failure — every failed
fetch carries the named routes the fetcher itself structurally could not take.

The ladder is inverted from upstream. Upstream runs on a desktop where curl
with a forged TLS fingerprint is the strong move and a browser is the fallback.
Here the browser IS the phone: the WebView holds the system TLS stack, the real
cookie jar and a user agent that matches the device, so it is the FIRST
escalation, and cheap HTTP is the probe underneath it.
"""
from __future__ import annotations

from verdict import Verdict

_TERMINAL = frozenset(v.value for v in (Verdict.AUTH_REQUIRED, Verdict.NOT_FOUND))

BROWSER_ROUTE = (
    "browser_use: navigate to the URL in the app's WebView, then get_readable "
    "(or get_text). The WebView uses Android's own TLS stack and cookie jar, so "
    "it clears JS challenges this fetcher cannot."
)
COOKIE_BRIDGE_ROUTE = (
    "cookie bridge: after browser_use clears the challenge, browser_use "
    "get_cookies and re-run this fetch with --cookies — one expensive render "
    "buys many cheap fetches on the same host."
)
RATE_LIMIT_ROUTE = (
    "rate limited (429) — transient, not a wall: wait a few seconds and retry. "
    "Do not hammer; repeating immediately extends the block."
)


def untried_routes(stop_reason: str, transforms_exhausted: bool) -> tuple:
    """Return (routes, must_use_browser).

    An empty list is the permission to fail honestly: it means the wall is real
    — 404 is not a bot defence, and 401 will not yield to a better fingerprint.

    429 is deliberately NOT terminal. It is the single most common cause of a
    premature "this site blocks us", and the answer is to wait, not to give up.
    """
    routes = []

    if stop_reason in _TERMINAL:
        return routes, False

    if stop_reason == Verdict.RATE_LIMITED.value:
        routes.append(RATE_LIMIT_ROUTE)
    elif not transforms_exhausted:
        routes.append("url variants not exhausted — re-run without --first-only")

    # Reached only when something behaved like a defence rather than a wall.
    routes.append(BROWSER_ROUTE)
    routes.append(COOKIE_BRIDGE_ROUTE)
    return routes, True


_WAF_VERDICTS = frozenset(v.value for v in (
    Verdict.CHALLENGE, Verdict.BLOCKED, Verdict.RATE_LIMITED, Verdict.SUSPECT_OK))


def classify_block(verdicts) -> str:
    """Was this a bot defence or a real wall?

    Every attempt landing on the same 401/404 is a wall — trying harder cannot
    work. Attempts that disagree, or any WAF signal at all, mean something is
    deciding per-request, and a decision can be changed.

    Returns "bot_detection", "infra_or_auth", or "" when there is no signal.
    """
    seen = [v for v in verdicts if v]
    if not seen:
        return ""
    if any(v in _WAF_VERDICTS for v in seen):
        return "bot_detection"
    if len(set(seen)) > 1:
        return "bot_detection"
    return "infra_or_auth" if seen[0] in _TERMINAL else ""
