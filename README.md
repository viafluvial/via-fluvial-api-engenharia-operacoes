# api-engenharia-operacoes

API agregadora da Central de Engenharia e Operacoes da Plataforma VFA.

## Executar local

```bash
make build
make up
```

Health:
- `GET http://localhost:18020/api/v1/actuator/health`
- `GET http://localhost:18020/api/v1/v3/api-docs`
- `GET http://localhost:18020/api/v1/openapi/openapi.yaml`

## Variaveis principais

- `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_SCHEMA`, `DB_USERNAME`, `DB_PASSWORD`
- `SECURITY_MODE=dev|oauth2`
- `APP_CORS_ALLOWED_ORIGINS`
- `ENGINEERING_CHECK_INTERVAL_SECONDS`
- `ENGINEERING_CHECK_TIMEOUT_MILLIS`
