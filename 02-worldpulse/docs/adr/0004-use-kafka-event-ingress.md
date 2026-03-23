# ADR 0004: Use Kafka as Event Ingress to Orchestration

- Status: Accepted
- Date: 2026-03-17

## Context
Signup requests already produce domain events and the architecture is event-driven.
We need orchestration to start from the same integration style without introducing a second synchronous entry pattern.

## Decision Drivers
- Reuse existing event contracts
- Keep loose coupling between signup and orchestration
- Preserve asynchronous behavior under load

## Decision
Keep Kafka topic consumption in the process service.
Start signup-process instances from SignupRequestedEvent messages.

## Project Implementation
- Kafka topic: worldpulse
- Trigger event: SignupRequestedEvent
- Consumer identity in process service: worldpulse-process
- Process start logic reads message envelope fields (type, traceid, data) and maps data.name/data.email/data.tier into process variables.
- Trace IDs are propagated through process variables and used in downstream logs for observability.
- API entry remains in signup service (POST /api/signup), which publishes the event and returns traceId.

## Alternatives Considered
- Start process directly from signup service via synchronous API/gRPC call

This was rejected because it increases coupling and creates tighter runtime dependencies.

## Consequences
- Existing event-driven style is preserved.
- Orchestration is triggered asynchronously and consistently.
- Process starts are traceable by event trace identifiers.
- Delivery guarantees and idempotency should be handled in consumers and process-start logic.
- End-to-end verification can be done by correlating one traceId across signup, process, payment, and notification logs.
