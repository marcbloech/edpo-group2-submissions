# WorldPulse Demo Guide

## Architecture Overview

WorldPulse is an event-driven microservices platform built with:

- **Kafka** -- asynchronous event messaging (choreography)
- **Camunda 8 / Zeebe** -- BPMN process orchestration
- **Spring Boot** -- Java microservices
- **Camunda Cloud** -- hosted process engine, Operate UI, Tasklist UI
- **Slack Connector** -- real-time notifications via Camunda's built-in Slack integration

### Services

| Service | Port | Role |
|---------|------|------|
| **Signup** | 8091 | REST API entry point, publishes events to Kafka |
| **Payment** | 8093 | Processes payments, publishes payment events |
| **Notification** | 8094 | Sends email notifications (simulated via logs) |
| **Process** | 8095 | Kafka-to-Zeebe bridge, starts BPMN process instances |
| **Market Scanner** | -- | Python service, publishes market alerts to Kafka |
| **BlueSky Scanner** | -- | Python service, publishes social trend events to Kafka |

### BPMN Processes

| Process | Description |
|---------|-------------|
| `signup-process` | Full signup flow with validation, payment, activation, and compensation |
| `payment-process` | Payment handling with retry logic, user task, and Slack notifications |
| `upgrade-process` | Tier upgrade with payment and tier application |
| `deactivation-process` | Account deactivation with notifications |

---

## Prerequisites

- Docker & Docker Compose
- Java 21 + Maven
- A Camunda Cloud account (camunda.io) with a running cluster

---

## Setup

### 1. Build the Java services

```bash
cd 02-worldpulse
mvn clean package -DskipTests
```

### 2. Configure Camunda Cloud credentials

Copy the cloud environment file:

```bash
cp .env.cloud .env
```

Edit `.env` with your cluster credentials (from camunda.io > Cluster > API > Create Client):

```env
CAMUNDA_CLIENT_MODE=saas
CAMUNDA_CLUSTER_ID=<your-cluster-id>
CAMUNDA_CLUSTER_REGION=<your-region>
CAMUNDA_CLIENT_ID=<your-client-id>
CAMUNDA_CLIENT_SECRET=<your-client-secret>
CAMUNDA_OAUTH_URL=https://login.cloud.camunda.io/oauth/token
CAMUNDA_CLOUD_BASE_URL=zeebe.camunda.io
ZEEBE_GRPC_ADDRESS=https://<your-cluster-id>.<your-region>.zeebe.camunda.io
```

### 3. Start the services

For cloud mode (only Kafka + app services needed):

```bash
docker compose up --build kafka signup payment notification process market-scanner bluesky-scanner
```

For local/self-managed mode (includes Zeebe, Operate, Tasklist):

```bash
cp .env.local .env
docker compose up --build
```

### 4. Verify deployment

Check that BPMN processes were deployed:

```bash
docker compose logs process | grep "Deployed:"
```

You should see:
```
Deployed: <signup-process:N>,<upgrade-process:N>,<deactivation-process:N>,<payment-process:N>
```

---

## Demo Scenarios

All commands below are single-line `curl` commands. Run them from any terminal.

---

### Scenario 1: FREE Tier Signup (Happy Path)

**Path:** Validate -> Notify + Assess Payment (parallel) -> Skip Payment -> Welcome -> Activate Account -> Done

```bash
curl -s -X POST http://localhost:8091/api/signup -H "Content-Type: application/json" -d '{"name":"Alice Johnson","email":"alice@example.com","tier":"FREE"}'
```

**What to observe:**
- Process completes immediately in Operate (Completed instances)
- No payment is processed (FREE tier skips payment)
- Notification service logs a welcome email
- `AccountActivatedEvent` published to Kafka

---

### Scenario 2: PRO Tier Signup (Happy Path -- with Payment + Slack)

**Path:** Validate -> Notify + Assess Payment (parallel) -> Payment Required -> Process Payment -> Payment Success -> Notify -> Slack -> Activate Account -> Done

```bash
curl -s -X POST http://localhost:8091/api/signup -H "Content-Type: application/json" -d '{"name":"Dave Brown","email":"dave@example.com","tier":"PRO"}'
```

**What to observe:**
- Payment subprocess executes within signup-process
- Slack channel receives: "Payment succeeded" with name, email, tier, amount, transaction ID
- Process completes with account activation

---

### Scenario 3: ENTERPRISE Signup with Activation Failure (Saga Compensation)

