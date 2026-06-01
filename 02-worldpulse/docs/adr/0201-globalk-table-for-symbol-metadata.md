# [ADR-0201] GlobalKTable for Symbol Metadata Instead of External DB Lookup

## Status

Accepted (May 2026)

## Context

Alerts arrive from App A keyed by symbol (e.g. `CL=F`) or topic (e.g. "Iran oil"). The downstream correlation join groups by *sector* (Energy, Tech, etc.), and the dashboard needs human-readable names ("Crude Oil WTI" instead of `CL=F`). Neither field exists in the source events.

We need to look up display name, sector, and asset category for each alert. The reference data is small (14 ticker symbols for our MVP), rarely changes, and is fully known at startup.

Three options:

1. **External database query per event.** Query a PostgreSQL or Redis instance for each alert. Simple, but adds network latency per lookup, a runtime dependency, and coupling between the stream processor and an external system. 

2. **Partitioned KTable backed by a Kafka topic.** Co-partition the metadata topic with the alerts topic so a regular KTable join works. This requires the metadata topic to have the same number of partitions as alerts, the same partitioning strategy, and the same key space.

3. **GlobalKTable backed by a compacted topic.** Replicate the full metadata set to every stream thread. Lookups are local, the data is always available, and there is no co-partitioning requirement.

## Decision

Option 3: GlobalKTable backed by a compacted `symbol-metadata` topic, joined via `leftJoin`.

We use `leftJoin` (not inner join) so alerts for unknown symbols pass through un-enriched rather than being silently dropped. 

The topic is seeded at startup by `WorldPulseStreamApp.main()` before the KafkaStreams instances start.

## Consequences

- Lookups are local, so no added latency or external dependency.
- Every instance has the full dataset, so co-partitioning is not a concern.
- The GlobalKTable must fit in memory on every instance. At 14 entries this is trivial; at 100k+ entries we should consider a partitioned KTable instead.
- 
- The `leftJoin` means un-enriched alerts can reach the query API. We consider this better than silently dropping them.
