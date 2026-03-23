# ADR 0001: Use Camunda 8 for Process Orchestration

- Status: Accepted
- Date: 2026-03-17

## Context
The project requires explicit orchestration for signup and payment flows, including retries, branching, timeouts, and clear monitoring.
Using only service-to-service code would hide process state and make cross-service troubleshooting difficult.

## Decision Drivers
- Process transparency for demos and operations
- Easy visualization of flow logic
- Support for incidents and controlled retries
- Strong fit with event-driven services already in the project

## Decision
Use Camunda 8 (Zeebe) as the orchestration engine.
Run Zeebe in Docker Compose and deploy BPMN models from the process service.

## Project Implementation
- Orchestration deployment is done by the process module at startup.
- Deployed resources are:
	- signup-process.bpmn
	- payment-process.bpmn
	- retryPaymentForm.form
- Runtime stack in docker-compose includes zeebe, operate, tasklist, elasticsearch, and the four app services (signup, payment, notification, process).
- The process service is configured with restart on failure to tolerate early Zeebe startup timing.

## Alternatives Considered
- Pure choreography over Kafka without a process engine
- In-code orchestration in a custom Spring service

Both alternatives were rejected because they reduce visibility and make long-running flow management harder.

## Consequences
- We get observable workflow execution and incident handling.
- We keep orchestration logic in BPMN instead of hardcoding it in service code.
- The runtime stack includes Zeebe, Operate, and Tasklist.
- We accept extra operational complexity compared to a pure code-only flow.
- BPMN changes now require process artifact rebuild/redeploy, which is explicit and traceable.
