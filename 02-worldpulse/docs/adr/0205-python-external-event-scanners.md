# [ADR-0205] Python Scanners for External Events (market-scanner, bluesky-scanner)

## Status

Accepted (May 2026)

## Context

The stream-processing exercises (E6, E7) need a continuous stream of realistic external events arriving on `worldpulse` alongside the internal signup/payment events. Two scanner services produce that stream:

- **`market-scanner`** publishes `MarketAlertEvent` (price drops, spikes, volatility). Source: Finnhub WebSocket (real-time trades).
- **`bluesky-scanner`** publishes `SocialTrendEvent` (keyword surges). Source: Bluesky Jetstream firehose, with the Bluesky Search API as backfill.

The rest of the system is Java 21 / Spring Boot (ADR-0005, ADR-0104), so picking Python for these two services is a real choice and worth recording. There are also two related decisions never written down: why these two sources, and why `market-scanner` defaults to mocked.

## Decision

**Python (not Java, not Node) for both scanners.** Three reasons:

1. **Async WebSocket + HTTP libraries fit the job.** Finnhub is a WebSocket, Bluesky Jetstream is a WebSocket, Bluesky Search is HTTPS. `aiohttp` + `asyncio` handle all three in the same event loop with minimal ceremony. The Java equivalent (WebFlux or hand-wired Netty) is heavyweight for a process whose only job is "read socket, publish to Kafka."
2. **Producer-only — no streams logic.** The scanners use the plain `confluent-kafka` Python client. They never run a Kafka Streams topology, so Python costs us nothing in stream-processing capability — all of that is in the Java `stream-processing` module (ADR-0203, ADR-0204).
3. **Polyglot is the point.** The exercise invites a polyglot system. Python-produced CloudEvents consumed by Java Kafka Streams demonstrates that the CloudEvents-on-Kafka contract (ADR-0103) is really language-neutral, not just an aspiration.

**Finnhub + Bluesky as the two sources.** One source per "axis" of the dashboard:

| Source     | Axis      | Why                                                                                                                |
| ---------- | --------- | ------------------------------------------------------------------------------------------------------------------ |
| Finnhub    | financial | Free tier exposes a real-time WebSocket trade stream. Symbols are short and joinable against reference data (the `symbol-metadata` GlobalKTable in ADR-0201). |
| Bluesky    | social    | Public Jetstream firehose, no auth required for read, no paywall. Twitter/X requires paid streaming; Reddit's public streams are throttled. |

Two heterogeneous axes are what makes the C6 stream-stream join non-trivial: "did a market event and a social event happen in the same sector within 5 minutes?" Picking two financial feeds (or two social feeds) would not have produced that join.

**Mocked by default for `market-scanner`** (`MARKET_MODE=${MARKET_MODE:-MOCKED}` in `docker-compose.yml:273`). `bluesky-scanner` defaults to `REAL` because Bluesky needs no API key; mocked mode is kept as a fallback. Reasons:

- A reviewer running `docker compose up` gets a live end-to-end demo with no third-party signup. Requiring a Finnhub API key would gate the demo behind an out-of-band step.
- The mocked sequence is deterministic enough to predict on stage. A real market on a quiet day might emit no alerts during the demo window.
- Mocked and real paths share `EventPublisher` and emit identical CloudEvents — downstream consumers cannot tell the difference, which is what makes the toggle safe.
- Real mode is one env var away: `MARKET_MODE=REAL` + `FINNHUB_API_KEY=...`.

## Consequences

- `docker compose up` produces real CloudEvents on `worldpulse` within seconds with no third-party dependency. Important for the demo to be reproducible.
- The polyglot CloudEvents contract from ADR-0103 is actually exercised — Python-produced events flow through Java Kafka Streams without a translation layer.
- Two extra base images, two extra `requirements.txt` files, and no shared CloudEvents library between Java and Python. The schema is small and stable so the duplication is bounded, but it is duplication.
- The `MARKET_MODE=MOCKED` default is tuned for a live presentation, not a real deployment — same demo-vs-production gap as the window sizes in ADR-0204. The env block in `docker-compose.yml` surfaces it as an obvious knob; a reviewer who doesn't read the README could miss it.
