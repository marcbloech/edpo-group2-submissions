# [ADR-0202] Per-Aggregation Suppress Policy for Windowed Operations

## Status

Accepted (May 2026)

## Context

App B has three windowed aggregations. Each answers a different business question, and the right emit behavior depends on what the consumer actually needs:

| Aggregation   | Question                                        | What the consumer needs                                             |
| ------------- | ----------------------------------------------- | ------------------------------------------------------------------- |
| signup-counts | "How many PREMIUM signups in the last minute?"  | One correct final number per window. Intermediate counts are noise. |
| login-stats   | "Where are users logging in right now?"         | Live updates. A 60-second delay defeats the purpose.                |
| alert-stats   | "What is the running average change% for CL=F?" | Real-time visibility. Dashboard users want to see numbers move.     |

Kafka Streams' `suppress(untilWindowCloses)` buffers all intermediate results and emits one final record when the window closes. Without suppress, every new event triggers an updated aggregate downstream.

Two options:

1. **Suppress everywhere.** Consistent behavior, simpler mental model. But login-stats and alert-stats become useless for live dashboards.

2. **Suppress selectively.** Use it where final-count correctness matters (signup-counts), skip it where liveness matters (login-stats, alert-stats).

## Decision

Option 2. Suppress is enabled only on signup-counts. Login-stats and alert-stats emit on every update.

## Consequences

- signup-counts emits one record per window, which is what the "signups per tier per minute" dashboard panel expects.
- login-stats and alert-stats update within seconds during burst tests.
- 
