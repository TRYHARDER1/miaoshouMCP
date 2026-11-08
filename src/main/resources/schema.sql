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


-- M5.4 independent result integrity state; M5.3 registry remains unchanged.
BEGIN IMMEDIATE;
CREATE TABLE IF NOT EXISTS ms_muse_processed_result (
  job_id TEXT NOT NULL,                         -- M5.3 固定 job
  asset_id TEXT NOT NULL,                       -- 原 assetId，身份不重新计算
  source_url_hash TEXT NOT NULL CHECK(length(source_url_hash)=64), -- 登记表既有 url_hash，不存完整 URL
  original_sha256 TEXT NOT NULL CHECK(length(original_sha256)=64), -- 原图冻结摘要
  status TEXT NOT NULL CHECK(status IN
    ('PENDING','DOWNLOADING','DOWNLOADED','VALIDATING','VALIDATED','FAILED')), -- 独立技术状态
  row_version INTEGER NOT NULL DEFAULT 0 CHECK(row_version>=0),   -- 乐观并发版本
  attempt_no INTEGER NOT NULL DEFAULT 0 CHECK(attempt_no>=0),     -- 每次获得新所有权递增
  retry_count INTEGER NOT NULL DEFAULT 0 CHECK(retry_count>=0),   -- 明确网络重试次数
  attempt_token TEXT,                           -- 当前/最后尝试标识，旧尝试不得提交
  lease_until TEXT,                             -- 本阶段 UTC 固定毫秒期限
  staging_relative_path TEXT,                   -- 专用组件生成的私有相对目录
  processed_relative_path TEXT UNIQUE,          -- 根目录内唯一结果路径，非远程文件名
  http_status INTEGER CHECK(http_status BETWEEN 100 AND 599),     -- 最近有效 HTTP 状态，允许 NULL
  media_type TEXT,                              -- 通过白名单后的实际媒体类型
  actual_bytes INTEGER CHECK(actual_bytes>=0),   -- 实际落盘字节数，非信任 Content-Length
  width INTEGER CHECK(width>0),                 -- 实际解码宽度，允许 NULL
  height INTEGER CHECK(height>0),               -- 实际解码高度，允许 NULL
  processed_sha256 TEXT CHECK(length(processed_sha256)=64),       -- 完整结果文件摘要
  validator_version TEXT,                       -- 技术校验规则版本
  error_code TEXT,                              -- 固定安全错误码，不含 URL/异常原文
  failure_phase TEXT CHECK(failure_phase IN
    ('CONTEXT','FETCH','VALIDATE','PUBLISH','RECOVER','RECHECK')),   -- 最近失败阶段，允许 NULL
  download_started_at TEXT,                     -- 最近网络尝试开始
  downloaded_at TEXT,                           -- 完整字节已保存
  validated_at TEXT,                            -- 技术校验及发布确认时间
  created_at TEXT NOT NULL,                     -- UTC 首次创建
  updated_at TEXT NOT NULL,                     -- UTC 最近变化/心跳
  PRIMARY KEY(job_id,asset_id),
  FOREIGN KEY(job_id,asset_id) REFERENCES ms_muse_result(job_id,asset_id), -- 禁止未登记源
  CHECK(status NOT IN ('DOWNLOADING','DOWNLOADED','VALIDATING') OR
    (attempt_token IS NOT NULL AND lease_until IS NOT NULL)),
  CHECK(status<>'VALIDATED' OR
    (processed_relative_path IS NOT NULL AND length(processed_relative_path)>0
     AND actual_bytes IS NOT NULL AND actual_bytes>0
     AND width IS NOT NULL AND width>0 AND height IS NOT NULL AND height>0
     AND media_type IS NOT NULL AND media_type IN ('image/jpeg','image/png')
     AND processed_sha256 IS NOT NULL AND validator_version IS NOT NULL
     AND length(validator_version)>0 AND validated_at IS NOT NULL AND lease_until IS NULL))
);
CREATE INDEX IF NOT EXISTS idx_processed_status_lease
  ON ms_muse_processed_result(status,lease_until); -- 恢复过期尝试
CREATE INDEX IF NOT EXISTS idx_processed_job_status
  ON ms_muse_processed_result(job_id,status);      -- 批次进度
CREATE INDEX IF NOT EXISTS idx_processed_content_sha
  ON ms_muse_processed_result(processed_sha256);   -- 重复内容提示，故意非唯一

CREATE TABLE IF NOT EXISTS ms_muse_processed_event (
  event_id INTEGER PRIMARY KEY AUTOINCREMENT,    -- 本地事件顺序
  job_id TEXT NOT NULL,                         -- 关联当前状态行
  asset_id TEXT NOT NULL,                       -- 原身份
  row_version INTEGER NOT NULL CHECK(row_version>=0), -- 对应提交后的版本
  attempt_no INTEGER NOT NULL CHECK(attempt_no>=0),   -- 关联尝试
  from_status TEXT CHECK(from_status IN
    ('PENDING','DOWNLOADING','DOWNLOADED','VALIDATING','VALIDATED','FAILED')), -- 首次事件为 NULL
  to_status TEXT NOT NULL CHECK(to_status IN
    ('PENDING','DOWNLOADING','DOWNLOADED','VALIDATING','VALIDATED','FAILED')),
  event_code TEXT NOT NULL,                     -- CREATED/CLAIMED/DOWNLOADED/VALIDATED/RECOVERED 等受控代码
  error_code TEXT,                              -- 可空，脱敏原因
  created_at TEXT NOT NULL,                     -- UTC 提交事件时间
  FOREIGN KEY(job_id,asset_id) REFERENCES ms_muse_processed_result(job_id,asset_id),
  UNIQUE(job_id,asset_id,row_version)            -- 同一状态更新事件不重复
);
CREATE INDEX IF NOT EXISTS idx_processed_event_asset
  ON ms_muse_processed_event(job_id,asset_id,event_id); -- 资产恢复/审计
COMMIT;
