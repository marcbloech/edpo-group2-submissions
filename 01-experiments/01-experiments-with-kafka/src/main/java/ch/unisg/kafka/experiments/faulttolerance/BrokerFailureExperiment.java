package ch.unisg.kafka.experiments.faulttolerance;
import ch.unisg.kafka.experiments.util.TopicManager;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.io.*;

// Experiment 3: Broker Failures & Leader Elections
// Kill a broker and observe leader election, measure failover time and impact
public class BrokerFailureExperiment {

    private static final String BOOTSTRAP_SERVERS = "localhost:9092,localhost:9094,localhost:9096";
    private static final String TOPIC = "exp3-broker-failure-test";
    private static final int NUM_MESSAGES = 100_000;
    private static final int REPLICATION_FACTOR = 3;
    private static final int NUM_PARTITIONS = 3;
    private static final String OUTPUT_FILE = "broker-failure-results.txt";

    private static class PerformanceMetrics {
        private final List<Long> latencies = new CopyOnWriteArrayList<>();
        private final Map<Long, Integer> throughputPerSecond = new ConcurrentHashMap<>();
        private final long startTime = System.currentTimeMillis();

        public void recordLatency(long latencyMs) {
            latencies.add(latencyMs);
            long second = (System.currentTimeMillis() - startTime) / 1000;
            throughputPerSecond.merge(second, 1, Integer::sum);
        }

