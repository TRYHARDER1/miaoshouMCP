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
CREATE TABLE IF NOT EXISTS ms_ai_generation (
  id TEXT PRIMARY KEY,
  snapshot_id TEXT NOT NULL REFERENCES ms_product_snapshot(id),
  task TEXT NOT NULL,
  provider TEXT NOT NULL,
  model TEXT NOT NULL,
  prompt_version TEXT NOT NULL,
  started_at TEXT NOT NULL,
  completed_at TEXT,
  input_summary_json TEXT NOT NULL,
  output_json TEXT,
  validation_status TEXT NOT NULL,
  review_status TEXT NOT NULL DEFAULT 'PENDING_REVIEW',
  error_code TEXT
);
CREATE INDEX IF NOT EXISTS idx_generation_snapshot ON ms_ai_generation(snapshot_id, started_at);
