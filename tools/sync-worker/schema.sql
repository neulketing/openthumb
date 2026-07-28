-- OpenThumb sync worker schema (D1 / SQLite).
-- One table only. Metadata always lives here; payloads larger than 64 KB
-- are offloaded to R2 (offloaded = 1, payload = NULL) with the R2 key
-- derived as `${kind}/${id}`.

CREATE TABLE IF NOT EXISTS sync_items (
  kind       TEXT    NOT NULL,
  id         TEXT    NOT NULL,
  updated_at INTEGER NOT NULL,          -- client-supplied epoch milliseconds
  size       INTEGER NOT NULL,          -- payload size in bytes (UTF-8)
  offloaded  INTEGER NOT NULL DEFAULT 0, -- 1 when the body is in R2
  payload    TEXT,                      -- inline JSON when offloaded = 0
  PRIMARY KEY (kind, id)
);

CREATE INDEX IF NOT EXISTS idx_sync_items_kind_updated
  ON sync_items (kind, updated_at);
