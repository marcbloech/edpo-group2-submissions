# Exercise 3 - Process Orchestration (Camunda 8)

## Scope
This folder contains the Exercise 3 artifacts:
- BPMN models for signup and payment orchestration
- Team contribution mapping

Implementation note:
- The runnable Spring Boot + Kafka + Camunda 8 microservice implementation is in `02-worldpulse`.
- Exercise 3 artifacts are kept in `03-process-orchestration` for submission clarity.

## BPMN Artifacts
- `src/main/resources/signup-process.bpmn`
- `src/main/resources/payment-process.bpmn`

> **Note:** These BPMN files reflect the E3 submission state. The current production BPMN models (with E5 saga compensation, parallel gateways, and validation error handling) are in `02-worldpulse/process/src/main/resources/`.

## Where the Executable Implementation Lives
- `02-worldpulse/process` deploys the BPMN models and starts process instances from Kafka events.
- `02-worldpulse/signup` contains signup domain workers (`validate-signup`, `activate-account`).
- `02-worldpulse/payment` contains payment worker (`process-payment`).
- `02-worldpulse/notification` contains notification worker (`send-notification`).

## How to Run (Executable Solution)
From repository root:

```bash
cd 02-worldpulse
mvn clean package -DskipTests
docker compose up --build -d                          # local Zeebe
docker compose --env-file .env.cloud up --build -d    # cloud Zeebe
```

Useful endpoints:
- Signup API: `http://localhost:8091/api/signup`
- Operate: `http://localhost:8085` (demo/demo)
- Tasklist: `http://localhost:8086` (demo/demo)

Example request:

```bash
curl -X POST http://localhost:8091/api/signup \
  -H "Content-Type: application/json" \
  -d '{"name":"Alice","email":"alice@example.com","tier":"PRO"}'
```

## Exercise 3 Concepts Covered
- BPMN-based process orchestration with Camunda 8 (Zeebe)
- Event-driven process start via Kafka message ingestion
- Service-task execution through distributed workers in domain services
- Conditional flow for FREE vs PRO/ENTERPRISE signup paths
- Delegated payment flow via call activity (`signup-process` -> `payment-process`)
- Error/timeout handling and retry decision in payment process

## Evidence Pointers
- Process models: `03-process-orchestration/src/main/resources/*.bpmn`
- Orchestration deployment and start logic: `02-worldpulse/process`
- Design decisions: `02-worldpulse/docs/adr`
