# ADR 0003: Split BPMN into signup and payment processes via call activity

## Status

SUPERSEDED (31.03.2026) by 01XX ADRs

## Context

The signup flow has two parts that don't belong together: intake (validation, tier branching, account activation) and payment (charge, retry, human decision, 5-minute timeout). Payment is significantly more complex and changes (potentially) more often.

We needed to decide how to connect them now. The options were a Zeebe call activity (orchestration) or Kafka events (choreography).

Using Lecture 4's heuistic of "Is it OK if the message is ignored?" , we must admit that for signup-to-payment, the answer is "no". A PRO user can't be activated without a successful payment; the signup process has to wait for the result. Thus, that is a command, not merely an event.

## Decision

Two separate BPMN processes, linked by a call activity:

- `signup-process`: validation, tier branching, account activation
- `payment-process`: payment handling, retry, timeout, error paths

For PRO/ENTERPRISE tiers, `signup-process` calls `payment-process` and blocks until it completes.

We didn't use choreography via Kafka here because of the coupling: signup depends on the payment outcome. Publishing a Kafka command and waiting for a response would create the a dependency, but more hidden. The call activity makes it explicit, visible in Operate, and manageable with BPMN timeout and error boundaries.

But kafka choreography is used at the system outer boundary instead (ADR 0004), where events can be ignored. `SignupRequestedEvent` triggers orchestration, and workers broadcast business events for optional downstream consumers (which will be later implemented).

## Consequences

Payment logic can be adapted on its own. Adding (e.g.) a fraud-check step means changing only the `payment-process`. Plus, the diagrams are smaller and more readable. Thus, our architecture uses both orchestration and choreography (hybrid approach). The cost of this is I/O variable mapping between parent and child process in BPMN, and two process definitions to deploy instead of one.
