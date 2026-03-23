# ADR 0003: Split BPMN into Signup and Payment Processes

- Status: Accepted
- Date: 2026-03-17

## Context
The end-to-end flow contains distinct concerns: signup intake and payment lifecycle.
These concerns evolve at different speeds and are usually reviewed by different stakeholders.

## Decision Drivers
- Keep BPMN diagrams understandable
- Isolate payment-specific complexity (retry, error, human decision)
- Avoid one large process that is hard to maintain

## Decision
Use two BPMN processes:
- signup-process for intake, validation, and tier branching
- payment-process for payment handling, retry decision, and payment outcomes

Link them via call activity from signup-process to payment-process when payment is required.

## Project Implementation
- signup-process handles:
	- validate-signup
	- initial notification
	- tier decision (FREE vs PRO/ENTERPRISE)
	- direct account activation for FREE
- For PRO/ENTERPRISE, signup-process invokes payment-process via call activity (called element: payment-process).
- payment-process handles:
	- process-payment service task
	- success/failure gateway
	- retry decision user task (retryPaymentForm)
	- success/failure/error notifications
	- account activation on successful payment path
- Notification type constants are passed via FEEL expressions in BPMN mappings.

## Alternatives Considered
- One single monolithic BPMN for both signup and payment

This was rejected because it increases diagram complexity and makes testing and change management harder.

## Consequences
- Clearer process ownership and easier maintenance.
- Payment logic can evolve without reshaping signup flow.
- Process diagrams are simpler to review and explain.
- Variable mapping between parent and child process must be managed carefully.
- Testing can target FREE and PRO branches independently with trace-level verification in logs.
