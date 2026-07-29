"""Decide whether a fetched page is actually the page you asked for.

Ported from insane-search (MIT, github.com/fivetaku/insane-search), whose
premise is the one thing every naive fetcher gets wrong: **HTTP 200 is where
the check starts, not where it ends.** A WAF challenge, a consent wall and a
bot interstitial all arrive as a cheerful 200 with an HTML body, and an agent
that trusts the status code summarises the interstitial as if it were content.

Six layers, in order, all generic — none of them names a website:

  1. status semantics — 429 / 401 / 404 / 5xx mean different things
  2. hard markers    — structural WAF containers, decisive on their own
  3. size fingerprint— a caller-supplied byte size known to be a stub
  4. JSON awareness  — a small valid JSON body is an API hit, not a challenge
  5. success selectors — positive proof, the strongest signal for HTML
  6. soft markers + sensor cookie + tiny-body heuristics — last resort

Deviation from upstream: selectors are matched with the standard library
rather than BeautifulSoup, so nothing has to be pip-installed on a phone.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from enum import Enum
from html.parser import HTMLParser
from typing import Optional

# Structural challenge containers. These strings do not occur in legitimate
# page content — only WAF products emit them — so one hit decides the verdict.
HARD_CHALLENGE_MARKERS = [
    "sec-if-cpt-container",
    "powered and protected by akamai",
    "just a moment...",
    "cf-chl-bypass",
    "window._cf_chl_opt",
    "orchestrate/chl_page",
    "attention required! | cloudflare",
    "<title>bot challenge</title>",
    "the requested url was rejected",
    "request unsuccessful. incapsula",
    "please enable js and disable any ad blocker",
]

# Words that suggest a challenge but appear legitimately in real writing — an
# article about CAPTCHAs contains "captcha". Only decisive without better proof.
SOFT_CHALLENGE_MARKERS = ["access denied", "checking your browser", "datadome", "captcha"]

# Below this many bytes a body is suspected of being a stub rather than a page.
SMALL_BODY_THRESHOLD = 3000
# Above this, a single soft marker reads as a content mention, not a challenge.
SOFT_MENTION_MAX_BYTES = 20000
# A body this large is expected to carry at least this much readable text;
# less means the words are still inside JavaScript. Both numbers are floors,
# not ratios — a long page of code samples has little prose and is still real.
SHELL_MIN_BYTES = 20000
SHELL_MIN_VISIBLE = 200


class Verdict(Enum):
    STRONG_OK = "strong_ok"          # positive proof — terminal success
    WEAK_OK = "weak_ok"              # clean, no negative signal — terminal success
    SUSPECT_OK = "suspect_ok"        # ambiguous — NOT terminal, keep trying
    CHALLENGE = "challenge"          # WAF challenge body
    BLOCKED = "blocked"              # generic non-2xx block
    RATE_LIMITED = "rate_limited"    # 429 — back off, do not hammer
    AUTH_REQUIRED = "auth_required"  # 401/407 — a wall, not a puzzle
    NOT_FOUND = "not_found"          # 404/410 — a wall
    UNKNOWN = "unknown"


# Verdicts where trying a different transport cannot help.
TERMINAL_NONSUCCESS = frozenset({
    Verdict.AUTH_REQUIRED, Verdict.NOT_FOUND, Verdict.RATE_LIMITED,
})


@dataclass
class Response:
    """What every transport here reduces to, so the validator never has to know
    whether the bytes came from urllib, curl_cffi or the phone's WebView."""

    status: int = 0
    text: str = ""
    url: str = ""
    headers: dict = field(default_factory=dict)
    cookies: dict = field(default_factory=dict)
    content: Optional[bytes] = None
    via: str = ""

    def header(self, name: str) -> str:
        want = name.lower()
        for k, v in self.headers.items():
            if str(k).lower() == want:
                return str(v)
        return ""


@dataclass
class ValidationResult:
    verdict: Verdict
    reasons: list = field(default_factory=list)
    matched_selectors: list = field(default_factory=list)
    body_size: int = 0
    status: int = 0

    @property
    def ok(self) -> bool:
        """Terminal success only — SUSPECT_OK is deliberately excluded."""
        return self.verdict in (Verdict.STRONG_OK, Verdict.WEAK_OK)

    def to_dict(self) -> dict:
        return {
            "verdict": self.verdict.value,
            "reasons": self.reasons,
            "matched_selectors": self.matched_selectors,
            "body_size": self.body_size,
            "status": self.status,
        }


