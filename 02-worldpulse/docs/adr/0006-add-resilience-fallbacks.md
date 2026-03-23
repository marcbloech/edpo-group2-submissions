# ADR 0006: Add Resilience Fallbacks for Orchestration

- Status: Accepted
- Date: 2026-03-17

## Context
For a robust architecture, transient infrastructure failures (especially Zeebe availability) must not silently lose business events.
Also, notification dispatch failures should not break the core signup/payment flow.

## Decision
Implement two fallback mechanisms:

1. Process-start fallback in process service
- Retry process instance start up to 3 attempts.
- If all attempts fail, publish a dead-letter event to Kafka topic worldpulse-dead-letter.

2. Notification fallback in notification worker
- Wrap notification dispatch in try/catch.
- Return non-blocking process variables (notificationSent=false, notificationFallbackUsed=true, notificationError=...) instead of failing the process.

## Project Implementation
- Process fallback classes:
  - process/messages/MessageListener.java
  - process/messages/ProcessDeadLetterPublisher.java
- Notification fallback class:
  - notification/workers/SendNotificationWorker.java

## Consequences
- Reduced risk of event loss during orchestration startup issues.
- Better observability of degraded mode via explicit fallback variables and logs.
- Slightly more implementation complexity and an additional operational topic (worldpulse-dead-letter).