CREATE TABLE IF NOT EXISTS health_check_result (
  id UUID PRIMARY KEY,
  run_id UUID NOT NULL REFERENCES health_check_run(id) ON DELETE CASCADE,
  service_key VARCHAR(80) NOT NULL,
  service_name VARCHAR(160) NOT NULL,
  category VARCHAR(120) NOT NULL,
  route_type VARCHAR(20) NOT NULL,
  health_state VARCHAR(24) NOT NULL,
  reason_code VARCHAR(48) NOT NULL,
  diagnostic_code VARCHAR(64),
  http_status INTEGER,
  latency_ms BIGINT,
  checked_at TIMESTAMPTZ NOT NULL,
  error_summary VARCHAR(300),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_health_check_result_service_time
  ON health_check_result (service_key, checked_at DESC);

CREATE INDEX IF NOT EXISTS idx_health_check_result_run
  ON health_check_result (run_id);
