# OpenThumb sync server

The same sync backend as `tools/sync-worker/`, without Cloudflare.

OpenThumb has no server of its own — no accounts, no telemetry, no analytics
(see `PRIVACY.md`). Sync is optional and off until you point the app at a
backend you run. There are two, and the app cannot tell them apart:

| | `sync-worker` | `sync-server` (this) |
|---|---|---|
| Runs on | Cloudflare Workers | any machine with Python 3.9 |
| Storage | D1 + R2 | SQLite file + a directory |
| Needs an account | Cloudflare | none |
| Dependencies | Node, wrangler | none |
| TLS | included | yours to arrange |

Pick the worker if you want someone else to keep it running. Pick this if you
would rather not have an account with anyone.

## Run it

```sh
mkdir -p ~/.openthumb-sync && chmod 700 ~/.openthumb-sync
python3 sync_server.py --print-token > ~/.openthumb-sync/token
chmod 600 ~/.openthumb-sync/token

python3 sync_server.py --token-file ~/.openthumb-sync/token
```

That listens on `127.0.0.1:8787`, keeps metadata in
`~/.openthumb-sync/sync.db`, and writes payloads over 64 KB into
`~/.openthumb-sync/sync.db.blobs`. In the app: Settings → Sync, the URL and the
same token.

`SYNC_TOKEN` in the environment works instead of `--token-file` if that fits
your service manager better.

## Reaching it from a phone

The phone has to reach the machine, and **the token travels in a header**, so
plain HTTP over anything but loopback puts a credential on the network in
cleartext. The server refuses that rather than letting you find out later:
binding a non-loopback address without TLS exits with an explanation.

Three ways out, in the order most people should try them:

1. **A private network.** Tailscale, WireGuard, or your own VPN — bind
   `127.0.0.1` on the host and let the tunnel carry it. Nothing is exposed.
2. **A reverse proxy with a certificate.** Caddy or nginx terminating TLS in
   front of `127.0.0.1:8787`; the app sees `https://`.
3. **`--tls-cert` / `--tls-key`.** The server does TLS directly. Fine for a
   self-signed certificate on a network you control, but Android will reject an
   untrusted certificate unless you install it.

`--allow-plaintext` exists for the case where you have read the above and
decided the risk is yours to take. It is not a shortcut around the problem.

## Backing it up

Two paths hold everything:

```sh
sqlite3 ~/.openthumb-sync/sync.db ".backup /somewhere/sync-backup.db"
cp -a ~/.openthumb-sync/sync.db.blobs /somewhere/
```

Do both, or a large payload comes back without its body. There is no other
state — no accounts, no sessions, no cache.

## Moving between backends

Both speak the same three endpoints with the same token, so switching means
changing the URL in the app. Data already on one side does not follow; the app
re-uploads what it has on the next sync, and `updatedAt` decides conflicts the
same way on both.

## The contract

Auth is `Authorization: Bearer <token>`, compared by SHA-256 digest so the
comparison time does not leak the token.

- `POST /sync/batch` — `{items: [{kind, id, updatedAt, payload}]}`, at most 200
  items. Last write wins, and only forwards: an older `updatedAt` never
  overwrites a newer row.
- `GET /sync/list?kind=&since=` — `{items: [{id, updatedAt}]}`, strictly newer
  than `since`, oldest first, at most 1000.
- `GET /sync/item?kind=&id=` — `{kind, id, updatedAt, payload}`.

`kind` is one of `chat`, `trigger_run`, `scheduled_task`, `trigger_rule`,
`memory`. Anything else is a 400 — the app never invents kinds, so an unknown
one means something is wrong rather than something is new.

A payload over 64 KB is written to the blob directory instead of the row, which
is what the worker does with R2. Blob filenames are the SHA-256 of
`kind` and `id`: an id is user data on its way to a filesystem, and hashing it
removes path traversal, case collisions and length limits at once. If a row
promises a payload the directory no longer has, the answer is 502, not 404 —
the item exists and this server lost it, which is a different problem for you
than the item never existing.

## Checking it

```sh
python3 test_sync_server.py
```

39 checks against a real server on a loopback port. Every case is taken from
`tools/sync-worker/src/index.ts`, because the thing worth testing is not that
this server works but that a device pointed at either backend cannot tell.