**Path:** Validate -> Payment Success -> Activate Account FAILS -> Compensate: Refund Payment -> Notify Activation Failed -> End (Failed)

```bash
curl -s -X POST http://localhost:8091/api/signup -H "Content-Type: application/json" -d '{"name":"Eve Davis","email":"eve@example.com","tier":"ENTERPRISE","forceActivationFailure":true}'
```

**What to observe:**
- Payment succeeds first, then activation fails
- **Compensation kicks in**: refund is processed automatically
- Notification sent about activation failure (includes refund details)
- In Operate: you can see the compensation boundary event trigger and the refund task
- This demonstrates the **Saga pattern** -- if a later step fails, earlier steps are compensated

---

### Scenario 4: FREE Tier with Activation Failure (No Refund Needed)

**Path:** Validate -> Skip Payment -> Welcome -> Activate Account FAILS -> Notify Failure (no refund) -> End (Failed)

```bash
curl -s -X POST http://localhost:8091/api/signup -H "Content-Type: application/json" -d '{"name":"Frank Miller","email":"frank@example.com","tier":"FREE","forceActivationFailure":true}'
```

**What to observe:**
- Activation fails but no refund is triggered (FREE tier had no payment)
- Demonstrates that compensation logic is conditional

---

### Scenario 5: PRO Signup with Forced Payment Failure (Human-in-the-Loop)

**Path:** Validate -> Payment FAILS -> User Task: "Decide: Retry Payment?" -> (user declines) -> Notify Payment Failed -> Slack: Payment Failed -> End

```bash
curl -s -X POST http://localhost:8091/api/signup -H "Content-Type: application/json" -d '{"name":"Eve Wilson","email":"eve@example.com","tier":"PRO","forcePaymentFailure":true}'
```

**What to observe:**
1. Payment is forced to fail
2. A **user task** appears in Camunda Cloud **Tasklist**: "Decide: Retry Payment?"
3. Open Tasklist, claim the task, set `retryPayment` to `false`, and complete it
4. Slack channel receives: "Payment failed (signup cancelled)" with details
5. This demonstrates the **Human-in-the-Loop** pattern -- a human decides whether to retry

**To test the retry path instead:** complete the user task with `retryPayment` set to `true`. The payment will re-execute (and fail again since the flag persists). After 3 retries, the process auto-cancels.

---

### Scenario 6: Payment Processing Error (Boundary Error Event + Slack)

**Path:** Validate -> Process Payment THROWS ERROR -> Boundary Error Event -> Send Error Notification -> Slack: Payment Processing Error -> Technical Error End

```bash
curl -s -X POST http://localhost:8091/api/signup -H "Content-Type: application/json" -d '{"name":"Zara Tech","email":"zara@example.com","tier":"PRO","forcePaymentError":true}'
```

**What to observe:**
- The payment worker throws a BPMN error (not a business failure -- a processing error)
- The **boundary error event** on the payment task catches it
- Error notification is sent via email
- Slack channel receives: "Payment process error" with details
- Process ends at the **Technical Error** end event
- This demonstrates **BPMN error boundary events** for unexpected failures

---

### Scenario 7: Validation Failure -- Invalid Email

**Path:** Validate FAILS -> Notify Validation Failed -> End (Failed)

```bash
curl -s -X POST http://localhost:8091/api/signup -H "Content-Type: application/json" -d '{"name":"Bob Smith","email":"invalid-email","tier":"PRO"}'
```

**What to observe:**
- Process catches the validation error via a boundary error event
- Notification sent about validation failure
- Process ends immediately -- no payment or activation attempted

---

### Scenario 8: Validation Failure -- Unsupported Tier

**Path:** Validate FAILS -> Notify Validation Failed -> End (Failed)

```bash
curl -s -X POST http://localhost:8091/api/signup -H "Content-Type: application/json" -d '{"name":"Carol White","email":"carol@test.com","tier":"GOLD"}'
```

**What to observe:**
- `GOLD` is not a valid tier (only FREE, PRO, ENTERPRISE)
- ValidateSignupWorker throws a BPMN error
- Same error handling path as Scenario 5

---

### Scenario 9: Validation Failure -- Missing Name

**Path:** Validate FAILS -> Notify Validation Failed -> End (Failed)

```bash
curl -s -X POST http://localhost:8091/api/signup -H "Content-Type: application/json" -d '{"name":"","email":"test@example.com","tier":"FREE"}'
```

---

### Scenario 10: Tier Upgrade (Happy Path)

