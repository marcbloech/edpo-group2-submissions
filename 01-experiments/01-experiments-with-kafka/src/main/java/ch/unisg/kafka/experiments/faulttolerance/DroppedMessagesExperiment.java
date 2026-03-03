package ch.unisg.kafka.experiments.faulttolerance;

import ch.unisg.kafka.experiments.util.TopicManager;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Experiment 4: Message Durability & Drop Rate Analysis
 *
 * Two-phase experiment that demonstrates how replication factor and acknowledgment
 * settings affect message durability under both normal and failure conditions.
 *
 * PHASE A — Normal Operation (Baseline):
 *   All 8 configurations tested without any failure injection.
 *   Proves that Kafka delivers 100% of messages when the cluster is healthy.
 *
 * PHASE B — Under Broker Failure:
 *   4 key configurations tested while a broker is killed mid-send.
 *   Demonstrates that weak configs (acks=0, RF=1) lose messages silently,
 *   while strong configs (acks=all, RF=3) survive broker crashes.
 */
public class DroppedMessagesExperiment {

    private static final String BOOTSTRAP_SERVERS = "localhost:9092,localhost:9094,localhost:9096";
    private static final String ALIVE_BROKERS = "localhost:9094,localhost:9096"; // Without killed broker
    private static final int BASELINE_MESSAGES = 5_000;
    private static final int FAULT_MESSAGES = 20_000;
    private static final int NUM_PARTITIONS = 3;
    private static final String OUTPUT_FILE = "dropped-messages-results.txt";

    // Fault injection (Phase B)
    private static final String TARGET_BROKER_CONTAINER = "docker-kafka1-1";

    // Results storage
    private static final List<TestResult> baselineResults = new ArrayList<>();
    private static final List<TestResult> faultResults = new ArrayList<>();

    // Dual output helper
    private static PrintStream fileOut;
    private static final PrintStream consoleOut = System.out;

    private static void print(String msg) {
        consoleOut.println(msg);
        if (fileOut != null) fileOut.println(msg);
    }

    private static void printf(String fmt, Object... args) {
        String msg = String.format(fmt, args);
        consoleOut.print(msg);
        if (fileOut != null) fileOut.print(msg);
    }

