# [ADR-0204] Tumbling Windows and Window Sizes for App B

## Status

Accepted (May 2026)

## Context

App B has four windowed aggregations plus one windowed join:

| Operation                        | Question answered                                                |
| -------------------------------- | ---------------------------------------------------------------- |
| `alert-stats`                    | "How are alerts trending per symbol/topic right now?"            |
| `signup-counts`                  | "How many signups per tier in the last minute?"                  |
| `login-stats`                    | "Where are users logging in right now?"                          |
| `correlated-alerts-summary` (C7) | "What is the rolled-up market + social picture per sector?"     |
| stream-stream join (C6)          | "Did a market alert and a social alert co-occur in this sector?" |

ADR-0202 already explains *when to suppress*. This ADR covers two earlier decisions that 0202 left implicit: **which window type** and **which window sizes**.

Kafka Streams offers three window types:

1. **Tumbling** — fixed-size, non-overlapping. Each record belongs to exactly one window.
2. **Hopping** — fixed-size, overlapping. Each record belongs to several windows. Multiplies storage and output.
3. **Session** — variable-size, gap-driven. Windows close when activity dies for `inactivity gap`.

## Decision

All windowed aggregations use **tumbling** windows. Sizes:

| Operation                        | Window size | Grace | Suppress?          |
| -------------------------------- | ----------- | ----- | ------------------ |
| `alert-stats`                    | 1 min       | 30s   | No (live)          |
| `signup-counts`                  | 1 min\*     | 30s   | Yes (final-only)   |
| `login-stats`                    | 30s         | 5s    | No (live)          |
| `correlated-alerts-summary` (C7) | 1 min\*     | 30s   | Yes (final-only)   |
| stream-stream join (C6)          | ±5 min      | 30s   | n/a (join)         |

\* 1 min is the demo size so windows visibly turn over during a live presentation. The comments at `AnalyticsDashboardTopology.java:215` and `:347` record the production intent (5–15 min for signups, 5 min for C7).

**Why tumbling, not hopping or session.** The dashboard wants discrete buckets ("PREMIUM signups in the last minute"), not sliding ones. Hopping windows emit overlapping counts that share members — confusing on a panel labeled "per minute" and 4× the storage for no readability gain. Session windows assume a natural activity gap that does not exist in this domain: login events from "zurich" and market alerts for `CL=F` arrive whenever they arrive, so a session window degenerates to either "one window forever" or "one record per window."

**Why these specific sizes.**

- `signup-counts` — slow-moving business KPI. The consumer can tolerate waiting for the window to close, which is what makes 0202's `suppress(untilWindowCloses)` viable here and not elsewhere. 1 min in demo gives 4–5 visible window closes in a 5-minute presentation; 5–15 min in production is the analyst's natural cadence.
- `alert-stats` — 1 min smooths the bursty arrival of market/social alerts without being so wide that a fresh spike is invisible. No suppress, so the dashboard still updates within seconds.
- `login-stats` — 30s, the tightest in the system. A 1-min window would feel laggy on the live map. Short enough to react, long enough that the per-location count is not down to single events.
- `correlated-alerts-summary` (C7) — must be ≤ the C6 join's ±5 min. If wider, a market+social pair that joined would land in a bucket alongside unrelated noise. Production target of 5 min matches the join exactly.
- stream-stream join (C6) — ±5 min encodes the domain rule "a market and a social event are correlated if they happen within 5 minutes." Shorter would miss real correlations where the news cycle takes time to react; longer would falsely correlate unrelated bursts.

**Grace periods.** 30s absorbs realistic clock skew between Python and Java producers feeding `worldpulse` from separate processes. For the 30s `login-stats` window, grace had to drop to 5s — a 30s grace on a 30s window would mean a window stays open for its entire successor.

## Consequences

- One mental model across all aggregations: "this much activity in this fixed-time bucket." Readers don't have to remember per-aggregation window semantics.
- Window sizes are tuning knobs, not topology changes. Switching demo → production is a value change at the build site, not a restructure. The `// 1min for demo; production: 5min` comments mark the intended values.
- The "C7 ≤ C6" constraint is documented here but not enforced in code. A future contributor who widens C7 past 5 minutes without also widening C6 would silently break bucket alignment.
- All window stores use 30-minute retention (well above `windowSize + grace`), so late-arriving events within grace are merged correctly and anything past 30 minutes is dropped — acceptable for a live dashboard.
