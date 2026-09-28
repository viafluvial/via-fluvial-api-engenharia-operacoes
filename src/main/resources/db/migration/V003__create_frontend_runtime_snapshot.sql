CREATE TABLE IF NOT EXISTS frontend_runtime_snapshot (
  id UUID PRIMARY KEY,
  environment VARCHAR(16) NOT NULL,
  application_name VARCHAR(120) NOT NULL,
  transport_mode VARCHAR(20) NOT NULL,
  runtime_config_present BOOLEAN NOT NULL,
  build_version VARCHAR(60),
  build_commit VARCHAR(60),
  enabled_service_keys TEXT NOT NULL,
  client_timestamp TIMESTAMPTZ,
  server_timestamp TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_frontend_runtime_snapshot_env_time
  ON frontend_runtime_snapshot (environment, server_timestamp DESC);
