"""Fetch a URL and say honestly whether what came back is the page.

The loop is small on purpose: try the URL, judge the response, and if the
judgement is not a terminal success, try the next URL variant. What makes it
worth having is not the loop but the two things around it — the six-layer
verdict in verdict.py, which refuses to call a challenge page a success, and
the escalation ladder in routes.py, which refuses to let a failure look final.

Transport: urllib from the standard library, so this runs on a phone with
nothing installed. If curl_cffi is importable it is used instead, because it
can present a real browser's TLS fingerprint and many WAFs look at nothing
else. Its absence costs capability, never correctness.
"""
from __future__ import annotations

import gzip
import time
import urllib.error
import urllib.request
import zlib
from http.cookiejar import CookieJar

import routes
import state
import urls
from verdict import TERMINAL_NONSUCCESS, Response, Verdict, validate

DEFAULT_TIMEOUT = 20
MAX_BODY_BYTES = 8 * 1024 * 1024

# Set once, not per request: a fingerprint is only useful if it is consistent.
# No user agent is hard-coded — the caller passes the device's own, so the app
# and this fetcher do not look like two different clients on one phone.
_BASE_HEADERS = {
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Encoding": "gzip, deflate",
    "Connection": "close",
}

try:  # optional; see module docstring
    from curl_cffi import requests as _curl  # type: ignore
except Exception:  # pragma: no cover - depends on what is installed
    _curl = None


def _decode(raw: bytes, encoding: str) -> bytes:
    enc = (encoding or "").lower()
    try:
        if "gzip" in enc:
            return gzip.decompress(raw)
        if "deflate" in enc:
            return zlib.decompress(raw, -zlib.MAX_WBITS)
    except Exception:
        pass  # a lying Content-Encoding is common; the raw bytes are still useful
    return raw


def _urllib_get(url, headers, cookies, timeout) -> Response:
    jar = CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    req = urllib.request.Request(url, headers=headers)
    if cookies:
        req.add_header("Cookie", "; ".join("%s=%s" % kv for kv in cookies.items()))
    try:
        with opener.open(req, timeout=timeout) as r:
            raw = r.read(MAX_BODY_BYTES)
            hdrs = dict(r.headers.items())
            status, final = r.status, r.geturl()
    except urllib.error.HTTPError as e:
        # The body of a 403 is the whole point — that is where the challenge is.
        raw = e.read(MAX_BODY_BYTES) if hasattr(e, "read") else b""
        hdrs = dict(e.headers.items()) if e.headers else {}
        status, final = e.code, url
    except Exception as e:
        return Response(status=0, text="", url=url, headers={"x-error": str(e)}, via="urllib")

    body = _decode(raw, str(hdrs.get("Content-Encoding", "")))
    got = dict(cookies or {})
    got.update({c.name: c.value for c in jar})
    return Response(
        status=status, text=body.decode("utf-8", "replace"), url=final,
        headers=hdrs, cookies=got, content=body, via="urllib",
    )


def _curl_get(url, headers, cookies, timeout, impersonate) -> Response:
    r = _curl.get(url, headers=headers, cookies=cookies or {}, timeout=timeout,
                  impersonate=impersonate, allow_redirects=True)
    body = r.content if isinstance(r.content, (bytes, bytearray)) else b""
    return Response(
        status=int(r.status_code or 0), text=r.text or "", url=str(r.url),
        headers=dict(r.headers or {}), cookies=dict(r.cookies or {}),
        content=bytes(body), via="curl_cffi:" + impersonate,
    )


def fetch_once(url, *, headers=None, cookies=None, timeout=DEFAULT_TIMEOUT,
               impersonate="chrome") -> Response:
    hdrs = dict(_BASE_HEADERS)
    hdrs.update(headers or {})
    if _curl is not None:
        try:
            return _curl_get(url, hdrs, cookies, timeout, impersonate)
        except Exception:
            pass  # fall through: a broken optional transport must not fail the fetch
    return _urllib_get(url, hdrs, cookies, timeout)