def _marker_pattern(marker: str):
    # Lookbehind only: "octocaptcha" is not a challenge, but structural markers
    # legitimately prefix longer tokens (window._cf_chl_opt), so no lookahead.
    return re.compile(r"(?<![a-z0-9_])" + re.escape(marker))


_HARD = [(m, _marker_pattern(m)) for m in HARD_CHALLENGE_MARKERS]
_SOFT = [(m, _marker_pattern(m)) for m in SOFT_CHALLENGE_MARKERS]


def _hits(patterns, lowered: str) -> list:
    return [m for m, pat in patterns if pat.search(lowered)]


def _abck_unresolved(cookies: dict) -> bool:
    """Akamai's sensor cookie carries `~-1~` until the challenge is solved. Its
    presence means the page was served *while still being judged*."""
    abck = cookies.get("_abck", "") or ""
    return bool(abck) and "~-1~" in abck


def _looks_like_json(text: str, ctype: str) -> bool:
    return "json" in ctype or text.lstrip()[:1] in ("{", "[")


def _json_ok(text: str) -> Optional[bool]:
    """True for non-empty JSON, False for parseable-but-empty, None for not JSON."""
    try:
        obj = json.loads(text)
    except Exception:
        return None
    return obj not in (None, {}, [], "")


def _byte_size(resp: Response) -> int:
    if isinstance(resp.content, (bytes, bytearray)):
        return len(resp.content)
    return len(resp.text.encode("utf-8", "ignore"))


def _visible_text(text: str) -> str:
    """What a reader would see: markup and script bodies removed."""
    out = re.sub(r"(?is)<(script|style|template|noscript)[^>]*>.*?</\1>", " ", text)
    out = re.sub(r"(?s)<[^>]+>", " ", out)
    return re.sub(r"\s+", " ", out).strip()


def _looks_complete_content_page(text: str, lowered: str) -> bool:
    """A small body can still be a real page. example.com is ~600 bytes and is
    not a challenge. What separates them is completeness plus visible words: an
    interstitial that got past the marker checks is script-only or truncated."""
    if "</html>" not in lowered and "</body>" not in lowered:
        return False
    return len(_visible_text(text)) >= 64


class _SelectorMatcher(HTMLParser):
    """Answers "does this document contain an element matching X" for the
    selector shapes recipes actually use: `tag`, `#id`, `.class`, `tag.class`,
    `tag#id`, and descendant chains of those (`article .body p`).

    ponytail: no attribute or pseudo selectors; add a real CSS engine only if a
    recipe needs one — bs4 is a pip install away and this stays dependency-free.
    """

    def __init__(self, selectors):
        super().__init__(convert_charrefs=True)
        self.compiled = [(s, [_parse_simple(p) for p in s.split()]) for s in selectors]
        self.matched = set()
        self._open = []  # stack of (tag, id, classes)

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        node = (tag, (a.get("id") or "").strip(),
                set((a.get("class") or "").split()))
        self._open.append(node)
        self._check()
        # Void elements never close, so they must not linger on the stack and
        # swallow their siblings into a descendant chain.
        if tag in ("br", "img", "input", "meta", "link", "hr", "source", "col"):
            self._open.pop()

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)

    def handle_endtag(self, tag):
        for i in range(len(self._open) - 1, -1, -1):
            if self._open[i][0] == tag:
                del self._open[i:]
                return

    def _check(self):
        for sel, parts in self.compiled:
            if sel in self.matched:
                continue
            if _chain_matches(self._open, parts):
                self.matched.add(sel)


def _parse_simple(part: str):
    """`div#main.big` → ('div', 'main', {'big'}); '*' or '' for any tag."""
    tag, ident, classes = "", "", set()
    for token in re.findall(r"[.#]?[^.#]+", part):
        if token.startswith("#"):
            ident = token[1:]
        elif token.startswith("."):
            classes.add(token[1:])
        else:
            tag = token.lower()
    return tag, ident, classes


def _node_matches(node, simple) -> bool:
    tag, ident, classes = simple
    if tag and tag != "*" and node[0] != tag:
        return False
    if ident and node[1] != ident:
        return False
    return classes <= node[2]


def _chain_matches(stack, parts) -> bool:
    """The last part must match the innermost open element; earlier parts must
    appear, in order, somewhere among its ancestors."""
    if not parts or not stack:
        return False
    if not _node_matches(stack[-1], parts[-1]):
        return False
    remaining = list(parts[:-1])
    for node in stack[:-1]:
        if remaining and _node_matches(node, remaining[0]):
            remaining.pop(0)
    return not remaining


def _selector_hits(body: str, selectors: list) -> list:
    m = _SelectorMatcher(selectors)
    try:
        m.feed(body)
    except Exception:
        pass
    return [s for s in selectors if s in m.matched]


