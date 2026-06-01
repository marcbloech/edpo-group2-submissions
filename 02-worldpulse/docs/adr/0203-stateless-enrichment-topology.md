# [ADR-0203] Stateless Enrichment Topology for App A

## Status

Accepted (May 2026)

## Context

App A is the E6 "stateless stream processing" side of the system. It sits between the raw `worldpulse` CloudEvents topic and the downstream stateful App B. Its only job is to clean, classify, and translate events — no aggregation, no joins, no time windows, no state stores.

`worldpulse` carries five very different event shapes from four producers (market-scanner, bluesky-scanner, signup service), all wrapped in a CloudEvents envelope (`id`, `source`, `type`, `time`, `data`, …) with `key = null`. Downstream consumers — App B's windowed aggregations, the GlobalKTable join, the stream-stream join — need typed Avro records with the right key, not raw JSON envelopes.

Three topology shapes were considered:

1. **One Streams app per event type.** Reuses nothing, duplicates envelope-stripping 5×, multiplies operational surface.

2. **No App A — let App B do everything.** Couples the stateful side to the CloudEvents envelope and blurs the E6/E7 boundary. App B would do both stateless prep and stateful aggregation.

3. **Single stateless topology: content-filter → event-filter → router → translator → merge.** One Streams app, one pass, branches per type, distinct output topics per shape.

## Decision

Option 3. `EventEnrichmentTopology.build()` implements:

```
worldpulse (JSON CloudEvents)
  → mapValues       strip envelope, keep type/data/time
  → filter          drop null/malformed
  → split/branch    market | social | signup | other
  → mapValues       per-branch JSON → Avro translator
  → selectKey       by symbol | topic | userId
  → merge           market + social into one alert stream
  → to              alerts-enriched, signups-normalized
```

A few choices worth recording:

- **`mapValues` for the content filter, not `map`.** Only the value changes; the key is still `null` at this stage. `mapValues` tells Kafka Streams no repartition is needed.
- **`split().branch()` with `Named.as("type-")`**, not the deprecated `branch(Predicate...)`. Branch names (`type-market`, `type-social`, `type-signup`, `type-other`) are stable identifiers instead of array indexes.
- **`selectKey` after translation, not before.** The keying field lives inside the typed Avro record (`alert.getSymbol()`, `alert.getTopic()`, `signup.getUserId()`). Rekeying before translation would mean parsing the JSON twice.
- **Translation failures return `null`**, followed by `filter(value != null)`. A trailing filter drops them at WARN, so a bad record cannot poison the Avro sink.
- **`merge(market, social)` into one `alerts-enriched` topic.** Both produce `EnrichedAlert` with the same schema; downstream consumers want a single alert stream, not two. Signups stay on `signups-normalized` because the schema and downstream consumer are different.
- **`defaultBranch("other")`** catches unknown types and drops them. Silent drop is intentional — App A's contract is "alerts + signups."

Output topic names use `-enriched` / `-normalized` to describe what App A did to the data, matching the verb-based convention used by App B's outputs (`signup-counts-final`, `correlated-alerts-summary-final`).

## Consequences

- App A and App B are in the same Maven module but answer to different exercises and can be scaled independently — App A is embarrassingly parallel, App B is not.
- Consumers of `alerts-enriched` get typed Avro keyed by the right field, so App B's `C4a` does `groupByKey` directly with no extra `selectKey` and no internal repartition topic.
- The `EnrichedAlert` schema has nullable market-only fields (`changePercent`) and social-only fields (`postCount`). A consumer that forgets to null-check the wrong shape will NPE — we always route by `source` ("WorldPulse-MarketScanner" vs "WorldPulse-BlueSkyScanner") when the branch matters again (C6).
- Unknown event types disappear into `type-other` without a warning. Fine while the contract is "alerts + signups"; a producer adding a new event type without coordinating us would see it silently dropped here.
