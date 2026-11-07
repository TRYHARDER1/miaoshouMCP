PRAGMA journal_mode=WAL;
PRAGMA busy_timeout=5000;
CREATE TABLE IF NOT EXISTS ms_product_snapshot (
  id TEXT PRIMARY KEY,
  detail_id INTEGER NOT NULL,
  shop_id INTEGER NOT NULL,
  site TEXT NOT NULL,
  oss_md5 TEXT NOT NULL,
  original_json TEXT NOT NULL,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_snapshot_product ON ms_product_snapshot(shop_id, detail_id);
CREATE TABLE IF NOT EXISTS ms_product_draft (
  id TEXT PRIMARY KEY,
  snapshot_id TEXT NOT NULL REFERENCES ms_product_snapshot(id),
  kind TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status = 'PENDING_REVIEW'),
  draft_json TEXT NOT NULL,
  created_at TEXT NOT NULL,
  UNIQUE(snapshot_id, kind)
);