def validate(
    resp: Response,
    *,
    success_selectors: Optional[list] = None,
    known_bad_sizes: Optional[list] = None,
    size_tolerance: int = 20,
) -> ValidationResult:
    size = _byte_size(resp)
    r = ValidationResult(verdict=Verdict.UNKNOWN, body_size=size, status=resp.status)

    # --- 1. status semantics ----------------------------------------------
    if resp.status == 429:
        r.verdict, _ = Verdict.RATE_LIMITED, r.reasons.append("status=429")
        return r
    if resp.status in (401, 407):
        r.verdict, _ = Verdict.AUTH_REQUIRED, r.reasons.append(f"status={resp.status}")
        return r
    if resp.status in (404, 410):
        r.verdict, _ = Verdict.NOT_FOUND, r.reasons.append(f"status={resp.status}")
        return r
    if 500 <= resp.status <= 599:
        r.verdict, _ = Verdict.BLOCKED, r.reasons.append(f"status={resp.status}")
        return r
    if resp.status == 0:
        r.reasons.append("status=0")
        return r
    # 403/406 fall through on purpose: the challenge body rides along with them.

    lowered = resp.text.lower()

    # --- 2. hard markers ---------------------------------------------------
    hard = _hits(_HARD, lowered)
    if hard:
        r.verdict = Verdict.CHALLENGE
        r.reasons.extend("hard:" + m for m in hard[:3])
        return r

    # --- 3. size fingerprint ----------------------------------------------
    for bad in known_bad_sizes or []:
        if abs(size - bad) <= size_tolerance:
            r.verdict = Verdict.CHALLENGE
            r.reasons.append(f"size_fp:{size}~{bad}")
            return r

    # --- 4. JSON awareness (before the tiny-body heuristic) ----------------
    if _looks_like_json(resp.text, resp.header("content-type").lower()):
        j = _json_ok(resp.text)
        if j is True:
            # A tiny 2xx JSON body is a successful API hit. Selectors are an
            # HTML idea, so WEAK_OK is the ceiling — there is no positive proof.
            r.verdict, _ = Verdict.WEAK_OK, r.reasons.append("json_ok")
            return r
        if j is False:
            r.verdict, _ = Verdict.SUSPECT_OK, r.reasons.append("json_empty")
            return r

    abck_bad = _abck_unresolved(resp.cookies)

    # --- 5. caller's positive proof ---------------------------------------
    if success_selectors:
        hits = _selector_hits(resp.text, success_selectors)
        if hits:
            r.matched_selectors = hits
            if abck_bad:
                # Content is there, but the sensor says the page is still being
                # judged — not terminal, keep looking for a clean one.
                r.verdict, _ = Verdict.SUSPECT_OK, r.reasons.append("abck_unresolved")
                return r
            r.verdict = Verdict.STRONG_OK
            return r
        r.verdict, _ = Verdict.CHALLENGE, r.reasons.append("no_success_selector")
        return r

    # --- 6. heuristics, only with no positive proof -----------------------
    soft = _hits(_SOFT, lowered)
    if soft:
        if len(soft) >= 2 or size <= SOFT_MENTION_MAX_BYTES:
            r.verdict = Verdict.CHALLENGE
            r.reasons.extend("soft:" + m for m in soft[:3])
            return r
        r.reasons.append("soft_mention:" + soft[0])

    if size < SMALL_BODY_THRESHOLD:
        if _looks_complete_content_page(resp.text, lowered):
            r.verdict, _ = Verdict.WEAK_OK, r.reasons.append(f"small_but_complete:{size}")
            return r
        r.verdict, _ = Verdict.CHALLENGE, r.reasons.append(f"tiny_body:{size}")
        return r

    if abck_bad:
        r.verdict, _ = Verdict.SUSPECT_OK, r.reasons.append("abck_unresolved")
        return r

    # --- 7. content density -----------------------------------------------
    # A large body carrying almost no readable words is a JavaScript shell, not
    # an article. Measured: a Threads profile returns 867KB with 62 visible
    # characters, and every check above passes it as a clean page. Not a
    # CHALLENGE — the server did nothing hostile — but not a terminal success
    # either, so the caller escalates to the WebView that can render it.
    if size >= SHELL_MIN_BYTES:
        visible = len(_visible_text(resp.text))
        if visible < SHELL_MIN_VISIBLE:
            r.verdict = Verdict.SUSPECT_OK
            r.reasons.append("js_shell:%d_visible_chars_in_%d_bytes" % (visible, size))
            return r

    r.verdict = Verdict.WEAK_OK
    return r
