# WorldPulse: Exercise 2 Walkthrough
This document briefly outlines the solution for Exercise 2, which will lay the groundwork for our WorldPulse application: 
An intelligence-as-a-service platform for real-time global news and insights. 
The focus of Exercise 2 is to implement a simple signup → payment → notification flow using Kafka events, 
demonstrating the Event Notification pattern in an event-driven architecture.

(There is NO actual event analysis or other "intelligence" in this exercise; the focus was purely on the event flow and architecture.)

This is heavily inspired by and adapted from Lab 4's flowing-retail exmple.

## Architecture
```
  curl POST /api/signup
        │
        ▼
  ┌─────────┐  SignupRequestedEvent   ┌──────────┐  PaymentReceived/FailedEvent
  │  Signup │ ─────────────────────▶  │ Payment  │ ──────────────────────────▶
  │  :8091  │                         │  :8093   │
  └─────────┘                         └──────────┘
                  ┌───────────────┐
                  │ Notification  │  (listens to ALL events on "worldpulse" topic)
                  │    :8094      │
                  └───────────────┘
```

**Single Kafka topic:** `worldpulse`

| Event                  | Published by | Consumed by          |
|------------------------|-------------|----------------------|
| `SignupRequestedEvent` | Signup      | Payment, Notification |
| `PaymentReceivedEvent` | Payment     | Notification          |
| `PaymentFailedEvent`   | Payment     | Notification          |

## EDA Pattern: Event Notification

The Notification Service implements the Event Notification pattern from lecture 2. It...
- subscribes to all events on the shared worldpulse topic
- reacts to each event type by "sending" a notification (simulated via console log for now)
- does not need to query other services for data; everything needed is in the event




## How to Run

### Build & Start

```bash
cd edpo-group2/02-worldpulse

# Build all modules
mvn clean install

# Start Kafka + all 3 services
docker compose up --build
```

### Test

Send a signup request (PRO tier):

```bash
curl -X POST http://localhost:8091/api/signup \
  -H "Content-Type: application/json" \
  -d '{"name":"Alice","email":"alice@example.com","tier":"PRO"}'
```

Or try with different tiers:

```bash
# FREE tier (no payment charge)
curl -X POST http://localhost:8091/api/signup \
  -H "Content-Type: application/json" \
  -d '{"name":"Bob","email":"bob@test.com","tier":"FREE"}'

# ENTERPRISE tier
curl -X POST http://localhost:8091/api/signup \
  -H "Content-Type: application/json" \
  -d '{"name":"Carol","email":"carol@corp.com","tier":"ENTERPRISE"}'
```

### Here is what you should see in the logs:

Signup service logs the incoming request:
```
Signup received for: SignupRequest [name=Alice, email=alice@example.com, tier=PRO]
```

Payment service logs the charge attempt:
```
Payment: Charged 1900 cents for PRO tier (user: <uuid>)
```
(or `Payment: FAILED` ~20% of the time for paid tiers)

Notification service logs formatted "emails" for every event:
```
========================================
[EMAIL] Welcome to WorldPulse!
  To: XYZ@example.com
  Dear XYZ,
  Your PRO signup is being processed.
  Trace: <uuid>
========================================
========================================
[EMAIL] Payment Confirmed!
  To: XYZ@example.com
  Dear XYZ,
  Your PRO subscription is now active.
  Trace: <uuid>
========================================
```

### Verify Kafka Events

```bash
docker compose exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic worldpulse \
  --from-beginning
```

### Stop

```bash
docker compose down
```