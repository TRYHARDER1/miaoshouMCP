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

-- M5.2: immutable plan configuration; one plan per snapshot.
CREATE TABLE IF NOT EXISTS ms_image_batch_plan (
  id TEXT PRIMARY KEY, -- persisted plan identity
  snapshot_id TEXT NOT NULL UNIQUE REFERENCES ms_product_snapshot(id), -- immutable asset context
  batch_size INTEGER NOT NULL CHECK(batch_size BETWEEN 1 AND 4), -- maximum distinct assets per batch
  manifest_hash TEXT NOT NULL, -- SHA256 of complete M5.1 manifest
  total_assets INTEGER NOT NULL CHECK(total_assets >= 0), -- distinct asset count, not reference count
  created_at TEXT NOT NULL, -- UTC fixed millisecond ISO-8601
  updated_at TEXT NOT NULL -- most recent state change
);
CREATE TABLE IF NOT EXISTS ms_image_batch (
  id TEXT PRIMARY KEY, -- stable persisted batch identity
  plan_id TEXT NOT NULL REFERENCES ms_image_batch_plan(id), -- owner
  sequence INTEGER NOT NULL CHECK(sequence >= 0), -- zero-based batch order
  status TEXT NOT NULL CHECK(status IN ('PENDING','PROCESSING','PAUSED','COMPLETED','FAILED','CANCELLED')),
  created_at TEXT NOT NULL, -- UTC allocation time
  updated_at TEXT NOT NULL, -- UTC latest state update
  UNIQUE(plan_id, sequence),
  UNIQUE(plan_id, id) -- composite target prevents cross-plan task assignment
);
CREATE TABLE IF NOT EXISTS ms_image_batch_task (
  plan_id TEXT NOT NULL REFERENCES ms_image_batch_plan(id), -- asset identity scope
  batch_id TEXT NOT NULL, -- fixed original batch
  asset_id TEXT NOT NULL, -- link to asset in reconstructed M5.1 manifest; no duplicated references
  sequence INTEGER NOT NULL CHECK(sequence >= 0), -- original asset sequence
  status TEXT NOT NULL CHECK(status IN ('PENDING','PROCESSING','COMPLETED','FAILED','SKIPPED')),
  error_code TEXT, -- safe enum-like failure code, never raw external errors
  retry_count INTEGER NOT NULL DEFAULT 0 CHECK(retry_count >= 0), -- explicit retry count
  attempt_token TEXT, -- fences previous attempts
  lease_until TEXT, -- UTC processing deadline, nullable otherwise
  started_at TEXT, -- latest attempt start
  completed_at TEXT, -- latest terminal event
  created_at TEXT NOT NULL, -- original allocation
  updated_at TEXT NOT NULL, -- latest state event
  PRIMARY KEY(plan_id, asset_id), -- one asset allocation in this snapshot plan
  UNIQUE(plan_id, sequence),
  FOREIGN KEY(plan_id, batch_id) REFERENCES ms_image_batch(plan_id, id),
  CHECK(status <> 'PROCESSING' OR (attempt_token IS NOT NULL AND lease_until IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS idx_image_batch_state ON ms_image_batch(plan_id, status);
CREATE INDEX IF NOT EXISTS idx_image_task_batch ON ms_image_batch_task(batch_id, sequence);
CREATE INDEX IF NOT EXISTS idx_image_task_expiry ON ms_image_batch_task(status, lease_until);

-- M5.3 manual handoff is independent of M5.2 processing leases.
CREATE TABLE IF NOT EXISTS ms_muse_job (
  job_id TEXT PRIMARY KEY, -- stable controlled UUID
  batch_id TEXT NOT NULL UNIQUE REFERENCES ms_image_batch(id), -- one unambiguous package per batch
  version INTEGER NOT NULL CHECK(version=1), -- no silent regeneration or version upgrade
  status TEXT NOT NULL CHECK(status IN ('CREATING','EXPORT_FAILED','EXPORTED','WAITING_MUSE','PARTIALLY_RECEIVED','LINKS_RECEIVED')),
  context_json TEXT NOT NULL, -- frozen source assets and all original references
  package_json TEXT, -- published manifest with verified original file metadata
  build_token TEXT, -- exporter fencing token, not an image task claim
  lease_until TEXT, -- UTC export reservation deadline, not manual Muse waiting time
  error_code TEXT, -- safe export failure code only
  created_at TEXT NOT NULL, -- UTC creation
  updated_at TEXT NOT NULL -- UTC most recent handoff event
);
CREATE TABLE IF NOT EXISTS ms_muse_result (
  job_id TEXT NOT NULL REFERENCES ms_muse_job(job_id), -- exact exported package
  asset_id TEXT NOT NULL UNIQUE, -- a stable M5.1 asset cannot receive conflicting results
  download_url TEXT NOT NULL, -- exact submitted URL, never downloaded in M5.3
  url_hash TEXT NOT NULL UNIQUE, -- rejects a result URL mapped to multiple assets
  registered_at TEXT NOT NULL, -- original UTC registration time
  status TEXT NOT NULL CHECK(status='LINK_REGISTERED'), -- registration is not quality approval
  download_verified INTEGER NOT NULL DEFAULT 0 CHECK(download_verified=0), -- always false in this stage
  PRIMARY KEY(job_id,asset_id)
);
CREATE INDEX IF NOT EXISTS idx_muse_job_status ON ms_muse_job(status,lease_until);