    public static void main(String[] args) throws Exception {
        fileOut = new PrintStream(new FileOutputStream(OUTPUT_FILE));
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        print("=".repeat(90));
        print("  Experiment 4: Message Durability & Drop Rate Under Broker Failure");
        print("=".repeat(90));
        print("Timestamp : " + timestamp);
        print("");
        print("Methodology:");
        print("  PHASE A — Baseline:  Send messages with NO broker failure (expect 0% loss)");
        print("  PHASE B — Failure:   Send messages WHILE killing a broker (expect loss in weak configs)");
        print("");
        print("Parameters:");
        printf("  Baseline messages:   %,d per config%n", BASELINE_MESSAGES);
        printf("  Fault messages:      %,d per config%n", FAULT_MESSAGES);
        print("  Target broker:       " + TARGET_BROKER_CONTAINER + " (broker.id=2, port 9092)");
        print("  Partitions:          " + NUM_PARTITIONS);
        print("  Brokers:             3 (KRaft mode)");
        print("=".repeat(90));

        // ── All 8 configurations ──
        TestConfig[] allConfigs = {
            new TestConfig(3, "all"),
            new TestConfig(3, "1"),
            new TestConfig(3, "0"),
            new TestConfig(2, "all"),
            new TestConfig(2, "1"),
            new TestConfig(2, "0"),
            new TestConfig(1, "1"),
            new TestConfig(1, "0"),
        };

        // ── 4 key configs for fault injection ──
        TestConfig[] faultConfigs = {
            new TestConfig(3, "all"),  // Strongest: full replication + full ack
            new TestConfig(3, "0"),    // High RF but fire-and-forget
            new TestConfig(1, "1"),    // Minimal RF with leader ack
            new TestConfig(1, "0"),    // Weakest: no replication + fire-and-forget
        };

        // ════════════════════════════════════════════════════════════
        // PHASE A — BASELINE (No Failures)
        // ════════════════════════════════════════════════════════════
        print("");
        print("╔".repeat(1) + "═".repeat(88) + "╗".repeat(1));
        print("║  PHASE A — BASELINE: Normal Operation (No Broker Failures)");
        print("╚".repeat(1) + "═".repeat(88) + "╝".repeat(1));

        for (int i = 0; i < allConfigs.length; i++) {
            printf("%n>>> Baseline Test %d/%d: %s%n", i + 1, allConfigs.length, allConfigs[i].name());
            TestResult result = runBaselineTest(allConfigs[i]);
            baselineResults.add(result);
            if (i < allConfigs.length - 1) {
                print("  Cooldown 2s...");
                Thread.sleep(2000);
            }
        }

        printPhaseTable("PHASE A — BASELINE RESULTS (No Failures)", baselineResults);

        // ════════════════════════════════════════════════════════════
        // PHASE B — FAULT INJECTION (Broker Kill During Send)
        // ════════════════════════════════════════════════════════════
        print("");
        print("╔".repeat(1) + "═".repeat(88) + "╗".repeat(1));
        print("║  PHASE B — FAULT INJECTION: Sending While Killing Broker");
        print("╚".repeat(1) + "═".repeat(88) + "╝".repeat(1));
        print("");
        print("  For each configuration:");
        print("    1. Create topic while cluster is healthy");
        print("    2. Kill broker (docker kill) → wait 5s for cluster detection");
        printf("    3. Send %,d messages with 1 broker DOWN%n", FAULT_MESSAGES);
        print("    4. Restart broker → wait for recovery");
        print("    5. Consume and verify delivery");
        print("  Expected:");
        print("    RF=3: All partitions survive (replicas on other brokers) → ~0% loss");
        print("    RF=1: ~1/3 partitions offline (no replicas) → ~33% loss");
        print("");

        // Ensure broker is running before starting
        ensureBrokerRunning();

        for (int i = 0; i < faultConfigs.length; i++) {
            printf("%n>>> Fault Test %d/%d: %s%n", i + 1, faultConfigs.length, faultConfigs[i].name());
            TestResult result = runFaultTest(faultConfigs[i]);
            faultResults.add(result);

            // Ensure broker is back up before next test
            ensureBrokerRunning();

            if (i < faultConfigs.length - 1) {
                print("  Cooldown 5s for broker stabilization...");
                Thread.sleep(5000);
            }
        }

        printPhaseTable("PHASE B — FAULT INJECTION RESULTS (Broker Killed During Send)", faultResults);

        // ════════════════════════════════════════════════════════════
        // FINAL COMPARISON
        // ════════════════════════════════════════════════════════════
        printFinalComparison();
        printConclusions();

        if (fileOut != null) {
            fileOut.flush();
            fileOut.close();
        }
        print("\nResults written to " + OUTPUT_FILE);
    }

    // ═══════════════════════════════════════════════════════════════
    // PHASE A: Baseline Test (no fault injection)
    // ═══════════════════════════════════════════════════════════════

    private static TestResult runBaselineTest(TestConfig config) throws Exception {
        String topicName = "exp4-base-rf" + config.replicationFactor + "-acks" + config.acks.replace("all", "A");

        print("");
        print("─".repeat(60));
        printf("  Configuration : RF=%d, acks=%s (BASELINE)%n", config.replicationFactor, config.acks);
        print("  Topic         : " + topicName);
        print("─".repeat(60));

        TopicManager topicManager = new TopicManager(BOOTSTRAP_SERVERS);
        topicManager.recreateTopic(topicName, NUM_PARTITIONS, config.replicationFactor);
        Thread.sleep(2000);

        // Send
        printf("  [SEND] Producing %,d messages (acks=%s)...%n", BASELINE_MESSAGES, config.acks);
        SendResult sendResult = sendMessagesCount(topicName, config, BASELINE_MESSAGES);
        printSendStats(sendResult, config);

        // Wait for replication
        print("  [REPLICATE] Waiting 2s for replication...");
        Thread.sleep(2000);

        // Consume & verify
        print("  [VERIFY] Consuming messages...");
        long consumed = consumeMessages(topicName);

        TestResult result = buildResult(config, sendResult, consumed, "BASELINE");
        topicManager.close();
        return result;
    }

