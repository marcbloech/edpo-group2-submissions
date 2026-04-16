# [ADR-0104] Camunda 8 (Zeebe) over Camunda 7 for Process Orchestration

## Context

WorldPulse's payment flow involves retries, a 5-minute timeout, error handling, and a human retry decision via Camunda Tasklist. Process state must survive container restarts and be inspectable at runtime. In E2 (pre-orchestration), diagnosing a payment failure required reading the signup, payment, and notification service logs separately, with no central view of where a process instance stood.

We needed a process engine that fits our multi-container Docker Compose setup, where each bounded context (signup, payment, notification) runs as a separate Spring Boot service with its own Dockerfile.

Lecture 4 presented comparison dimensions for Camunda 7 vs 8. Below we evaluate these against our specific requirements.

## Decision

We will use **Camunda 8**, running as a shared Docker container. Services connect over gRPC and register job workers with `@JobWorker` annotations.

| Aspect                    | Camunda 7                                                                         | Camunda 8                                                                                   | WorldPulse verdict                                                                                                                                                                                                                                                                                                                                                |
| ------------------------- | --------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Engine Architecture**   | Embeddable as a **library** inside the application; also supports **External Tasks** (REST long-polling) for remote workers | Zeebe is always a **remote resource**; workers connect via **gRPC long-polling** (with optional server-streaming via `StreamActivatedJobs`) | Both engines can run as a remote resource with external workers. Camunda 7 achieves this via External Tasks (REST `fetchAndLock` with `asyncResponseTimeout` for long-polling); Camunda 8 via gRPC long-polling (`ActivateJobs` RPC). Both avoid busy-waiting. The difference is transport efficiency (gRPC binary/HTTP2 vs REST/JSON) and the fact that Camunda 8 was built for external workers from the ground up. Both work for our scale; **Camunda 8 fits slightly better** as the native path. |
| **Persistence Model**     | State stored in **DB tables** (relational)                                        | Zeebe uses **event sourcing** (append-only log)                                             | Event sourcing aligns with the event-driven architecture, it naturally provides a full audit trail. However, also a relational DB as C7 provides it would work; hence no massive advantage but **Camunda 8 fits.**                                                                                                                                                |
| **Glue Code**             | Supports **Java Delegates** (in-process, Java-only) and **External Tasks** (REST, polyglot) | Supports **workers only** (gRPC, polyglot)                                                  | Java Delegates would force domain logic into the engine's JVM, which we do not want. Both engines support polyglot external workers: Camunda 7 via REST-based External Tasks (any language can call REST), Camunda 8 via gRPC `@JobWorker` (client libs for Java, Go, community support for C#, Python, Node.js). The `@JobWorker` annotation required less setup than configuring a REST external task client. **Camunda 8 fits slightly better.** |
| **Connectors**            | Integration relies on **custom code or HTTP APIs**                                | Provides **ready-to-use connectors** (Email, Slack, ChatGPT, etc.)                          | We currently simulate notifications (console log) and payments, but ready-to-use connectors could reduce integration effort if we connect to real services later. **Advantage for Camunda 8.**                                                                                                                                                                    |
| **Deployment Simplicity** | Can run as a **single embedded application** — easy to deploy in smaller systems  | Requires a **distributed architecture** with Zeebe, Operate, Tasklist, and other components | This is arguably Camunda 7's strongest advantage. Our Docker Compose setup already has 10 containers; Camunda 8 adds four more. Manageable but not entirely trivial. **Camunda 7 would be simpler here.**                                                                                                                                                         |
| **Community Support**     | Fully **open-source** ecosystem, now led by **Operaton**; long-standing community | **Not fully open-source**, oriented toward SaaS; rapidly evolving ecosystem                 | Camunda 7 has more comunity answers and established documentation. Camunda 8's documentation is thinner and still evolving. **Camunda 7 has an advantage here.**                                                                                                                                                                                                  |

**Summary:** Both engines could support our architecture --- Camunda 7 with External Tasks (REST long-polling) achieves the same service boundary separation and polyglot support as Camunda 8 with gRPC workers. We chose Camunda 8 because: (1) its gRPC worker model is the native interaction pattern with less configuration overhead (`@JobWorker` annotation vs REST client setup), (2) event-sourced persistence aligns with our event-driven architecture, and (3) it represents the current-generation platform. The trade-off: more containers to operate and thinner community documentation. If deployment simplicity or community support were our top priority, Camunda 7 / Operaton would have been the better choice.

**Alternatives considered:**

- **O1: Camunda 7 / Operaton**
  
  - (as laid out above in the table)

## Status

Accepted

## Consequences

**Good:**

- Workers run in their owning domain services, preserving bounded context boundaries (ADR-0102). `ProcessPaymentWorker` stays in payment; `ValidateSignupWorker` and `ActivateAccountWorker` stay in signup.
- Zeebe's event-sourced persistence provides a full audit trail of each process instance, including payment retry attempts and timeout events.

**Bad:**

- Four extra Docker containers (Zeebe, Operate, Tasklist, Elasticsearch) increase memory footprint by a few GB and introduce potential new failure modes.
- Network issues between containers are a new (potential) failure category.
- All three developers are new to Camunda. Community support is thinner than Camunda 7/Operaton, meaning some issues could require more trial-and-error to resolve.
