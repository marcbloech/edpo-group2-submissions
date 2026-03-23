# WorldPulse: Exercise 4 - Orchestration vs Choreography

This README documents how Exercise 4 is demonstrated in our WorldPulse project.

## What is included
- Choreography baseline from Exercise 2
- Orchestration flow from Exercise 3 (Camunda 8 / Zeebe)
- Side-by-side comparison using the same domain flow (signup -> payment -> notification)

## Project mapping
- Choreography documentation: `docs/E2_README.md`
- Orchestration documentation: `docs/E3_README.md`
- BPMN models:
	- `process/src/main/resources/signup-process.bpmn`
	- `process/src/main/resources/payment-process.bpmn`

## Quick comparison
| Topic | Choreography (E2) | Orchestration (E3) |
|---|---|---|
| Flow owner | Services coordinate through events | Camunda process model controls flow |
| State visibility | Service logs + Kafka events | Operate process instances |
| Error/retry logic | Distributed in services | Explicit in BPMN (timer/error/retry) |
| Human interaction | Custom implementation needed | Native Tasklist user task support |
| Change style | Event contracts + service changes | BPMN-first workflow updates |

## How to run (common setup)
From repository root:

```bash
cd 02-worldpulse
mvn clean package -DskipTests
docker compose up --build -d
```

## Demo A: Choreography behavior
1. Follow the event-driven flow described in `docs/E2_README.md`.
2. Trigger signup:

```bash
curl -X POST http://localhost:8091/api/signup \
	-H "Content-Type: application/json" \
	-d '{"name":"Alice","email":"alice@example.com","tier":"PRO"}'
```

3. Verify service-to-service event reactions in logs and Kafka topic output.

## Demo B: Orchestration behavior
1. Follow orchestration flow in `docs/E3_README.md`.
2. Trigger signup with the same API.
3. Verify process execution in Operate: `http://localhost:8085`.
4. If retry/human decision is required, use Tasklist: `http://localhost:8086`.

## Expected evidence for Exercise 4
- Choreography: event propagation visible in service logs / Kafka stream
- Orchestration: process instance progression visible in Operate
- Same business scenario demonstrated with both interaction styles

## Deliverable summary
Exercise 4 is covered by showing both approaches in the same project and clearly comparing their operational behavior, observability, and control model.