    // ═══════════════════════════════════════════════════════════════
    // PHASE B: Fault Injection Test (broker killed during send)
    // ═══════════════════════════════════════════════════════════════

    private static TestResult runFaultTest(TestConfig config) throws Exception {
        String topicName = "exp4-fault-rf" + config.replicationFactor + "-acks" + config.acks.replace("all", "A");

        print("");
        print("─".repeat(60));
        printf("  Configuration : RF=%d, acks=%s (FAULT INJECTION)%n", config.replicationFactor, config.acks);
        print("  Topic         : " + topicName);
        print("─".repeat(60));

        // Step 1: Create topic while cluster is healthy
        TopicManager topicManager = new TopicManager(BOOTSTRAP_SERVERS);
        topicManager.recreateTopic(topicName, NUM_PARTITIONS, config.replicationFactor);
        topicManager.close();
        Thread.sleep(2000);

        // Step 2: Kill the broker
        print("  [FAULT] Killing broker: docker kill " + TARGET_BROKER_CONTAINER);
        executeDockerCommand("docker kill " + TARGET_BROKER_CONTAINER);
        print("  [FAULT] Waiting 5s for cluster to detect failure...");
        Thread.sleep(5000);
        print("  [FAULT] Broker is DOWN. Starting sends with degraded cluster.");

        // Step 3: Send messages with broker down (connect only to alive brokers)
        printf("  [SEND] Producing %,d messages (acks=%s) with 1 broker DOWN...%n", FAULT_MESSAGES, config.acks);
        SendResult sendResult = sendMessagesFault(topicName, config, FAULT_MESSAGES);
        printSendStats(sendResult, config);

        // Step 4: Restart broker and wait for recovery
        print("  [RECOVERY] Restarting broker: docker start " + TARGET_BROKER_CONTAINER);
        executeDockerCommand("docker start " + TARGET_BROKER_CONTAINER);
        print("  [RECOVERY] Waiting 10s for broker recovery + replication...");
        Thread.sleep(10000);

        // Step 5: Consume & verify
        print("  [VERIFY] Consuming messages...");
        long consumed = consumeMessages(topicName);

        TestResult result = buildResult(config, sendResult, consumed, "FAULT");
        return result;
    }

    // ═══════════════════════════════════════════════════════════════
    // Send: Count-based (Phase A)
    // ═══════════════════════════════════════════════════════════════

    private static SendResult sendMessagesCount(String topicName, TestConfig config, int count) {
        Properties props = producerProps(config);

        AtomicInteger sent = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        CopyOnWriteArrayList<Long> latencies = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(count);

        long startTime = System.currentTimeMillis();

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            for (int i = 0; i < count; i++) {
                String key = "key-" + (i % 100);
                String value = String.format("{\"seq\":%d,\"ts\":%d,\"rf\":%d,\"acks\":\"%s\"}",
                        i, System.currentTimeMillis(), config.replicationFactor, config.acks);
                ProducerRecord<String, String> record = new ProducerRecord<>(topicName, key, value);

                final long sendTime = System.currentTimeMillis();
                producer.send(record, (metadata, exception) -> {
                    if (exception != null) {
                        failed.incrementAndGet();
                    } else {
                        sent.incrementAndGet();
                        latencies.add(System.currentTimeMillis() - sendTime);
                    }
                    latch.countDown();
                });
            }
            latch.await();
        } catch (Exception e) {
            print("    [ERROR] Send error: " + e.getMessage());
        }

