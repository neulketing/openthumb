# OpenThumb sync worker

Optional BYO-backend sync for OpenThumb. OpenThumb has no telemetry, no
analytics and no server of its own (see `PRIVACY.md`). If you want your
chats, trigger-run history, scheduled tasks, trigger rules and memory to
sync between devices, you deploy this Cloudflare Worker to your own
Cloudflare account and point the app at it. The data lives in your D1
database and your R2 bucket; nobody else operates it.

Auth is a single bearer token that you generate. There are no accounts,
no multi-user support and no analytics. The worker never logs payload
contents.

## What you need

- A free Cloudflare account (<https://dash.cloudflare.com/sign-up>).
  D1 and R2 both have free tiers that cover personal sync traffic.
- Node.js 18 or later.

## Setup

All commands run in this directory (`tools/sync-worker/`).

1. Install dependencies:

   ```sh
   npm install
   ```

2. Log wrangler in to your Cloudflare account (opens a browser):

   ```sh
   npx wrangler login
   ```

3. Create the D1 database:

   ```sh
   npx wrangler d1 create openthumb-sync
   ```

   The output prints a `database_id`. Open `wrangler.toml` and replace
   `TODO-paste-database-id-here` with that id.

4. Create the R2 bucket:

   ```sh
   npx wrangler r2 bucket create openthumb-sync
   ```

   If you pick a different name, update `bucket_name` in `wrangler.toml`.

5. Apply the schema:

   ```sh
   npx wrangler d1 execute openthumb-sync --remote --file=schema.sql
   ```

6. Generate your sync token and store it as a secret:

   ```sh
   openssl rand -hex 32
   npx wrangler secret put SYNC_TOKEN
   ```

   Paste the hex string when prompted. Keep a copy — you will enter the
   same token in the app, and it cannot be read back from Cloudflare
   afterwards. Treat it like a password: anyone who has it can read and
   write your synced data.

7. Deploy:

   ```sh
   npx wrangler deploy
   ```

   The output shows your worker URL, e.g.
   `https://openthumb-sync.<your-subdomain>.workers.dev`.

8. In the OpenThumb app, open sync settings, enter the worker URL and the
   token from step 6.

## API

All requests need `Authorization: Bearer <SYNC_TOKEN>`. Anything else gets
a `401`. Payloads are JSON; bodies larger than 64 KB are stored in R2 with
a pointer in D1, transparently to the client.

- `POST /sync/batch` — upsert a batch. Body:
  `{"items": [{"kind": "chat", "id": "...", "updatedAt": 1712345678901, "payload": {...}}]}`
  where `kind` is one of `chat`, `trigger_run`, `scheduled_task`,
  `trigger_rule`, `memory`. Last write wins on `updatedAt`. Max 200 items
  per batch.
- `GET /sync/list?kind=chat&since=0` — `[{id, updatedAt}]` for delta sync,
  oldest first (up to 1000 per call; page by passing the last `updatedAt`
  as `since`).
- `GET /sync/item?kind=chat&id=...` — one item with its `payload`.

## Notes

- Data is stored as plaintext in your own D1/R2. Cloudflare can
  technically see it, as with any hosted database. Client-side encryption
  before upload is a possible future app option; this worker would not
  need to change.
- To rotate the token, run step 6 again and update the app.
- To wipe everything, delete the D1 database and R2 bucket from the
  Cloudflare dashboard.
