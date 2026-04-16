# [ADR-0101]Orchestration vs Choreography in WorldPulse

## Context

WorldPulse's signup flow has two different coupling requirements:

1. **Signup-to-payment (tight coupling):** A paid user (Pro or Enterprise) should not be activated without a successful payment. The signup process must wait for the payment result. Based on the heuristic of "Is it OK if the message is ignored?"; the answer is a clear no and makes this step a command, not an event.

2. **Payment/notification outcomes to downstream consumers (loose coupling):** A PaymentReceivedEvent consumed by downstream services is optional. If the consumer is down, the central flow is unaffected. Therefore, here the answer to the ignorance question is "yes"; the message can be ignored. This makes it an event suited for choreography.

**Coupling boundaries (resolving an apparent contradiction):**

At first glance it may look inconsistent that the signup flow starts from a Kafka event (`SignupRequestedEvent`) even though the signup-to-payment process itself is tightly coupled. The contradiction dissolves once coupling is evaluated per context (in terms of boundaries) rather than per message. 
The "can this message be ignored?" heuristic applies to the *producer↔consumer* contract. The frontend publishes `SignupRequestedEvent` as a fact without knowing or caring who processes it. Any individual subscriber (analytics, audit, the signup orchestrator) may be offline; Kafka's durable log lets them catch up later. From the producer's perspective the message is ignorable.
Once the signup orchestrator decides to act on the event, the steps that follow (`validate-signup` → `process-payment` → `activate-account`) form a single business transaction where partial completion is unacceptable. Inside this boundary, steps are modeled as commands, because Zeebe must guarantee they all run (with retries, timeouts, compensations) or none.
After orchestration completes, outcomes (`PaymentReceivedEvent`, `AccountActivatedEvent`, …) are published back to Kafka as facts that the rest of the system is again free to ignore.

In short: In our project, **Kafka decouples services; Zeebe couples steps within one service's workflow.** The overall flow is Kafka → Zeebe → Kafka, and each transition deliberately crosses a coupling boundary. The ignorability heuristic is not violated; it is simply applied at the boundary where it is meaningful (between independent services), not inside a transactional process.

**Additional decision drivers:**

- Reliability: payment outcome must be guaranteed before an account activation
- Loose coupling: downstream consumers (notification, analytics, audit, etc) must not require changes to existing producers but can evolve freely
- Horizontal extensibility: adding new consumers without modifying existing services



So the key question is: To use orchestration, or choreography?

## Decision

Our WorldPulse project will use a **hybrid pattern** for different parts of the project: Orchestration via Zeebe for the tightly coupled signup-to-payment flow, and choreography via Kafka for broadcasting events for downstream processing.

**Orchestration (Zeebe):** Our signup-process.bpmn calls payment-process.bpmn via call activity. The signup process blocks until payment completes. Inside each process, Zeebe issues commands to job workers: `validate-signup`, `process-payment`, `send-notification`, `activate-account` .

**Choreography (Kafka):** After completing their Zeebe job, workers publish business events to the shared `worldpulse` topic. Event types include `SignupRequestedEvent`, `UpgradeRequestedEvent`, `AccountDeactivationRequestedEvent`, `PaymentReceivedEvent`, `PaymentFailedEvent`, `PaymentRefundedEvent`, `PaymentRefundFailedEvent`, `NotificationSentEvent`, `NotificationFailedEvent`, `AccountActivatedEvent`, plus scanner events (`MarketAlertEvent`, `SocialTrendEvent`). Any service can subscribe without modifying producers.

**Ingress Bridge:** The process service's `MessageListener` consumes multiple ingress events from Kafka and starts the matching Zeebe process instance:
- `SignupRequestedEvent` -> `signup-process`
- `UpgradeRequestedEvent` -> `upgrade-process`
- `AccountDeactivationRequestedEvent` -> `deactivation-process`

For resilience: If Zeebe is unavailable, it retries 3 times (500ms backoff). If all retries fail, `ProcessDeadLetterPublisher` sends a `ProcessStartFailedEvent` (CloudEvents envelope + `type` header) to the `worldpulse-dead-letter` topic for later investigation.

**Alternatives considered:**

- **O1: Pure orchestration (everything via Zeebe)** rejected, because: downstream consumers (notification Kafka listener, future services) would be tightly coupled to the process engine. Adding an (e.g. analytics) consumer would require modifying the BPMN process, and thus reducing horizontal extensibility significantly. When aiming to add different consumers that listen to diverse sources for information (e.g. stock market trackers, Twitter scanners, etc), this becomes a bottleneck.

- **O2: Pure choreography (all interactions via Kafka events, including signup-to-payment)** rejected, because: publishing a Kafka event and waiting for a payment response would still create runtime dependency (i.e., the signup must wait for payment), but hidden behind Kafka messages. Timeout and error handling across the async boundary would have to replicate what Zeebe handles already natively. The coupling is thus already real and the call activity makes it explicit and visible in Operate.

## Status

Accepted

## Consequences

**Good:**

- Accuracy: Payment outcome is guaranteed before any account activation. The call activity blocks the signup process until the payment process completes without race conditions.
- Extensibility: Adding a new downstream consumer (audit, analytics, stock service) requires only a new Kafka consumer group subscribing to the `worldpulse` topic without any changes to existing producers or the BPMN process.
- Observability: Operate shows the orchestrated flow and the Kafka topic shows the event stream for all published business events. The `traceid` in CloudEvents correlates across both mechanisms.
- Lecture Adherence: We follow the lecture's conceptual framework: commands enable orchestration, events enable choreography.

**Bad:**

- Complexity: Two coordination mechanisms (Zeebe + Kafka) to operate, monitor, and debug. We must keep track of which interactions are orchestrated and which are choreographed.
- Complexity 2: The Kafka-to-Zeebe bridge mechanism (`MessageListener`) requires retry logic and dead-letter handling. This adds implementation complexity.
- Reliability: Publishing Kafka Events from workers after the Zeebe job completion is "just" best-effort: if Kafka is unavailable during a Zeebe job, the event is lost. This is acceptable for non-critical consumers but would be problematic for anything requiring at-least-once delivery - but which we do not have at the moment in the downstream tasks (the most critical would be the bridge between signup and payment, and that is taken care of in the orchestration part within Zeebe).
- 
