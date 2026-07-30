#!/usr/bin/env python3
"""OpenThumb sync server — the same backend, without Cloudflare.

`tools/sync-worker/` is a Cloudflare Worker, which means syncing between your
own devices requires an account with one company. This is the same contract on
your own machine: one file, one SQLite database, one directory for large
payloads, and nothing to install.

    python3 sync_server.py --db ~/.openthumb-sync/sync.db --token-file ~/.openthumb-sync/token

Then point the app at it: Settings → Sync, URL `https://your-host`, the same
token. The endpoints, the auth model, the 64 KB inline limit and the
last-write-wins rule all match the worker exactly, so you can move between the
two without the app noticing.

Privacy stance, unchanged from the worker: no accounts, no multi-user, no
analytics, and payload contents are never logged. The single bearer token is
the whole auth model — whoever holds it holds the data.

Standard library only. Python 3.9 or later.
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import secrets
import sqlite3
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

# Kept in step with tools/sync-worker/src/index.ts. A kind the worker rejects
# must be rejected here too, or moving between backends changes behaviour.
KINDS = frozenset({"chat", "trigger_run", "scheduled_task", "trigger_rule", "memory"})
INLINE_LIMIT = 64 * 1024
MAX_BATCH = 200
MAX_ID_LEN = 256
LIST_LIMIT = 1000
# A body larger than this cannot be a legal batch, so it is refused before it
# is read into memory rather than after.
MAX_BODY = MAX_BATCH * (INLINE_LIMIT + 4096)

SCHEMA = """
CREATE TABLE IF NOT EXISTS sync_items (
  kind       TEXT    NOT NULL,
  id         TEXT    NOT NULL,
  updated_at INTEGER NOT NULL,
  size       INTEGER NOT NULL,
  offloaded  INTEGER NOT NULL DEFAULT 0,
  payload    TEXT,
  PRIMARY KEY (kind, id)
);
CREATE INDEX IF NOT EXISTS sync_items_kind_updated
  ON sync_items (kind, updated_at);
