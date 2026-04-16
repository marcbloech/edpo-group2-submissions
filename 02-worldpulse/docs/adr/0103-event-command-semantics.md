# [ADR-0103] Semantic Command/Event Separation with CloudEvents on a Shared Topic

## Context

In our WorldPulse tool, we use the two fundamentally different types of inter-service messages discussed in the Lecture (each with different coupling characteristics, delivery guarantees, and failure semantics):

- **Commands** shall be directed at a specific receiver, must be handled, and failure is exceptional. They represent *intent*; wanting something to happen. As Lecture 2 stresses, commands are characterized by their *semantics, not their technical protocol* — they can be transmitted synchronously or asynchronously, over any transport. The Flowing Retail lab demonstrates this: `RetrievePaymentCommand` travels over Kafka, the same bus used for events. In WorldPulse, we chose to issue commands via Zeebe job activation (e.g., `process-payment` job directed at `ProcessPaymentWorker`), but this is a design choice, not a necessity.

- **Events** ("payment succeeded") are broadcast to anyone interested, can be safely ignored, and represent facts about what happened. They are past-tense and immutable. In WorldPulse, events are published to the Kafka `worldpulse` topic (e.g., `PaymentReceivedEvent` published by `ProcessPaymentWorker` after completing its Zeebe job).

This ADR establishes the semantic separation and documents how and why every WorldPulse message is classified.

**Business events vs engine events**:

- *Business events* cross bounded context boundaries via Kafka. They represent our domain-level facts: `SignupRequestedEvent`, `UpgradeRequestedEvent`, `AccountDeactivationRequestedEvent`, `PaymentReceivedEvent`, `AccountActivatedEvent`.
- *Engine events* are internal to the orchestration layer managed by Zeebe: a 5-minute timer boundary event on `Task_ProcessPayment`, the `Error_PaymentProcessing` error boundary, job activation and completion signals.

**Ingress events vs triggered orchestration (see ADR-0101):**

Three of the messages classified below (`SignupRequestedEvent`, `UpgradeRequestedEvent`, `AccountDeactivationRequestedEvent`) are events that *start* an orchestrated, tightly coupled process. They are still classified as events, not commands, because classification applies at the integration boundary between services, not at the execution boundary inside a single service. From the producer's perspective the message is ignorable — any individual subscriber (audit, analytics, the orchestrator) may be offline and catch up later via Kafka's durable log. Once the Process Service consumes such an event, it translates it into Zeebe commands internally, and those commands live under the stricter "must be handled" rule. See ADR-0101 "Coupling boundaries" for the full argument.

## Decision

