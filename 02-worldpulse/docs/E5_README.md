# E5: Sagas and Stateful Resilience in WorldPulse

## Scope
This document covers the implemented E5 improvements in the production flow under `02-worldpulse`.

## Saga Pattern (Orchestrated)
WorldPulse uses an orchestration-based saga in Camunda 8:
- Parent process: `signup-process`
- Child process: `payment-process` (called via BPMN call activity)
- Compensation command: `refund-payment` (payment bounded context worker)

For paid signups, the flow is modeled as a bounded subprocess:
- `SubProcess_PaidSignupTransaction` contains payment execution and post-payment account activation.
- A boundary error event on this subprocess catches `ACTIVATION_ERROR`.
- Compensation path runs `refund-payment`, then sends failure notification, then ends in compensated failure.

This keeps orchestration centralized while preserving service boundaries.

## Compensation Flow
1. User enters paid path (`paymentRequired = true`).
2. `payment-process` completes payment and returns payment variables.
3. `activate-account` runs in signup bounded context.
4. If activation fails with BPMN error `ACTIVATION_ERROR`, boundary on paid subprocess triggers.
5. `refund-payment` runs in payment service.
6. Notification service sends `AccountActivationFailedEvent` message.
7. Process ends at compensated failure end event.

For free tier activation failures, no payment compensation is needed; the process sends failure notification and ends with failure.

## Resilience Patterns Implemented
- Stateful retry with human intervention:
  - `payment-process` includes user decision task (`Task_RetryDecision`) and explicit loop.
  - BPMN-level retry counter is modeled with `retryCount` and `maxRetryCount`.
- Dead-letter fallback:
  - Kafka-to-Zeebe ingress retries process start 3 times.
  - If all attempts fail, `ProcessStartFailedEvent` is published to `worldpulse-dead-letter`.
- Non-blocking notification fallback:
  - Notification worker catches dispatch errors and sets fallback process variables instead of blocking the saga.
- Saga compensation:
  - Activation failure after payment triggers refund compensation.

## Failure Scenarios
### Scenario A: Payment succeeds, activation fails
`payment-process success -> activate-account error -> refund-payment -> notify activation failed -> compensated end`

### Scenario B: Zeebe unavailable at ingress
`SignupRequestedEvent -> process start retry (3x) -> dead-letter event`

### Scenario C: Notification service transient failure
`send-notification command -> worker fallback -> process continues (non-blocking)`

## Outbox Decision (Trade-off)
Current implementation uses best-effort Kafka publish inside workers (without transactional outbox table).

Decision for this assignment:
- Keep best-effort event publication.
- Rationale: no shared transactional datastore across services in this setup, limited exercise scope, and primary consistency is controlled by Zeebe command orchestration.
- Trade-off: event publication can be lost after business state success but before Kafka ack.
- Mitigation in place: structured logs, dead-letter for process start failures, explicit compensation commands for payment inconsistency.

Future hardening path:
1. Add per-service outbox table and outbox relay publisher.
2. Make worker completion contingent on durable outbox write.
3. Add idempotent consumers with deduplication keys.

## Files
- `process/src/main/resources/signup-process.bpmn`
- `process/src/main/resources/payment-process.bpmn`
- `signup/src/main/java/.../workers/ValidateSignupWorker.java`
- `signup/src/main/java/.../workers/AssessPaymentEligibilityWorker.java`
- `payment/src/main/java/.../workers/ProcessRefundWorker.java`
- `process/src/main/java/.../messages/MessageListener.java`
