# ADR 0004: Use Kafka as system-wide event backbone

## Status

Accepted (updated 2026-03-23 (POST SUBMISSION) to reflect broadened integration)

## Context

We use Zeebe for orchestration inside the signup/payment flow and Kafka for event-driven communication at service boundaries (hybrid pattern).

Initially, Kafka only sent `SignupRequestedEvent` into the process service. But payment results, notification outcomes, and account activations were all trapped inside Zeebe, invisible to anything outside the orchestrated flow.

## Decision

Now, every service publishes business events to the shared `worldpulse` topic after completing its Zeebe job for alter down-stream usage.

| Event                     | Publisher                | What it is for              |
| ------------------------- | ------------------------ | --------------------------- |
| `SignupRequestedEvent`    | Signup REST handler      | Triggers orchestration      |
| `PaymentReceivedEvent`    | `ProcessPaymentWorker`   | Payment success             |
| `PaymentFailedEvent`      | `ProcessPaymentWorker`   | Payment failure             |
| `NotificationSentEvent`   | `SendNotificationWorker` | Audit trail                 |
| `NotificationFailedEvent` | `SendNotificationWorker` | Alerting                    |
| `AccountActivatedEvent`   | `ActivateAccountWorker`  | Analytics, downstream flows |
| `ProcessStartFailedEvent` | Process service DLQ      | Recovery                    |

All events use the CloudEvents envelope (`Message<T>`) with a `type` header for filtering and `traceid` for end-to-end correlation.

## Consequences

All four services now publish to Kafka, so adding a new consumer (audit, analytics) doesn not require touching existing code but instead our orchestration can easily be adapted.

Since publishing is best-effort, events can be lost if Kafka is unavailable during a Zeebe job. That is acceptable for use cases like analytics, but would be troublesome for anything critical.  And the single shared topic is sufficient for our four services but would need partitioning at larger scale.