We will use **Kafka for events** (broadcast, best-effort) and **Zeebe job activation for commands** (directed, guaranteed). This transport mapping is a design choice — commands could equally travel over Kafka (as Flowing Retail's `RetrievePaymentCommand` demonstrates), but Zeebe gives us built-in timeout, retry, and process state tracking for command interactions. All Kafka messages use the CloudEvents envelope. Business events are published on shared topic `worldpulse`; recovery events are published on `worldpulse-dead-letter`.

**Classification of every WorldPulse message:**

| Message                   | Type    | Mechanism | Reasoning                                                                                                                  |
| ------------------------- | ------- | --------- | -------------------------------------------------------------------------------------------------------------------------- |
| `SignupRequestedEvent`    | Event   | Kafka     | "A signup was requested" (fact). Starts orchestration when consumed by Process Service; independently consumable by audit/analytics. Classification follows the integration boundary — see Context and ADR-0101 |
| `UpgradeRequestedEvent`   | Event   | Kafka     | "A tier upgrade was requested". Starts orchestration for payment + upgrade application                                     |
| `AccountDeactivationRequestedEvent` | Event | Kafka | "An account deactivation was requested". Starts orchestration for deactivation flow                                        |
| `validate-signup` job     | Command | Zeebe     | "Validate this signup now". Must be handled by Signup                                                                      |
| `process-payment` job     | Command | Zeebe     | "Process this payment". Directed to Payment, failure is exceptional.                                                       |
| `refund-payment` job      | Command | Zeebe     | "Compensate payment". Directed to Payment when activation fails after charge.                                               |
| `send-notification` job   | Command | Zeebe     | "Send this notification". Directed to notification, non-blocking fallback on failure                                       |
| `activate-account` job    | Command | Zeebe     | "Activate this account". Directed to signup, must succeed                                                                  |
| `apply-tier-upgrade` job  | Command | Zeebe     | "Apply this tier upgrade". Directed to signup after successful upgrade payment                                              |
| `deactivate-account` job  | Command | Zeebe     | "Deactivate this account". Directed to signup                                                                               |
| `PaymentReceivedEvent`    | Event   | Kafka     | "Payment succeeded". Broadcast for notification, analytics or any other interested consumers                               |
| `PaymentFailedEvent`      | Event   | Kafka     | "Payment failed" Broadcast for alerting etc.                                                                               |
| `PaymentRefundedEvent`    | Event   | Kafka     | "Payment compensation succeeded" for audit/reconciliation                                                                   |
| `PaymentRefundFailedEvent`| Event   | Kafka     | "Payment compensation failed" for support follow-up                                                                         |
| `NotificationSentEvent`   | Event   | Kafka     | "Notification was sent" (audit trail)                                                                                      |
| `NotificationFailedEvent` | Event   | Kafka     | "Notification failed" (alerting)                                                                                           |
| `AccountActivatedEvent`   | Event   | Kafka     | "Account is now active" downstream flows                                                                                   |
| `MarketAlertEvent`        | Event   | Kafka     | "Significant market price movement detected". Published by market-scanner for notification dispatch                          |
| `SocialTrendEvent`        | Event   | Kafka     | "Social trend or sentiment spike detected". Published by bluesky-scanner for notification dispatch                           |
| `ProcessStartFailedEvent` | Event   | Kafka     | "Process could not start". Published to `worldpulse-dead-letter` for recovery                                               |

**Naming convention:** Events use past-tense; commands use imperative job types (`validate-signup`, `process-payment`). 



**Topic structure alternatives:**

- **O2: Per-service topics** (`worldpulse-signup`, `worldpulse-payment`, etc.) — rejected: at our scale, multiple topics add operational overhead (monitoring, consumer group management) without meaningful isolation benefit; a single topic simplifies infrastructure and monitoring.
- **O3: Per-event-type topics** — rejected: 15 topics for 6 services is over-engineered for a single-broker setup; a single topic simplifies infrastructure and monitoring.

## Status

Accepted

## Consequences

**Good:**

- Clear semantic boundary: in our implementation, messages on Kafka are events (ignorable at the integration boundary — any given subscriber may or may not act on them), messages from Zeebe job activation are commands (must be handled inside the execution boundary of a running process). The distinction is semantic — Kafka could carry commands too (as in Flowing Retail) — but our transport split makes the semantics visible at the infrastructure level. See ADR-0101 "Coupling boundaries" for the framing.
- CloudEvents `type` header enables the notification service's `MessageListener` to filter for relevant events (`SignupRequestedEvent`, `PaymentReceivedEvent`, `PaymentFailedEvent`, `MarketAlertEvent`, `SocialTrendEvent`) without deserializing every message on the topic.
- New consumers (audit, analytics, stock service) subscribe to the existing `worldpulse` topic with a new consumer group. No changes to any existing producer.

**Bad:**

- Kafka publishing from workers is best-effort: If Kafka is unavailable when the Zeebe job completes, the event is lost. The Zeebe job still succeeds, the process flow continues, but downstream Kafka consumers miss the event.
- The single `worldpulse` topic is sufficient for our 6-service, 15-event-type setup but would arguably need a topic-per-domain split at significantly larger scale.