        public void printDetailedMetrics() {
            if (latencies.isEmpty()) return;

            List<Long> sorted = new ArrayList<>(latencies);
            Collections.sort(sorted);

            long sum = sorted.stream().mapToLong(Long::longValue).sum();
            long avg = sum / sorted.size();
            long min = sorted.get(0);
            long max = sorted.get(sorted.size() - 1);
            long p50 = sorted.get((int) (sorted.size() * 0.50));
            long p95 = sorted.get((int) (sorted.size() * 0.95));
            long p99 = sorted.get((int) (sorted.size() * 0.99));

            System.out.println("\nLatency Metrics (milliseconds):");
            System.out.printf("  Min: %d ms%n", min);
            System.out.printf("  Avg: %d ms%n", avg);
            System.out.printf("  Max: %d ms%n", max);
            System.out.printf("  P50 (median): %d ms%n", p50);
            System.out.printf("  P95: %d ms%n", p95);
            System.out.printf("  P99: %d ms%n", p99);

            if (!throughputPerSecond.isEmpty()) {
                double avgThroughput = throughputPerSecond.values().stream()
                    .mapToInt(Integer::intValue).average().orElse(0);
                int maxThroughput = throughputPerSecond.values().stream()
                    .mapToInt(Integer::intValue).max().orElse(0);
                int minThroughput = throughputPerSecond.values().stream()
                    .mapToInt(Integer::intValue).min().orElse(0);

                System.out.println("\nThroughput Metrics:");
                System.out.printf("  Average: %.1f msg/s%n", avgThroughput);
                System.out.printf("  Minimum: %d msg/s%n", minThroughput);
                System.out.printf("  Maximum: %d msg/s%n", maxThroughput);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        // Duplicate output to both console and file
        FileOutputStream fileOutputStream = new FileOutputStream(OUTPUT_FILE);
        PrintStream filePrint = new PrintStream(fileOutputStream);
        PrintStream console = System.out;
        PrintStream dualOutput = new PrintStream(new OutputStream() {
            public void write(int b) throws IOException {
                console.write(b);
                filePrint.write(b);
            }
            public void write(byte[] b, int off, int len) throws IOException {
                console.write(b, off, len);
                filePrint.write(b, off, len);
            }
        });
        System.setOut(dualOutput);
        System.setErr(dualOutput);
        
        System.out.println("=".repeat(80));
        System.out.println("Experiment 3: Broker Failures & Leader Elections");
        System.out.println("OUTPUT WILL BE SAVED TO: " + OUTPUT_FILE);
        System.out.println("=".repeat(80));
        System.out.println("\nThis experiment tests Kafka's fault tolerance and leader election:");
        System.out.println("  1. Creates a topic with 3 partitions and replication factor 3");
        System.out.println("  2. Starts continuous message production");
        System.out.println("  3. Monitors leader and ISR changes for each partition");
        System.out.println("  4. Detects broker failures and measures failover time");
        System.out.println("  5. Verifies message consumption after failures\n");
        System.out.println("MANUAL ACTION REQUIRED:");
        System.out.println("  - While the experiment is running, kill a broker using:");
        System.out.println("    docker stop docker-kafka1-1  (or kafka2-1 or kafka3-1)");
        System.out.println("  - Observe the leader election and failover behavior");
        System.out.println("  - Optionally restart the broker after 30 seconds");
        System.out.println("=".repeat(80));

        try {
            runBrokerFailureTest();
        } finally {
            filePrint.close();
            fileOutputStream.close();
        }
    }

    private static void runBrokerFailureTest() throws Exception {
        TopicManager topicManager = new TopicManager(BOOTSTRAP_SERVERS);
        topicManager.recreateTopic(TOPIC, NUM_PARTITIONS, REPLICATION_FACTOR);
        Thread.sleep(5000);

        Properties adminProps = new Properties();
        adminProps.put("bootstrap.servers", BOOTSTRAP_SERVERS);
        AdminClient adminClient = AdminClient.create(adminProps);

        // Print initial cluster state
        System.out.println("\n--- Initial Cluster State ---");
        printClusterState(adminClient);

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong messagesSent = new AtomicLong(0);
        AtomicLong messagesFailed = new AtomicLong(0);
        PerformanceMetrics metrics = new PerformanceMetrics();
        ExecutorService executor = Executors.newFixedThreadPool(3);

        // Start producer thread
        executor.submit(() -> runProducer(running, messagesSent, messagesFailed, metrics));

        // Start cluster monitor thread
        executor.submit(() -> monitorCluster(adminClient, running));

        // Start consumer thread
        CountDownLatch consumerLatch = new CountDownLatch(1);
        executor.submit(() -> runConsumer(running, consumerLatch));

        // Run for 2 minutes or until interrupted
        System.out.println("\n--- Experiment Running (will run for 2 minutes) ---");
        System.out.println("Kill a broker now to observe failover...\n");

        Thread.sleep(120_000); // Run for 2 minutes

        System.out.println("\n--- Stopping Experiment ---");
        running.set(false);
        
        Thread.sleep(5000); // Allow time for graceful shutdown
        executor.shutdownNow();
        consumerLatch.await();

        System.out.println("\n--- Final Statistics ---");
        System.out.printf("Total Messages Sent: %,d%n", messagesSent.get());
        System.out.printf("Total Messages Failed: %,d%n", messagesFailed.get());
        System.out.printf("Success Rate: %.2f%%%n", 
            (messagesSent.get() * 100.0) / (messagesSent.get() + messagesFailed.get()));
        
        metrics.printDetailedMetrics();

        adminClient.close();
        topicManager.close();

        System.out.println("\n--- Experiment Complete ---");
    }

    private static void runProducer(AtomicBoolean running, AtomicLong sent, AtomicLong failed, PerformanceMetrics metrics) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.RETRIES_CONFIG, 5);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        props.put(ProducerConfig.RETRY_BACKOFF_MS_CONFIG, 1000);
        props.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            int counter = 0;
            while (running.get()) {
                String key = "key-" + counter;
                long sendTime = System.currentTimeMillis();
                String value = "msg-" + counter + "-ts-" + sendTime;
                ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, key, value);

                // Async send with timestamp in closure for accurate latency
                producer.send(record, (metadata, exception) -> {
                    long latency = System.currentTimeMillis() - sendTime;
                    if (exception != null) {
                        failed.incrementAndGet();
                        System.err.println("[SEND FAILED] " + exception.getMessage());
                    } else {
                        sent.incrementAndGet();
                        metrics.recordLatency(latency);
                    }
                });

                counter++;
                
