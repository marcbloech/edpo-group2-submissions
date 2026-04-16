# Architecture Decision Records

This folder contains concise ADRs for key project decisions.

## ADR List
- 0101: Orchestration vs choreography in WorldPulse
- 0102: Service boundaries and worker distribution
- 0103: Event/command semantics on Kafka + Zeebe
- 0104: Camunda 8 over Camunda 7
- 0105: Orchestration-based Saga compensation for activation failures

## Superseded ADRs
- 0001-0006 are kept for history and superseded by the 010x ADR set.

## ADR List (01xx series — consolidated overhaul)

These four ADRs consolidate and supersede the original 00xx series. They address TA feedback (specificity to WorldPulse), Zimmermann's guest lecture criteria (ASR test, decision significance, option IDs, diverse stakeholder consequences), and YAML review findings.

- **[0101](0101-hybrid-orchestration-choreography.md)**: Hybrid Orchestration (Zeebe) and Choreography (Kafka) for the Signup/Payment Domain
- **[0102](0102-service-boundaries-worker-distribution.md)**: Distribute Zeebe Workers to Domain Services and Split BPMN by Bounded Context
- **[0103](0103-event-command-semantics.md)**: Separate Events (Kafka) from Commands (Zeebe) with CloudEvents on a Shared Topic
- **[0104](0104-camunda-8-over-camunda-7.md)**: Use Camunda 8 (Zeebe) over Camunda 7 for Process Orchestration

## Superseded ADRs (00xx series — original E2-E4 submissions)

The following ADRs were written incrementally during E2-E4. They are retained for history but superseded by the 01xx series above.

- **0001** → superseded by **0104** (Camunda 8 vs 7)
- **0002** → superseded by **0102** (service boundaries)
- **0003** → superseded by **0101** + **0102** (orchestration/choreography + process decomposition)
- **0004** → superseded by **0101** + **0103** (Kafka backbone + event/command semantics)
- **0005** → absorbed into **0104** as a consequence (Java 21 for SDK compatibility)
- **0006** → absorbed into **0101** (retry+DLQ at Kafka-to-Zeebe bridge) and **0103** (best-effort publishing)
