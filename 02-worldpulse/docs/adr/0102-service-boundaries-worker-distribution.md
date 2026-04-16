# [ADR-0102] Service Boundaries & Granularity

## Context

After adopting Camunda 8 (ADR-0104), we needed to decide where job workers are located and how processes map to bounded contexts.

The simplest approach would be to place all workers in the process application. For WorldPulse, centralizing `ValidateSignupWorker`, `ProcessPaymentWorker`, `SendNotificationWorker`, and `ActivateAccountWorker` into one process service would turn it into a God service containing payment simulation, notification dispatch, signup validation, and account activation logic. This would be exactly the process monolith anti-pattern. Instead, the engine should coordinate flow, not host business logic ("smart endpoints, dumb pipes").

Additionally, one feedback received was whether user account activation should really belong to the payment bounded context (based on our initial design); hinting at a bounded context violation where `activate-account` was (perhaps) incorrectly placed inside `payment-process`.

## Decision

We will distribute workers to the services that own the domain logic and split the process logic into two processes mapped to bounded contexts.

**Worker placement:**

| Worker                   | Service      | Job type            |
| ------------------------ | ------------ | ------------------- |
| `ValidateSignupWorker`   | signup       | `validate-signup`   |
| `ActivateAccountWorker`  | signup       | `activate-account`  |
| `ProcessPaymentWorker`   | payment      | `process-payment`   |
| `SendNotificationWorker` | notification | `send-notification` |

We create two processes linked by a Zeebe call activity:

- `signup-process`: validation, tier branching, notification, account activation
- `payment-process`: payment handling, retry logic, 5-minute timeout, human retry decision

For paid user tiers, the `signup-process` calls `payment-process` via call activity and blocks until it completes. `activate-account` executes in `signup-process` then again after the call activity returns.

**Alternatives considered:**

- **O1: Centralize all workers in one process service** rejected, because: creates a God service mixing payment, notification, and signup logic in one codebase. Violates bounded contexts and "smart endpoints, dumb pipes."

- **O2: Separate independent processes via Kafka only** rejected, because: signup must wait for payment result before activation. A pure choreography would hide this mandatory and important coupling.

## Status

Accepted

## Consequences

**Good:**

- Code Cleanliness: Domain logic stays close to domain code and tests. `ProcessPaymentWorker` is in the payment codebase alongside `PaymentService`; `ValidateSignupWorker` is in the signup codebase alongside `SignupRestController`.
- Monitoring: Logs for payment processing appear in the payment service container, notification logs in the notification container — no need to search a monolithic log.
- Evolvability: Both processes can evolves independently. Adding a fraud-check step means changing only one BPMN file without touching the signup flow.

**Bad:**

- Complexity: Whilst adhering to the "smart endpoints, dumb pipes" principle, this creates more complexity that all developers need to be aware of and adhere to the principles inherent in these decisions (and not start mixing granularity levels or service boundaries in future developments)
