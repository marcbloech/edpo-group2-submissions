# ADR 0001: Use Camunda 8 (Zeebe) over Camunda 7 / Operaton

## Status

Accepted

## Context

We need orchestration for the signup and payment flows. Payment involves retries, a 5-minute timeout, error handling, and a human retry decision, so the process state has to survive restarts and be inspectable. In E2, all of this was distributed across service logs and diagnosing a payment failure required reading three sets of logs.

We had to pick between Camunda 7 (embedded engine with Java Delegates) and Camunda 8 (Zeebe as a separate broker with external job workers).

## Decision

Camunda 8 (Zeebe), running as a shared Docker container. Services connect over gRPC and register job workers with `@JobWorker`.

Four arguments why this fits our setup better than Camunda 7:

- Our workers run in separate containers: `ProcessPaymentWorker` in payment (port 8093), `SendNotificationWorker` in notification (port 8094). Camunda 7 Java Delegates run inside the engine's JVM. That would mean either embedding the engine in each service or pulling all domain logic into the process service. Neither works well with our service boundaries (ADR 0002).

- Zeebe uses an append-only event-sourced log (as discussed in the lecture), which fits the event-driven nature more nicely (but the other way around would not be dealbreaker; but this is just a natural better fit).

- Docker Compose already has one container per service. Zeebe slots in as infrastructure. Camunda 7's embedded model suits monoliths more.

- The course will introduce Camunda Cloud in later sessions. Starting on Zeebe means we don't have to rewrite Java Delegates mid-semester (Future Proofing, essentially).

## Consequences

We get workflow visibility through Operate and microservice autonomy through external workers. The cost is four extra infrastructure containers (Zeebe, Operate, Tasklist, Elasticsearch), and a learning curve.
