# WorldPulse: Local vs Cloud Deployment

WorldPulse supports two deployment modes for Camunda 8 (Zeebe):

| | Local (selfManaged) | Cloud (SaaS) |
|---|---|---|
| Zeebe | Docker container (`zeebe:26500`) | Camunda 8 Cloud cluster |
| Operate | `http://localhost:8085` (demo/demo) | Camunda Console |
| Tasklist | `http://localhost:8086` (demo/demo) | Camunda Console |
| Auth | None (plaintext gRPC) | OAuth2 via Camunda Cloud |
| Kafka | Docker container (`kafka:9092`) | Docker container (always local) |

## Switching Modes

```bash
# Local (default)
docker compose up --build -d

# Cloud
docker compose --env-file .env.cloud up --build -d
```

That's it. The `--env-file` flag is the only difference.

## Local Mode (default)

Everything runs in Docker. No credentials needed.

```bash
cd 02-worldpulse
mvn clean package -DskipTests
docker compose up --build -d
```

**Endpoints:**
- Signup form: http://localhost:8091/
- Signup API: `POST http://localhost:8091/api/signup`
- Operate: http://localhost:8085 (login: demo/demo)
- Tasklist: http://localhost:8086 (login: demo/demo)

## Cloud Mode (SaaS)

1. Create `.env.cloud` from the example (or use the one provided by the team):

```bash
cp .env.example .env.cloud
# Fill in your Camunda Cloud credentials from Camunda Console
```

2. Build and start:

```bash
mvn clean package -DskipTests
docker compose --env-file .env.cloud up --build -d
```

Docker Compose picks up the cloud credentials and services connect to your Camunda 8 Cloud cluster via OAuth-authenticated gRPC.

**Note:** In cloud mode, the local Zeebe, Operate, Tasklist, and Elasticsearch containers still start but are unused by the application services. View process instances in Camunda Console instead.

## Testing a Signup

```bash
# Happy path (free tier)
curl -X POST http://localhost:8091/api/signup \
  -H "Content-Type: application/json" \
  -d '{"name":"Alice","email":"alice@example.com","tier":"FREE"}'

# Paid path
curl -X POST http://localhost:8091/api/signup \
  -H "Content-Type: application/json" \
  -d '{"name":"Bob","email":"bob@example.com","tier":"PRO"}'

# Saga compensation test (force activation failure after payment)
curl -X POST http://localhost:8091/api/signup \
  -H "Content-Type: application/json" \
  -d '{"name":"Charlie","email":"charlie@example.com","tier":"PRO","forceActivationFailure":true}'
```

Or use the interactive form at http://localhost:8091/ (has a checkbox for forcing activation failure).

## Checking Logs

```bash
# All services
docker compose logs -f

# Specific service
docker compose logs -f process      # BPMN deployment + Kafka-to-Zeebe bridge
docker compose logs -f signup       # validate-signup + activate-account workers
docker compose logs -f payment      # process-payment + refund-payment workers
docker compose logs -f notification # send-notification worker
```

## How the Config Switch Works

Each service's `application.properties` defaults to `selfManaged` with empty auth:

```properties
camunda.client.mode=${CAMUNDA_CLIENT_MODE:selfManaged}
camunda.client.auth.client-id=${CAMUNDA_CLIENT_ID:}
camunda.client.auth.client-secret=${CAMUNDA_CLIENT_SECRET:}
camunda.client.auth.issuer=${CAMUNDA_OAUTH_URL:}
```

When `--env-file .env.cloud` is used, Docker Compose injects the cloud credentials as environment variables, overriding these defaults. No code or config file changes needed.

## File Reference

| File | Purpose |
|---|---|
| `.env` | Default env (local mode) — committed |
| `.env.local` | Explicit local mode env — committed |
| `.env.cloud` | Cloud credentials — **gitignored** |
| `.env.example` | Template for creating `.env.cloud` — committed |
