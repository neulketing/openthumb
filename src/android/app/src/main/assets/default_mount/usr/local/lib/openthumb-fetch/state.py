"""What the fetcher remembers between runs: cookies per host, and where it got to.

Two problems on a phone that do not exist on a desktop.

**Doze.** Android suspends background work, and a fetch that walks several URL
variants takes long enough to be interrupted. Without a record of what was
already tried, the next run starts from scratch and gets suspended at the same
place forever. An append-only journal fixes it: each attempt is written as it
finishes, and a resumed run skips what the journal already covers. One file,
appended to, no state machine — the file *is* the state.

**Cookies.** Clearing a JS challenge in the WebView is expensive; throwing the
result away after one request wastes it. Cookies handed in with `--cookies` are
kept for the host they came from, so later fetches to that host start already
solved. Storage is keyed by host and nothing else: a cookie from one site can
never be attached to a request for another, which is the whole reason this is
not simply a shared jar.
"""
from __future__ import annotations

import hashlib
import json
import os
import time
from urllib.parse import urlsplit

STATE_DIR = os.environ.get(
    "OPENTHUMB_FETCH_STATE", os.path.expanduser("~/.openthumb-fetch"))

# Past this, a resumed run re-tries everything: a block lifts, a rate limit
# expires, and a stale journal would make the fetcher skip a route that now works.
JOURNAL_TTL = 600
# Session cookies outlive this on the server side, but re-solving is cheaper
# than looking blocked because we sent something expired.
COOKIE_TTL = 3600


def _dir(name: str) -> str:
    path = os.path.join(STATE_DIR, name)
    os.makedirs(path, exist_ok=True)
    return path


def _host(url: str) -> str:
    return (urlsplit(url).hostname or "").lower()


def _safe(name: str) -> str:
    """A host is attacker-influenced text on its way to a path. Hash it rather
    than sanitise it — no traversal, no case collision, no length limit."""
    return hashlib.sha256(name.encode("utf-8", "ignore")).hexdigest()[:32]


# --------------------------------------------------------------------------
# cookies, scoped to one host


def load_cookies(url: str) -> dict:
    path = os.path.join(_dir("cookies"), _safe(_host(url)) + ".json")
    try:
        with open(path, "r", encoding="utf-8") as fh:
            blob = json.load(fh)
    except Exception:
        return {}
    if time.time() - blob.get("at", 0) > COOKIE_TTL:
        return {}
    # Belt and braces: the file is keyed by host hash, and the host is stored
    # inside it too, so a hash collision cannot hand site A's cookies to site B.
    if blob.get("host") != _host(url):
        return {}
    return blob.get("cookies") or {}


def save_cookies(url: str, cookies: dict) -> None:
    if not cookies:
        return
    path = os.path.join(_dir("cookies"), _safe(_host(url)) + ".json")
    tmp = path + ".tmp"
    try:
        with open(tmp, "w", encoding="utf-8") as fh:
            json.dump({"host": _host(url), "at": time.time(), "cookies": cookies}, fh)
        os.replace(tmp, path)
        os.chmod(path, 0o600)  # session cookies are credentials
    except Exception:
        pass  # a fetch must not fail because the cache is unwritable


def forget_cookies(url: str) -> None:
    try:
        os.remove(os.path.join(_dir("cookies"), _safe(_host(url)) + ".json"))
    except Exception:
        pass


# --------------------------------------------------------------------------
# the resume journal


class Journal:
    """Append-only record of which URL variants a run already tried.

    Keyed by the request, not the URL, so changing selectors or cookies starts
    a fresh run rather than inheriting verdicts that were reached under
    different rules.
    """

    def __init__(self, url: str, fingerprint: str = "", enabled: bool = True):
        self.enabled = enabled
        key = _safe("%s\n%s" % (url, fingerprint))
        self.path = os.path.join(_dir("journal"), key + ".jsonl")
        self.done = self._read() if enabled else {}

    def _read(self) -> dict:
        try:
            age = time.time() - os.path.getmtime(self.path)
        except OSError:
            return {}
        if age > JOURNAL_TTL:
            self.clear()
            return {}
        done = {}
        try:
            with open(self.path, "r", encoding="utf-8") as fh:
                for line in fh:
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        rec = json.loads(line)
                    except Exception:
                        continue  # a run killed mid-write leaves a partial line
                    if rec.get("url"):
                        done[rec["url"]] = rec
        except Exception:
            return {}
        return done

    def seen(self, url: str):
        return self.done.get(url)

    def record(self, attempt: dict) -> None:
        self.done[attempt.get("url", "")] = attempt
        if not self.enabled:
            return
        try:
            with open(self.path, "a", encoding="utf-8") as fh:
                fh.write(json.dumps(attempt, ensure_ascii=False) + "\n")
                fh.flush()
                os.fsync(fh.fileno())  # the point is surviving a kill
        except Exception:
            pass

    def clear(self) -> None:
        try:
            os.remove(self.path)
        except Exception:
            pass
