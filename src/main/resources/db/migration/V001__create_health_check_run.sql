CREATE TABLE IF NOT EXISTS health_check_run (
  id UUID PRIMARY KEY,
  environment VARCHAR(16) NOT NULL,
  started_at TIMESTAMPTZ NOT NULL,
  finished_at TIMESTAMPTZ,
  trigger_type VARCHAR(32) NOT NULL,
  initiated_by VARCHAR(120),
  status VARCHAR(24) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_health_check_run_env_started
  ON health_check_run (environment, started_at DESC);
