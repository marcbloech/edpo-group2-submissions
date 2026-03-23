# Kafka Experiments (EDPO FS2026 - Exercise 1)

## Infrastructure

**Single broker** (`docker-compose-single.yml`) — 1 KRaft broker on `localhost:9092`:
```bash
docker compose -f docker/docker-compose-single.yml up -d
```

**Multi broker** (`docker-compose-multi.yml`) — 1 controller + 3 brokers on `localhost:9092`, `:9094`, `:9096`:
```bash
docker compose -f docker/docker-compose-multi.yml up -d
```

## Shared Utilities

| Class | Purpose |
|---|---|
| `TopicManager` | Create/delete/recreate topics, describe topic metadata via `AdminClient` |
| `MetricsCollector` | Thread-safe latency recording (avg/p50/p95/p99), throughput, CSV output |

## Running Experiments

### Producer Experiments

```bash
mvn clean compile
mvn exec:java -Dexec.mainClass="ch.unisg.kafka.experiments.producer.AcksExperiment"
mvn exec:java -Dexec.mainClass="ch.unisg.kafka.experiments.producer.LoadTestExperiment"
```

| Experiment | Infra | What it tests |
|---|---|---|
| `AcksExperiment` | multi broker | `acks=0/1/all` throughput & latency (500K msgs, rf=3) |
| `LoadTestExperiment` | single broker | 1-8 concurrent producer threads (50K msgs each) |

### Consumer Experiments

Consumer experiments test consumer lag, offset management, and consumer group behavior.

```bash
mvn clean compile
mvn exec:java -Dexec.mainClass="ch.unisg.kafka.experiments.consumer.ConsumerLagExperiment"
mvn exec:java -Dexec.mainClass="ch.unisg.kafka.experiments.consumer.OffsetResetExperiment"
mvn exec:java -Dexec.mainClass="ch.unisg.kafka.experiments.consumer.RebalancingExperiment"
```

| Experiment | Infra | What it tests |
|---|---|---|
| `ConsumerLagExperiment` | single broker | Consumer lag monitoring with different processing delays (0/10/50/100ms), demonstrates lag buildup when processing is slow |
| `OffsetResetExperiment` | single broker | `auto.offset.reset` behavior (earliest vs latest), demonstrates data loss risk with `latest` when consumer starts after messages are produced |
| `RebalancingExperiment` | single broker | Consumer group rebalancing, partition assignment with multiple consumers, multiple consumer groups consuming independently |

**Results:** See `consumer-experiments-results.md` for detailed test results.

**Key Findings:**
- Consumer lag builds up when consumer processes slower than producer
- `auto.offset.reset=earliest` reads from beginning, `latest` only reads new messages (can cause data loss)
- Partitions automatically redistribute when consumers join/leave group
- Each consumer group consumes messages independently

### Fault Tolerance & Reliability Experiments

```bash
mvn exec:java -Dexec.mainClass="ch.unisg.kafka.experiments.faulttolerance.BrokerFailureExperiment"
mvn exec:java -Dexec.mainClass="ch.unisg.kafka.experiments.faulttolerance.DroppedMessagesExperiment"
```

| Experiment | Infra | What it tests |
|---|---|---|
| `BrokerFailureExperiment` | multi broker | Leader election monitoring, failover time measurement, broker failure impact on producer/consumer (rf=3, 3 partitions) |
| `DroppedMessagesExperiment` | multi broker | Message durability under different replication factors (1/2/3) and acks settings (0/1/all), drop rate analysis |

#### Experiment 3 — Broker Failure & Leader Elections

Sends 100K messages (`acks=all`, RF=3, 3 partitions) while concurrently monitoring partition metadata every 100ms. The user manually kills a broker during the run, and the experiment detects leader elections & ISR changes in real time.

**How to run:**
1. Start `BrokerFailureExperiment`
2. While running, manually kill a broker:
   ```bash
   docker stop docker-kafka1-1   # or kafka2-1, kafka3-1
   ```
3. Observe leader election and failover behavior in the console
4. Optionally restart the broker:
   ```bash
   docker start docker-kafka1-1
   ```

**Outputs:** `broker-failure-results.txt` — latency percentiles (p50/p95/p99), throughput over time, detected leader elections with old/new leader IDs, ISR shrink/expand events.

#### Experiment 4 — Message Durability & Drop Rate

Two-phase experiment that tests all combinations of replication factor (1/2/3) and acknowledgment settings (`acks=0/1/all`).

| Phase | Configs | Messages | Fault | Purpose |
|---|---|---|---|---|
| **A — Baseline** | 8 (full RF x acks matrix) | 5,000 each | None | Prove 0% loss under healthy cluster |
| **B — Broker Failure** | 4 key configs | 20,000 each | `docker-kafka1-1` killed before sending | Measure actual drop rate under failure |

Phase B kills `docker-kafka1-1` before each test and sends only to the alive brokers (`localhost:9094,localhost:9096`). After each test, the broker is restarted and the cluster is allowed to recover.

**Outputs:** `dropped-messages-results.txt` — per-config sent/received/dropped counts, drop rate percentages, latency stats, and a final comparison table.

## Adding New Experiments

Create classes under `consumer/` or `faulttolerance/` packages. Use `TopicManager` for topic setup and `MetricsCollector` for measurements. Each experiment is a standalone `main()` class run via `exec-maven-plugin`.
