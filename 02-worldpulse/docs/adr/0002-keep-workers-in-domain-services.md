# ADR 0002: Keep job workers in domain services

## Status

SUPERSEDED (31.03.2026) by 01XX ADRs

## Context

Zeebe job workers run the business logic behind BPMN service tasks. We could put them all in the process service, or keep them in the services that own the logic.

Centralizing them would turn the process service into a huge monolith-service containing payment simulation, notification dispatch, and signup validation. That's the process anti-pattern discussed in the lecutre, and it breaks the "smart endpoints, dumb pipes" principle: our engine coordinates flow, it should not host the business logic.

## Decision

Workers stay in the services that own the domain:

| Worker                   | Service             | Job type            |
| ------------------------ | ------------------- | ------------------- |
| `ValidateSignupWorker`   | signup (8091)       | `validate-signup`   |
| `ActivateAccountWorker`  | signup (8091)       | `activate-account`  |
| `ProcessPaymentWorker`   | payment (8093)      | `process-payment`   |
| `SendNotificationWorker` | notification (8094) | `send-notification` |

The process service (8095) deploys BPMN and starts process instances from Kafka events.

Workers also publish business events to Kafka after completing their Zeebe job (ADR 0004), so each service participates in both orchestration and choreography (NOTE: THIS WAS NEWLY ADDED  AFTER E4 submission!).

## Consequences

Domain logic stays close to domain code, and logs appear in the right place. The process service stays lean. On the other hand, every service now needs the Camunda SDK and Zeebe config. And if a worker type string in the BPMN doesn't match the `@JobWorker` annotation in Java, nothing happens at runtime (We caught this twice during development).