def fetch(url, *, success_selectors=None, known_bad_sizes=None, headers=None,
          cookies=None, timeout=DEFAULT_TIMEOUT, order=None, first_only=False,
          pause=0.0, resume=True, cookie_store=True) -> dict:
    """Try each URL variant until one is a terminal success, or the wall is real.

    Returns a dict, not an object, because its consumer is an LLM reading JSON.
    """
    variants = urls.iter_transformed(url, order)
    if first_only:
        variants = variants[:1]

    # Cookies handed in win over stored ones — the caller just cleared a
    # challenge in the WebView and knows more than the cache does.
    if cookies:
        state.save_cookies(url, cookies)
    merged = state.load_cookies(url) if cookie_store else {}
    merged.update(cookies or {})
    merged = merged or None

    # Anything that changes how a response is judged belongs in the key, or a
    # resumed run would inherit verdicts reached under different rules.
    fingerprint = repr((sorted(success_selectors or []), sorted(known_bad_sizes or []),
                        sorted((headers or {}).items()), bool(merged), first_only))
    journal = state.Journal(url, fingerprint, enabled=resume)

    attempts, best_resp, best = [], None, None
    stop_reason = "exhausted"

    for i, (name, candidate) in enumerate(variants):
        prior = journal.seen(candidate)
        if prior:
            # Already tried, and it did not succeed — a successful run clears
            # the journal. Skipping is the whole point: this is what makes a
            # fetch killed by Doze make progress instead of restarting. The
            # verdict still counts, or a fully resumed run would report nothing.
            attempt, resp = dict(prior, resumed=True), None
        else:
            if pause and i:
                time.sleep(pause)
            resp = fetch_once(candidate, headers=headers, cookies=merged,
                              timeout=timeout)
            val = validate(resp, success_selectors=success_selectors,
                           known_bad_sizes=known_bad_sizes)
            attempt = {
                "transform": name, "url": candidate, "via": resp.via,
                "status": val.status, "verdict": val.verdict.value,
                "bytes": val.body_size, "reasons": val.reasons,
            }
            if val.ok:
                attempts.append(attempt)
                journal.clear()
                if cookie_store and resp.cookies:
                    state.save_cookies(candidate, resp.cookies)
                return _result(True, resp, attempt, attempts, "success", True)
            journal.record(attempt)

        attempts.append(attempt)

        # Keep the least-bad attempt, and its body when we have one, so a caller
        # who wants to look at the challenge page still can.
        if best is None or _rank(attempt["verdict"]) > _rank(best["verdict"]):
            best, best_resp = attempt, resp

        if attempt["verdict"] in _TERMINAL_VALUES:
            stop_reason = attempt["verdict"]
            break
    else:
        stop_reason = "exhausted"

    exhausted = stop_reason == "exhausted" and not first_only
    return _result(False, best_resp, best, attempts, stop_reason, exhausted)


_TERMINAL_VALUES = frozenset(v.value for v in TERMINAL_NONSUCCESS)

# How informative a non-success is. A challenge page has a body worth showing;
# a 404 has nothing. Ordered so the least useless attempt is the one reported.
_RANK = {
    Verdict.SUSPECT_OK.value: 5, Verdict.CHALLENGE.value: 4,
    Verdict.BLOCKED.value: 3, Verdict.RATE_LIMITED.value: 2,
    Verdict.AUTH_REQUIRED.value: 1, Verdict.NOT_FOUND.value: 1,
    Verdict.UNKNOWN.value: 0,
}


def _rank(verdict: str) -> int:
    return _RANK.get(verdict, 0)


def _result(ok, resp, best, attempts, stop_reason, exhausted) -> dict:
    best = best or {}
    out = {
        "ok": ok,
        "url": resp.url if resp else best.get("url", ""),
        "transform": best.get("transform", ""),
        "status": best.get("status", 0),
        "verdict": best.get("verdict", Verdict.UNKNOWN.value),
        "reasons": best.get("reasons", []),
        "bytes": best.get("bytes", 0),
        "via": best.get("via", ""),
        # Empty when the attempt came from the journal — the body was not kept,
        # only the verdict. Say so rather than implying the page was blank.
        "content": resp.text if resp else "",
        "attempts": attempts,
        "stop_reason": stop_reason,
    }
    if ok:
        out["untried_routes"], out["must_use_browser"] = [], False
        out["block_class"] = ""
        return out

    routes_left, must_browser = routes.untried_routes(stop_reason, exhausted)
    out["untried_routes"] = routes_left
    out["must_use_browser"] = must_browser
    out["block_class"] = routes.classify_block([a["verdict"] for a in attempts])
    return out
