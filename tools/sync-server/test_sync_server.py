#!/usr/bin/env python3
"""Checks that the self-hosted server honours the same contract as the worker.

The point of this file is not that the server works — it is that a device can
be pointed at either backend and not notice. So every case here is written from
`tools/sync-worker/src/index.ts`: the same status codes, the same kind
whitelist, the same 64 KB inline limit, and the same last-write-wins rule.

    python3 test_sync_server.py

Runs a real server on a loopback port and talks to it over HTTP. No network, no
dependencies.
"""
from __future__ import annotations

import json
import os
import shutil
import sys
import tempfile
import threading
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import sync_server  # noqa: E402

TOKEN = "t" * 64
FAILED = []


def ck(name, got, want):
    if got == want:
        print("  ok   %s" % name)
    else:
        print("  FAIL %s: want %r, got %r" % (name, want, got))
        FAILED.append(name)


def call(method, path, body=None, token=TOKEN):
    """Returns (status, parsed_json)."""
    url = "http://127.0.0.1:%d%s" % (PORT, path)
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if token is not None:
        req.add_header("Authorization", "Bearer " + token)
    if data:
        req.add_header("content-type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"null")


work = tempfile.mkdtemp(prefix="openthumb-sync-test-")
try:
    import hashlib
    from http.server import ThreadingHTTPServer

    sync_server.Handler.store = sync_server.Store(
        os.path.join(work, "sync.db"), os.path.join(work, "blobs"))
    sync_server.Handler.token_digest = hashlib.sha256(TOKEN.encode()).digest()
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), sync_server.Handler)
    PORT = httpd.server_address[1]
    threading.Thread(target=httpd.serve_forever, daemon=True).start()

    # --- auth: the whole model is one bearer token ----------------------
    ck("no token is unauthorized", call("GET", "/sync/list?kind=memory", token=None)[0], 401)
    ck("wrong token is unauthorized",
       call("GET", "/sync/list?kind=memory", token="x" * 64)[0], 401)
    ck("right token is allowed", call("GET", "/sync/list?kind=memory")[0], 200)

    # --- the kind whitelist --------------------------------------------
    ck("unknown kind is rejected", call("GET", "/sync/list?kind=secrets")[0], 400)
    ck("missing kind is rejected", call("GET", "/sync/list")[0], 400)
    for kind in sorted(sync_server.KINDS):
        ck("kind %s is accepted" % kind, call("GET", "/sync/list?kind=" + kind)[0], 200)

    # --- unknown routes ------------------------------------------------
    ck("unknown path is 404", call("GET", "/sync/everything")[0], 404)
    ck("posting elsewhere is 404", call("POST", "/nope", {"items": []})[0], 404)

    # --- batch validation ----------------------------------------------
    ck("empty batch is rejected", call("POST", "/sync/batch", {"items": []})[0], 400)
    ck("non-array items is rejected", call("POST", "/sync/batch", {"items": 1})[0], 400)
    ck("item with unknown kind is rejected",
       call("POST", "/sync/batch", {"items": [
           {"kind": "nope", "id": "a", "updatedAt": 1, "payload": {}}]})[0], 400)
    ck("item with empty id is rejected",
       call("POST", "/sync/batch", {"items": [
           {"kind": "memory", "id": "", "updatedAt": 1, "payload": {}}]})[0], 400)
    ck("item with an over-long id is rejected",
       call("POST", "/sync/batch", {"items": [
           {"kind": "memory", "id": "x" * 257, "updatedAt": 1, "payload": {}}]})[0], 400)
    ck("item with a non-numeric updatedAt is rejected",
       call("POST", "/sync/batch", {"items": [
           {"kind": "memory", "id": "a", "updatedAt": "soon", "payload": {}}]})[0], 400)
    ck("an over-large batch is rejected",
       call("POST", "/sync/batch", {"items": [
           {"kind": "memory", "id": str(i), "updatedAt": 1, "payload": {}}
           for i in range(sync_server.MAX_BATCH + 1)]})[0], 400)

    # --- round trip -----------------------------------------------------
    st, body = call("POST", "/sync/batch", {"items": [
        {"kind": "memory", "id": "m1", "updatedAt": 1000, "payload": {"note": "안녕"}},
        {"kind": "trigger_rule", "id": "r1", "updatedAt": 2000, "payload": [1, 2, 3]},
    ]})
    ck("a batch is accepted", (st, body.get("accepted")), (200, 2))
    ck("list returns what was written",
       call("GET", "/sync/list?kind=memory&since=0")[1],
       {"items": [{"id": "m1", "updatedAt": 1000}]})
    ck("since filters strictly greater",
       call("GET", "/sync/list?kind=memory&since=1000")[1], {"items": []})
    ck("a payload survives the round trip, unicode included",
       call("GET", "/sync/item?kind=memory&id=m1")[1],
       {"kind": "memory", "id": "m1", "updatedAt": 1000, "payload": {"note": "안녕"}})
    ck("a missing item is 404", call("GET", "/sync/item?kind=memory&id=nope")[0], 404)
    ck("item without an id is 400", call("GET", "/sync/item?kind=memory")[0], 400)

    # --- last write wins, and only forwards ----------------------------
    call("POST", "/sync/batch", {"items": [
        {"kind": "memory", "id": "m1", "updatedAt": 1500, "payload": {"note": "newer"}}]})
    ck("a newer write replaces an older one",
       call("GET", "/sync/item?kind=memory&id=m1")[1]["payload"], {"note": "newer"})
    call("POST", "/sync/batch", {"items": [
        {"kind": "memory", "id": "m1", "updatedAt": 900, "payload": {"note": "stale"}}]})
    ck("an older write does not clobber a newer one",
       call("GET", "/sync/item?kind=memory&id=m1")[1]["payload"], {"note": "newer"})
    ck("an equal timestamp is allowed to write",
       (call("POST", "/sync/batch", {"items": [
           {"kind": "memory", "id": "m1", "updatedAt": 1500,
            "payload": {"note": "equal"}}]})[0],
        call("GET", "/sync/item?kind=memory&id=m1")[1]["payload"]),
       (200, {"note": "equal"}))

    # --- the 64 KB boundary, where the payload moves out of the row ----
    small = {"blob": "s" * (sync_server.INLINE_LIMIT - 100)}
    big = {"blob": "b" * (sync_server.INLINE_LIMIT + 1000)}
    call("POST", "/sync/batch", {"items": [
        {"kind": "chat", "id": "small", "updatedAt": 1, "payload": small},
        {"kind": "chat", "id": "big", "updatedAt": 2, "payload": big},
    ]})
    ck("an inline payload round-trips",
       call("GET", "/sync/item?kind=chat&id=small")[1]["payload"], small)
    ck("an offloaded payload round-trips",
       call("GET", "/sync/item?kind=chat&id=big")[1]["payload"], big)
    blobs = os.listdir(os.path.join(work, "blobs"))
    ck("only the large one left the database", len(blobs), 1)
    ck("the blob filename reveals nothing about the id",
       "big" in "".join(blobs), False)
    ck("blobs are not world-readable",
       oct(os.stat(os.path.join(work, "blobs", blobs[0])).st_mode)[-3:], "600")

    # --- a lost blob is not reported as a missing item -----------------
    os.remove(os.path.join(work, "blobs", blobs[0]))
    ck("a row whose blob is gone is 502, not 404",
       call("GET", "/sync/item?kind=chat&id=big")[0], 502)

    # --- an id that would escape the blob directory --------------------
    call("POST", "/sync/batch", {"items": [
        {"kind": "chat", "id": "../../escape", "updatedAt": 5, "payload": big}]})
    names = os.listdir(os.path.join(work, "blobs"))
    ck("a traversal id writes one file, named by hash",
       (len(names), all(len(n) == 64 and n.isalnum() for n in names)), (1, True))
    ck("a traversal id still round-trips",
       call("GET", "/sync/item?kind=chat&id=../../escape")[1]["payload"], big)

    httpd.shutdown()
finally:
    shutil.rmtree(work, ignore_errors=True)

print("all checks passed" if not FAILED else "%d FAILED: %s" % (len(FAILED), FAILED))
sys.exit(1 if FAILED else 0)
