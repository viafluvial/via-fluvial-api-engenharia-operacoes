INSERT INTO frontend_runtime_snapshot (
  id,
  environment,
  application_name,
  transport_mode,
  runtime_config_present,
  build_version,
  build_commit,
  enabled_service_keys,
  client_timestamp,
  server_timestamp
)
SELECT
  '00000000-0000-0000-0000-000000000101'::uuid,
  'DSV',
  'via-fluvial-app',
  'BACKEND',
  true,
  'seed',
  'seed',
  '[]',
  NULL,
  NOW()
WHERE NOT EXISTS (
  SELECT 1 FROM frontend_runtime_snapshot WHERE application_name = 'via-fluvial-app'
);