"""


class Store:
    """SQLite plus a directory of blobs, standing in for D1 plus R2."""

    def __init__(self, db_path: str, blob_dir: str):
        self.db_path = db_path
        self.blob_dir = blob_dir
        os.makedirs(os.path.dirname(os.path.abspath(db_path)) or ".", exist_ok=True)
        os.makedirs(blob_dir, exist_ok=True)
        self._lock = threading.Lock()
        with self._connect() as db:
            # WAL so a read during a write does not block; the lock below still
            # serialises writers, which is right for one person's devices.
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript(SCHEMA)

    def _connect(self):
        db = sqlite3.connect(self.db_path, timeout=10)
        db.row_factory = sqlite3.Row
        return db

    def _blob_path(self, kind: str, ident: str) -> str:
        # The id comes from the client, so it never touches the filesystem as
        # text. Hashing it removes path traversal, case collisions on
        # case-insensitive filesystems, and length limits in one step — things
        # the worker gets for free from R2's flat keys.
        digest = hashlib.sha256(("%s\0%s" % (kind, ident)).encode()).hexdigest()
        return os.path.join(self.blob_dir, digest)

    def put_many(self, items) -> int:
        rows = []
        for item in items:
            serialized = json.dumps(item.get("payload"), ensure_ascii=False,
                                    separators=(",", ":"))
            size = len(serialized.encode("utf-8"))
            offload = size > INLINE_LIMIT
            if offload:
                path = self._blob_path(item["kind"], item["id"])
                tmp = path + ".tmp"
                with open(tmp, "w", encoding="utf-8") as fh:
                    fh.write(serialized)
                os.replace(tmp, path)  # a reader never sees a half-written blob
                os.chmod(path, 0o600)
            rows.append((item["kind"], item["id"], int(item["updatedAt"]),
                         size, 1 if offload else 0,
                         None if offload else serialized))

        with self._lock, self._connect() as db:
            db.executemany(
                """INSERT INTO sync_items (kind, id, updated_at, size, offloaded, payload)
                   VALUES (?, ?, ?, ?, ?, ?)
                   ON CONFLICT (kind, id) DO UPDATE SET
                     updated_at = excluded.updated_at,
                     size = excluded.size,
                     offloaded = excluded.offloaded,
                     payload = excluded.payload
                   WHERE excluded.updated_at >= sync_items.updated_at""",
                rows,
            )
        return len(rows)

    def list_since(self, kind: str, since: int):
        with self._connect() as db:
            cur = db.execute(
                """SELECT id, updated_at FROM sync_items
                   WHERE kind = ? AND updated_at > ?
                   ORDER BY updated_at ASC LIMIT ?""",
                (kind, int(since), LIST_LIMIT),
            )
            return [{"id": r["id"], "updatedAt": r["updated_at"]} for r in cur]

    def get(self, kind: str, ident: str):
        with self._connect() as db:
            row = db.execute(
                """SELECT kind, id, updated_at, offloaded, payload FROM sync_items
                   WHERE kind = ? AND id = ?""",
                (kind, ident),
            ).fetchone()
        if row is None:
            return None
        if row["offloaded"]:
            try:
                with open(self._blob_path(kind, ident), encoding="utf-8") as fh:
                    payload = json.load(fh)
            except OSError:
                # The row promises a payload the blob directory does not have.
                # 502 rather than 404: the item exists, this server lost it.
                return "missing"
        else:
            payload = json.loads(row["payload"] or "null")
        return {"kind": row["kind"], "id": row["id"],
                "updatedAt": row["updated_at"], "payload": payload}


def valid_item(raw) -> bool:
    return (
        isinstance(raw, dict)
        and raw.get("kind") in KINDS
        and isinstance(raw.get("id"), str)
        and 0 < len(raw["id"]) <= MAX_ID_LEN
        and isinstance(raw.get("updatedAt"), (int, float))
        and not isinstance(raw.get("updatedAt"), bool)
    )


class Handler(BaseHTTPRequestHandler):
    server_version = "openthumb-sync"
    sys_version = ""          # no Python version in the banner
    protocol_version = "HTTP/1.1"

    store: Store = None       # set by serve()
    token_digest: bytes = b""

    def log_message(self, fmt, *args):
        # Method and status only. A URL carries the item id, and the id is the
        # user's data — the worker logs nothing, and neither does this.
        sys.stderr.write("%s %s\n" % (self.command, args[1] if len(args) > 1 else ""))

    def _json(self, body, status=200):
        blob = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(blob)))
        self.end_headers()
        self.wfile.write(blob)

    def _authorized(self) -> bool:
        header = self.headers.get("Authorization", "")
        if not header.startswith("Bearer ") or not self.token_digest:
            return False
        got = hashlib.sha256(header[7:].encode()).digest()
        return hmac.compare_digest(got, self.token_digest)

    def do_GET(self):
        if not self._authorized():
            return self._json({"error": "unauthorized"}, 401)
        parts = urlsplit(self.path)
        query = parse_qs(parts.query)
        kind = (query.get("kind") or [""])[0]

        if parts.path == "/sync/list":
            if kind not in KINDS:
                return self._json({"error": "unknown or missing kind"}, 400)
            try:
                since = int(float((query.get("since") or ["0"])[0]))
            except ValueError:
                return self._json({"error": "since must be a number"}, 400)
            return self._json({"items": self.store.list_since(kind, since)})

        if parts.path == "/sync/item":
            ident = (query.get("id") or [""])[0]
            if kind not in KINDS:
                return self._json({"error": "unknown or missing kind"}, 400)
            if not ident:
                return self._json({"error": "missing id"}, 400)
            found = self.store.get(kind, ident)
            if found is None:
                return self._json({"error": "not found"}, 404)
            if found == "missing":
                return self._json({"error": "payload missing from storage"}, 502)
            return self._json(found)

        return self._json({"error": "not found"}, 404)

    def do_POST(self):
        if not self._authorized():
            return self._json({"error": "unauthorized"}, 401)
        if urlsplit(self.path).path != "/sync/batch":
            return self._json({"error": "not found"}, 404)

        length = int(self.headers.get("content-length") or 0)
        if length <= 0 or length > MAX_BODY:
            return self._json({"error": "invalid body length"}, 400)
        try:
            body = json.loads(self.rfile.read(length))
        except Exception:
            return self._json({"error": "invalid JSON body"}, 400)

        items = body.get("items") if isinstance(body, dict) else None
        if not isinstance(items, list) or not items:
            return self._json({"error": "items must be a non-empty array"}, 400)
        if len(items) > MAX_BATCH:
            return self._json({"error": "batch too large (max %d)" % MAX_BATCH}, 400)
        for raw in items:
            if not valid_item(raw):
                return self._json({"error": "invalid item in batch"}, 400)

        try:
            accepted = self.store.put_many(items)
        except Exception:
            # Deliberately opaque, like the worker: an error message must not
            # be able to echo payload contents back to the caller.
            return self._json({"error": "internal error"}, 500)
        return self._json({"ok": True, "accepted": accepted})


def read_token(args) -> str:
    if args.token_file:
        with open(args.token_file, encoding="utf-8") as fh:
            token = fh.read().strip()
        if not token:
            sys.exit("token file %s is empty" % args.token_file)
        return token
    token = os.environ.get("SYNC_TOKEN", "").strip()
    if not token:
        sys.exit(
            "no token. Generate one and keep it in a file:\n"
            "  mkdir -p ~/.openthumb-sync && chmod 700 ~/.openthumb-sync\n"
            "  python3 -c 'import secrets;print(secrets.token_hex(32))' "
            "> ~/.openthumb-sync/token\n"
            "then pass --token-file ~/.openthumb-sync/token, or set SYNC_TOKEN."
        )
    return token


def serve(args) -> int:
    token = read_token(args)
    if len(token) < 32:
        sys.exit("token is too short to be worth having; use at least 32 characters")

    # The token travels in a header. Over plain HTTP on a reachable interface
    # that is a credential in cleartext on the network, so it is refused rather
    # than left to be discovered later. Loopback is fine — nothing leaves the
    # machine — and a reverse proxy terminating TLS is the intended setup.
    public = args.host not in ("127.0.0.1", "::1", "localhost")
    if public and not args.tls_cert and not args.allow_plaintext:
        sys.exit(
            "refusing to serve %s without TLS: the bearer token would cross the\n"
            "network in cleartext. Either put a TLS-terminating proxy in front and\n"
            "bind 127.0.0.1, pass --tls-cert/--tls-key, or accept the risk\n"
            "explicitly with --allow-plaintext." % args.host
        )

    Handler.store = Store(args.db, args.blob_dir or (args.db + ".blobs"))
    Handler.token_digest = hashlib.sha256(token.encode()).digest()

    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    if args.tls_cert:
        import ssl
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(args.tls_cert, args.tls_key or args.tls_cert)
        httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)

    scheme = "https" if args.tls_cert else "http"
    print("openthumb-sync on %s://%s:%d  db=%s"
          % (scheme, args.host, args.port, args.db), flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("stopped", flush=True)
    return 0


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8787)
    p.add_argument("--db", default=os.path.expanduser("~/.openthumb-sync/sync.db"))
    p.add_argument("--blob-dir", default="", metavar="DIR",
                   help="payloads over 64 KB (default: <db>.blobs)")
    p.add_argument("--token-file", default="", metavar="PATH",
                   help="file holding the bearer token; or set SYNC_TOKEN")
    p.add_argument("--tls-cert", default="", metavar="PEM")
    p.add_argument("--tls-key", default="", metavar="PEM")
    p.add_argument("--allow-plaintext", action="store_true",
                   help="serve a non-loopback address without TLS anyway")
    p.add_argument("--print-token", action="store_true",
                   help="generate a token, print it, and exit")
    args = p.parse_args(argv)

    if args.print_token:
        print(secrets.token_hex(32))
        return 0
    return serve(args)


if __name__ == "__main__":
    sys.exit(main())
