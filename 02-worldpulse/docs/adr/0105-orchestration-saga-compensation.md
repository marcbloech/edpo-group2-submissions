# [ADR-0105] BPMN Compensation Events For Activation Failures (Saga Pattern)

## Context

`signup-process` and `payment-process` are orchestrated with Camunda 8 (Zeebe).  
For paid tiers, payment can succeed but account activation can still fail due to downstream business or technical reasons.

Without compensation, this creates an inconsistent state: user charged but not activated.

BPMN 2.0 defines a first-class compensation mechanism with dedicated boundary events, compensation handler tasks (`isForCompensation="true"`), and compensation throw events. Camunda 8 supports this natively since version 8.5. Using built-in compensation rather than ad-hoc error routing aligns with the Saga pattern taught in Lecture 3 (slide 19) and demonstrated in Lab 04 (order.bpmn) and Lab 09 (trip booking saga).

## Decision

Implement compensation using BPMN's built-in compensation mechanism inside `SubProcess_PaidSignupTransaction`:

1. **Compensation boundary event** attached to `CallActivity_PaymentProcess` — registers that this activity has a compensation handler.
2. **Compensation handler** (`Task_RefundPayment`) marked with `isForCompensation="true"` — linked to the boundary event via a BPMN association (dashed line), not a sequence flow. Calls `refund-payment` Zeebe job type.
3. **Error boundary event** on `Task_ActivateAccountPaid` catches `ACTIVATION_ERROR`.
4. **Compensation intermediate throw event** (`ThrowEvent_CompensatePayment`) with `activityRef="CallActivity_PaymentProcess"` — triggers the refund handler via the engine's compensation mechanism.
5. After compensation completes, `send-notification` informs the user using `AccountActivationFailedEvent`.

Payment worker continues to emit compensation outcome events to Kafka (`PaymentRefundedEvent` / `PaymentRefundFailedEvent`).

## Status

Accepted (updated from ad-hoc error-routing to proper BPMN compensation)

## Consequences

**Good:**

- Uses BPMN's standard compensation mechanism — the engine tracks which activities completed and invokes the correct handlers.
- Aligns with Saga pattern as taught in the course (Labs 04, 09) and Bernd Ruecker's "Practical Process Automation" (Chapter 7).
- Compensation logic stays in payment bounded context (`refund-payment` worker).
- The process model is self-documenting: compensation associations visually show which activity undoes which.
- End-to-end flow remains visible in Operate and auditable via Kafka events.

**Bad:**

- Adds BPMN complexity (compensation boundary event, association, throw event).
- Refund can fail too; this requires manual follow-up and monitoring.
- Compensation handlers in Camunda 8 have limitations (e.g., child process handlers are not auto-invoked across call activity boundaries — hence the handler is attached to the call activity itself).
