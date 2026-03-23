# ADR 0002: Keep Job Workers in Domain Services

- Status: Accepted
- Date: 2026-03-17

## Context
A centralized worker module would reduce service autonomy and move domain behavior away from owning services.
It would also make the process service a bottleneck for business changes.

## Decision Drivers
- Preserve microservice ownership
- Keep domain logic close to domain models
- Reduce coupling between orchestration and business implementation

## Decision
Keep Zeebe job workers in their owning microservices:
- signup: validate-signup, activate-account
- payment: process-payment
- notification: send-notification

The process service only deploys BPMN resources and starts instances from incoming events.

## Project Implementation
- signup workers are implemented in:
	- signup/workers/ValidateSignupWorker.java
	- signup/workers/ActivateAccountWorker.java
- payment worker is implemented in:
	- payment/workers/ProcessPaymentWorker.java
- notification worker is implemented in:
	- notification/workers/SendNotificationWorker.java
- The process service starts instances from Kafka events via process/messages/MessageListener.java and does not execute domain jobs.
- Legacy choreography listeners were kept under a profile gate to avoid double processing in default orchestration mode.

## Alternatives Considered
- Put all workers in the process service

This was rejected because it breaks service boundaries and weakens team/service autonomy.

## Consequences
- Better alignment with microservice boundaries.
- Domain behavior stays close to service code.
- Process service stays focused on deployment and process start, not business execution.
- Each domain service must include Zeebe client configuration and worker lifecycle management.
- Service logs directly show job execution in the owning bounded context, improving operational clarity.