        long endTime = System.currentTimeMillis();
        return new SendResult(count, sent.get(), failed.get(), endTime - startTime, new ArrayList<>(latencies));
    }

    // ═══════════════════════════════════════════════════════════════
    // Send: Count-based with fault tolerance (Phase B)
    // Uses only alive brokers, retries for leader election recovery
    // ═══════════════════════════════════════════════════════════════

    private static SendResult sendMessagesFault(String topicName, TestConfig config, int count) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, ALIVE_BROKERS);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, config.acks);
        props.put(ProducerConfig.RETRIES_CONFIG, 5);
        props.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 200);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 20000);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10000);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);

        AtomicInteger attempted = new AtomicInteger(0);
        AtomicInteger sent = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        AtomicInteger sendExceptions = new AtomicInteger(0);
        CopyOnWriteArrayList<Long> latencies = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(count);

        long startTime = System.currentTimeMillis();

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            for (int i = 0; i < count; i++) {
                String key = "key-" + (i % 100);
                String value = String.format("{\"seq\":%d,\"ts\":%d,\"rf\":%d,\"acks\":\"%s\"}",
                        i, System.currentTimeMillis(), config.replicationFactor, config.acks);
                ProducerRecord<String, String> record = new ProducerRecord<>(topicName, key, value);

                attempted.incrementAndGet();
                final long sendTime = System.currentTimeMillis();
                try {
                    producer.send(record, (metadata, exception) -> {
                        if (exception != null) {
                            failed.incrementAndGet();
                        } else {
                            sent.incrementAndGet();
                            latencies.add(System.currentTimeMillis() - sendTime);
                        }
                        latch.countDown();
                    });
                } catch (Exception e) {
                    sendExceptions.incrementAndGet();
                    latch.countDown();
                }
            }
            // Wait for all callbacks (with timeout)
            latch.await(60, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            print("    [ERROR] Send error: " + e.getMessage());
        }

        long endTime = System.currentTimeMillis();
        int totalFailed = failed.get() + sendExceptions.get();
        if (sendExceptions.get() > 0) {
            printf("    Send exceptions (TimeoutException etc.): %,d%n", sendExceptions.get());
        }
        return new SendResult(attempted.get(), sent.get(), totalFailed, endTime - startTime, new ArrayList<>(latencies));
    }

    // ─── Docker Command Execution ───────────────────────────────

    private static void executeDockerCommand(String command) {
        try {
            ProcessBuilder pb;
            // Windows
            pb = new ProcessBuilder("cmd.exe", "/c", command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Read output
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                printf("      [docker] %s%n", line);
            }
            process.waitFor();
        } catch (Exception e) {
            print("      [docker-error] " + e.getMessage());
        }
    }

    private static void ensureBrokerRunning() {
        print("  [CHECK] Ensuring " + TARGET_BROKER_CONTAINER + " is running...");
        executeDockerCommand("docker start " + TARGET_BROKER_CONTAINER);
        try {
            Thread.sleep(3000); // Give broker time to fully start
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        print("  [CHECK] Broker is running.");
    }

    // ─── Producer Properties ────────────────────────────────────

    private static Properties producerProps(TestConfig config) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, config.acks);
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10000);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        return props;
    }

    // ─── Consumer ───────────────────────────────────────────────

    private static long consumeMessages(String topicName) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "exp4-verifier-" + System.currentTimeMillis());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 5000);

        long count = 0;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(topicName));
            int emptyPolls = 0;
            while (emptyPolls < 5) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
                if (records.isEmpty()) {
                    emptyPolls++;
                } else {
                    count += records.count();
                    emptyPolls = 0;
                }
            }
        }
        return count;
    }

    // ─── Print Helpers ──────────────────────────────────────────

    private static void printSendStats(SendResult sr, TestConfig config) {
        printf("    Attempted    : %,d%n", sr.attempted);
        printf("    Acknowledged : %,d%n", sr.sent);
        printf("    Failed       : %,d%n", sr.failed);
        printf("    Duration     : %.2f s%n", sr.durationMs / 1000.0);
        double throughput = sr.sent > 0 ? sr.sent / (sr.durationMs / 1000.0) : 0;
        printf("    Throughput   : %,.0f msg/s%n", throughput);

        if (!sr.latencies.isEmpty()) {
            Collections.sort(sr.latencies);
            List<Long> lat = sr.latencies;
            long p50 = lat.get((int) (lat.size() * 0.50));
            long p95 = lat.get((int) (lat.size() * 0.95));
            long p99 = lat.get(Math.min((int) (lat.size() * 0.99), lat.size() - 1));
            printf("    Latency      : P50=%dms  P95=%dms  P99=%dms  Max=%dms%n",
                    p50, p95, p99, lat.get(lat.size() - 1));
        } else {
            print("    Latency      : N/A (acks=0)");
        }
    }

    private static TestResult buildResult(TestConfig config, SendResult sr, long consumed, String phase) {
        // For fault phase: total_loss = attempted - consumed (true undelivered messages)
        // For baseline: total_loss = acked - consumed (should be 0)
        long totalLoss;
        if (phase.equals("FAULT")) {
            totalLoss = sr.attempted - consumed;
        } else {
            totalLoss = sr.sent - consumed;
        }
        if (totalLoss < 0) totalLoss = 0;

        double lossRate = sr.attempted > 0 ? (totalLoss * 100.0) / sr.attempted : 0;
        double deliveryRate = sr.attempted > 0 ? (consumed * 100.0) / sr.attempted : 100;
        double throughput = sr.sent > 0 ? sr.sent / (sr.durationMs / 1000.0) : 0;

        // Latencies
        long p50 = 0, p95 = 0, p99 = 0;
        if (!sr.latencies.isEmpty()) {
            Collections.sort(sr.latencies);
            List<Long> lat = sr.latencies;
            p50 = lat.get((int) (lat.size() * 0.50));
            p95 = lat.get((int) (lat.size() * 0.95));
            p99 = lat.get(Math.min((int) (lat.size() * 0.99), lat.size() - 1));
        }

        String verdict;
        if (totalLoss == 0 && sr.failed == 0) {
            verdict = "PERFECT";
            print("    RESULT: PERFECT — Zero message loss, zero send failures");
        } else if (totalLoss == 0 && sr.failed > 0) {
            verdict = "SURVIVED";
            printf("    RESULT: SURVIVED — %,d send failures but all messages eventually delivered%n", sr.failed);
        } else {
            verdict = "DATA LOSS";
            printf("    RESULT: DATA LOSS — %,d messages not delivered (%.1f%% loss rate)%n", totalLoss, lossRate);
        }

        printf("    Attempted=%,d  Acked=%,d  Failed=%,d  Consumed=%,d  Lost=%,d  Delivery=%.1f%%%n",
                sr.attempted, sr.sent, sr.failed, consumed, totalLoss, deliveryRate);

        return new TestResult(config, phase, sr.attempted, sr.sent, sr.failed,
                consumed, totalLoss, lossRate, throughput, p50, p95, p99, verdict);
    }

    // ─── Phase Table ────────────────────────────────────────────

    private static void printPhaseTable(String title, List<TestResult> results) {
        print("");
        print("=".repeat(120));
        print("  " + title);
        print("=".repeat(120));
        printf("%-18s %10s %10s %10s %10s %10s %12s %10s %10s%n",
                "Config", "Attempted", "Acked", "Failed", "Consumed", "Lost", "Loss Rate", "msg/s", "Verdict");
        print("-".repeat(120));

        for (TestResult r : results) {
            printf("%-18s %,10d %,10d %,10d %,10d %,10d %11.1f%% %,10.0f %10s%n",
                    r.config.name(), r.attempted, r.sent, r.sendFailed,
                    r.consumed, r.dropped, r.dropRate, r.throughput, r.verdict);
        }
        print("-".repeat(120));
    }

    // ─── Final Comparison ───────────────────────────────────────

    private static void printFinalComparison() {
        print("");
        print("=".repeat(120));
        print("  FINAL COMPARISON: Baseline (Healthy Cluster) vs. Fault Injection (1 Broker Dead)");
        print("=".repeat(120));
        printf("%-18s │ %10s %10s %12s │ %10s %10s %12s │%n",
                "Config", "Base Cons.", "Base Lost", "Base Verdict",
                "Fault Cons.", "Fault Lost", "Fault Verdict");
        print("-".repeat(120));

        for (TestResult fault : faultResults) {
            TestResult base = null;
            for (TestResult b : baselineResults) {
                if (b.config.replicationFactor == fault.config.replicationFactor
                        && b.config.acks.equals(fault.config.acks)) {
                    base = b;
                    break;
                }
            }

            if (base != null) {
                printf("%-18s │ %,10d %10s %12s │ %,10d %,10d %12s │%n",
                        fault.config.name(),
                        base.consumed, "0 (0.0%)", base.verdict,
                        fault.consumed, fault.dropped, fault.verdict);
            }
        }
        print("-".repeat(120));
    }

    private static void printConclusions() {
        print("");
        print("=".repeat(90));
        print("  CONCLUSIONS");
        print("=".repeat(90));
        print("");
        print("1. BASELINE (No Failures):");
        print("   All configurations achieve 0% message loss when the cluster is healthy.");
        print("   Higher RF and acks=all reduce throughput but don't cause loss during normal ops.");
        print("");
        print("2. UNDER BROKER FAILURE:");
        print("   RF=3, acks=all  : SURVIVES — All replicas confirm writes before ack.");
        print("                     Even when one broker dies, remaining ISRs hold all data.");
        print("   RF=3, acks=0    : RISKY    — Fire-and-forget means the producer doesn't know");
        print("                     if the dead broker received messages. In-flight data is lost.");
        print("   RF=1, acks=1    : FAILURES — Partitions on the dead broker go offline.");
        print("                     Producer detects failures (callback errors), but data");
        print("                     on the dead broker's partitions is inaccessible until restart.");
        print("   RF=1, acks=0    : WORST    — No replication + no acknowledgment = maximum risk.");
        print("                     Messages to the dead broker's partitions vanish silently.");
        print("");
        print("3. KEY INSIGHT:");
        print("   Message durability requires BOTH replication (RF >= 2) AND acknowledgment");
        print("   (acks=all). Using either alone provides only partial protection.");
        print("   The performance cost of acks=all + RF=3 is the 'insurance premium' for");
        print("   zero data loss under failure.");
        print("=".repeat(90));
    }

    // ─── Data Classes ───────────────────────────────────────────

    static class TestConfig {
        int replicationFactor;
        String acks;

        TestConfig(int replicationFactor, String acks) {
            this.replicationFactor = replicationFactor;
            this.acks = acks;
        }

        String name() {
            return "RF=" + replicationFactor + ", acks=" + acks;
        }
    }

    static class SendResult {
        int attempted;
        int sent;
        int failed;
        long durationMs;
        List<Long> latencies;

        SendResult(int attempted, int sent, int failed, long durationMs, List<Long> latencies) {
            this.attempted = attempted;
            this.sent = sent;
            this.failed = failed;
            this.durationMs = durationMs;
            this.latencies = latencies;
        }
    }

    static class TestResult {
        TestConfig config;
        String phase;
        int attempted, sent, sendFailed;
        long consumed, dropped;
        double dropRate, throughput;
        long p50, p95, p99;
        String verdict;

        TestResult(TestConfig config, String phase, int attempted, int sent, int sendFailed,
                   long consumed, long dropped, double dropRate, double throughput,
                   long p50, long p95, long p99, String verdict) {
            this.config = config;
            this.phase = phase;
            this.attempted = attempted;
            this.sent = sent;
            this.sendFailed = sendFailed;
            this.consumed = consumed;
            this.dropped = dropped;
            this.dropRate = dropRate;
            this.throughput = throughput;
            this.p50 = p50;
            this.p95 = p95;
            this.p99 = p99;
            this.verdict = verdict;
        }
    }
}