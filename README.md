# WorldPulse — Event-driven Architectures (EDPO, FS26, Group 2)

**WorldPulse** is an event-driven platform that ingests real-world signals (financial
markets, social trends, user activity), orchestrates business processes around them, and turns the resulting event stream into live analytics. It is built incrementally across
the eight course exercises (E1–E8), from raw Kafka experiments to a full Kafka Streams
analytics pipeline.

The whole system is **one Kafka event log** (`worldpulse` topic and friends) that every
component produces to or consumes from.

## Repository structure

| Folder                          | What it is                                                                                                                                            |
| ------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- |
| **`01-experiments/`**           | Standalone Kafka experiments (E1): producer/consumer behaviour, acknowledgements, consumer lag, broker-failure and durability tests.                  |
| **`02-worldpulse/`**            | **The core application** (E2–E8) — all runnable services, scanners, orchestration, and the stream-processing pipeline. Also includes ADRs under docs. |
| **`03-process-orchestration/`** | BPMN models archived for the E3 submission. The *runnable* Camunda 8 implementation lives in `02-worldpulse/process`.                                 |

## Main content

The central repo part is **`02-worldpulse`**. Inside it,
**`02-worldpulse/stream-processing`** holds the Kafka Streams pipeline. See [`02-worldpulse/stream-processing/README.md`](02-worldpulse/stream-processing/README.md).

## Exercise → folder map

| Exercise | Topic                          | Lives in                                                                   |
| -------- | ------------------------------ | -------------------------------------------------------------------------- |
| E1       | Kafka experiments              | `01-experiments/`                                                          |
| E2       | Choreography (Kafka + Spring)  | `02-worldpulse/{signup,payment,notification}`                              |
| E3       | BPMN orchestration (Camunda 8) | `02-worldpulse/process` (BPMN also archived in `03-process-orchestration`) |
| E4       | Orchestration vs. choreography | `02-worldpulse/` (both styles, side by side)                               |
| E5       | Sagas & compensation           | `02-worldpulse/process` (signup ↔ payment with compensation)               |
| E6       | Stream processing — stateless  | `02-worldpulse/stream-processing` (App A)                                  |
| E7       | Stream processing — stateful   | `02-worldpulse/stream-processing` (App B)                                  |
| E8       | Windowing, suppress, joins     | `02-worldpulse/stream-processing` (App B)                                  |

## Quick start

```bash
cd 02-worldpulse
cp .env.example .env          # add your own keys only if you need cloud/live data
docker compose up --build     # Kafka (3 brokers) + Zeebe + all services + streams
```

> **Secrets:** no real credentials are committed. Copy `.env.example` to `.env` and fill in Camunda Cloud / Finnhub / Slack values if so desired.

# 