                // Small pause every 100 messages for steady throughput
                if (counter % 100 == 0) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Producer error: " + e.getMessage());
        }
    }

    private static void monitorCluster(AdminClient adminClient, AtomicBoolean running) {
        Map<Integer, Integer> previousLeaders = new HashMap<>();
        Map<Integer, String> previousISRs = new HashMap<>();
        Map<Integer, Long> leaderChangeTimestamps = new HashMap<>();

        while (running.get()) {
            try {
                Map<String, TopicDescription> descriptions = 
                    adminClient.describeTopics(Collections.singleton(TOPIC)).all().get();
                TopicDescription desc = descriptions.get(TOPIC);

                for (TopicPartitionInfo partitionInfo : desc.partitions()) {
                    int partition = partitionInfo.partition();
                    Node leader = partitionInfo.leader();
                    List<Node> isr = partitionInfo.isr();
                    
                    int currentLeaderId = (leader != null) ? leader.id() : -1;
                    String currentISR = isr.stream()
                        .map(n -> String.valueOf(n.id()))
                        .reduce((a, b) -> a + "," + b)
                        .orElse("");

                    Integer prevLeader = previousLeaders.get(partition);
                    String prevISR = previousISRs.get(partition);

                    // Detect leader change
                    if (prevLeader != null && prevLeader != currentLeaderId) {
                        long now = System.currentTimeMillis();
                        Long lastChange = leaderChangeTimestamps.get(partition);
                        
                        System.out.println("\n[LEADER ELECTION] Detected leader change");
                        System.out.printf("  Partition %d: Leader changed %d -> %d%n", 
                            partition, prevLeader, currentLeaderId);
                        
                        if (lastChange != null) {
                            long failoverTime = now - lastChange;
                            System.out.printf("  Failover time: %d ms%n", failoverTime);
                        }
                        
                        leaderChangeTimestamps.put(partition, now);
                    }

                    // Detect ISR change
                    if (prevISR != null && !prevISR.equals(currentISR)) {
                        System.out.println("\n[ISR CHANGE] Detected ISR modification");
                        System.out.printf("  Partition %d: ISR changed [%s] -> [%s]%n", 
                            partition, prevISR, currentISR);
                    }

                    previousLeaders.put(partition, currentLeaderId);
                    previousISRs.put(partition, currentISR);
                }

                Thread.sleep(1000); // Check every second

            } catch (Exception e) {
                System.err.println("[MONITORING ERROR] " + e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
    }

    private static void runConsumer(AtomicBoolean running, CountDownLatch latch) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "exp3-verifier-" + System.currentTimeMillis());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(TOPIC));
            
            long messagesConsumed = 0;
            while (running.get()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
                messagesConsumed += records.count();
                
                if (messagesConsumed % 100 == 0 && messagesConsumed > 0) {
                    System.out.printf("[CONSUMER] Progress: %,d messages consumed%n", messagesConsumed);
                }
            }
            
            System.out.printf("\n[CONSUMER] Final count: %,d messages%n", messagesConsumed);
        } catch (Exception e) {
            System.err.println("[CONSUMER ERROR] " + e.getMessage());
        } finally {
            latch.countDown();
        }
    }

    private static void printClusterState(AdminClient adminClient) throws Exception {
        Map<String, TopicDescription> descriptions = 
            adminClient.describeTopics(Collections.singleton(TOPIC)).all().get();
        TopicDescription desc = descriptions.get(TOPIC);

        System.out.printf("Topic: %s%n", TOPIC);
        System.out.printf("Partitions: %d%n", desc.partitions().size());
        
        for (TopicPartitionInfo partitionInfo : desc.partitions()) {
            Node leader = partitionInfo.leader();
            List<Node> isr = partitionInfo.isr();
            List<Node> replicas = partitionInfo.replicas();
            
            System.out.printf("  Partition %d:%n", partitionInfo.partition());
            System.out.printf("    Leader: %s%n", leader != null ? leader.id() : "NONE");
            System.out.printf("    ISR: [%s]%n", 
                isr.stream().map(n -> String.valueOf(n.id())).collect(Collectors.joining(",")));
            System.out.printf("    Replicas: [%s]%n", 
                replicas.stream().map(n -> String.valueOf(n.id())).collect(Collectors.joining(",")));
        }
    }
}