**Path:** Notify Upgrade Requested -> Process Payment -> Apply Tier Upgrade -> Notify Upgrade Completed -> Done

```bash
curl -s -X POST http://localhost:8091/api/upgrade -H "Content-Type: application/json" -d '{"name":"Grace Lee","email":"grace@example.com","currentTier":"FREE","targetTier":"PRO"}'
```

**What to observe:**
- Upgrade payment is processed
- Tier is upgraded from FREE to PRO
- Notifications sent at each step

---

### Scenario 11: Account Deactivation

**Path:** Notify Deactivation Requested -> Deactivate Account -> Notify Deactivated -> Done

```bash
curl -s -X POST http://localhost:8091/api/deactivate -H "Content-Type: application/json" -d '{"name":"Henry Clark","email":"henry@example.com","tier":"PRO","reason":"No longer needed"}'
```

**What to observe:**
- Account is deactivated with a reason logged
- Notifications sent for deactivation request and completion

---

## Where to Monitor

### Camunda Cloud (cloud mode)

- **Operate** -- View deployed processes, running/completed instances, incidents
  - Go to camunda.io > Your Cluster > Operate
  - Filter by process (signup-process, payment-process, etc.)
  - Toggle "Completed" filter to see finished instances

- **Tasklist** -- Claim and complete user tasks (e.g., retry payment decision)
  - Go to camunda.io > Your Cluster > Tasklist

### Local mode

- **Operate**: http://localhost:8085 (login: `demo` / `demo`)
- **Tasklist**: http://localhost:8086 (login: `demo` / `demo`)

### Slack

Payment process outcomes are posted to the configured Slack channel:
- `Payment succeeded` -- with trace ID, email, name, tier, amount, transaction ID
- `Payment failed (signup cancelled)` -- when retry is declined
- `Payment process error` -- on technical errors

### Service Logs

```bash
# All services
docker compose logs -f

# Specific service
docker compose logs -f process
docker compose logs -f signup
docker compose logs -f payment
docker compose logs -f notification
```

### Kafka Events

To watch events on the `worldpulse` topic:

```bash
docker compose exec kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic worldpulse --from-beginning
```

---

## Event Flow Summary

### Signup (Paid Tier -- Happy Path)

```
[User] POST /api/signup
   |
   v
[Signup Service] --> Kafka: SignupRequestedEvent
   |
   v
[Process Service] <-- Kafka: receives event
   |
   v
[Zeebe/Camunda Cloud] starts signup-process
   |
   +--> [Signup Worker] validate-signup
   +--> [Notification Worker] send-notification (parallel)
   +--> [Signup Worker] assess-payment-eligibility (parallel)
   |
   v  (paymentRequired = true)
[Zeebe] starts payment-process (subprocess)
   |
   +--> [Payment Worker] process-payment --> Kafka: PaymentReceivedEvent
   +--> [Notification Worker] send-notification (success)
   +--> [Slack Connector] posts to Slack channel
   |
   v
[Signup Worker] activate-account --> Kafka: AccountActivatedEvent
   |
   v
[Process Complete]
```

### Signup (Paid Tier -- Compensation Path)

```
[Zeebe] payment-process succeeds
   |
   v
[Signup Worker] activate-account --> FAILS (forceActivationFailure=true)
   |
   v  (compensation boundary event)
[Payment Worker] refund-payment --> Kafka: PaymentRefundedEvent
   |
   v
[Notification Worker] notify activation failed (with refund details)
   |
   v
[Process Failed -- Compensated]
```

---

## Patterns Demonstrated

| Pattern | Where |
|---------|-------|
| **Event-Driven Architecture** | All services communicate via Kafka events |
| **Event Notification** | Notification service listens to all events on `worldpulse` topic |
| **Process Orchestration** | Camunda/Zeebe coordinates the signup, payment, upgrade, and deactivation flows |
| **Saga Pattern (Compensation)** | Paid signup: if activation fails after payment, a refund is automatically triggered |
| **Choreography + Orchestration** | Kafka for loose coupling between services; Zeebe for coordinating within a process |
| **Human-in-the-Loop** | Payment retry decision is a user task in Tasklist |
| **Connector Integration** | Slack notifications sent via Camunda's built-in Slack connector |
| **Error Handling** | Validation errors, payment errors, timeouts -- all handled via BPMN boundary events |
| **Parallel Execution** | Signup validates while simultaneously sending initial notification and assessing payment |
