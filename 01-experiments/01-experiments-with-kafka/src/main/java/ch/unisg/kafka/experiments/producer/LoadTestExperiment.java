package ch.unisg.kafka.experiments.producer;

import ch.unisg.kafka.experiments.util.MetricsCollector;
import ch.unisg.kafka.experiments.util.TopicManager;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// Vary producer threads (1, 2, 4, 8) sending 50K messages each, then verify with a consumer
public class LoadTestExperiment {

    private static final String BOOTSTRAP_SERVERS = "localhost:9092";
    private static final String TOPIC = "exp2-load-test";
    private static final int MSGS_PER_THREAD = 50_000;

    public static void main(String[] args) throws Exception {
        int[] threadCounts = {1, 2, 4, 8};

        System.out.println("Experiment 2: Load Test - Concurrent Producers (" + MSGS_PER_THREAD + " messages per thread)");

        for (int threads : threadCounts) {
            Thread.sleep(5000)  ;
            runLoadTest(threads);
        }

        System.out.println("\nExperiment 2 complete.");
    }

    private static void runLoadTest(int numThreads) throws Exception {
        // use helper to delete and recreate topic just in case
        TopicManager topicManager = new TopicManager(BOOTSTRAP_SERVERS);
        topicManager.recreateTopic(TOPIC, 1, 1);
        Thread.sleep(5000); // wait for topic to be fully ready

        int totalMessages = numThreads * MSGS_PER_THREAD;
        MetricsCollector metrics = new MetricsCollector("threads=" + numThreads);
        CountDownLatch latch = new CountDownLatch(totalMessages);
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        System.out.printf("%nSending %,d messages with %d threads ...%n", totalMessages, numThreads);
        metrics.start();

        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                Properties props = new Properties();
                props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
                props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                props.put(ProducerConfig.ACKS_CONFIG, "1");

                try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
                    for (int i = 0; i < MSGS_PER_THREAD; i++) {
                        String key = "t" + threadId + "-" + i;
                        String value = "thread-" + threadId + "-msg-" + i;
                        long sendStart = System.currentTimeMillis();

                        producer.send(new ProducerRecord<>(TOPIC, key, value), (metadata, exception) -> {
                            long latency = System.currentTimeMillis() - sendStart;
                            metrics.recordLatency(latency);
                            if (exception != null) {
                                metrics.incrementFailed();
                            } else {
                                metrics.incrementSent();
                            }
                            latch.countDown();
                        });
                    }
                }
            });
        }

        latch.await();
        metrics.stop();
        executor.shutdown();

        // consume all messages back to measure drop rate (sent vs actually received)
        long consumed = verifyWithConsumer();

        metrics.printSummary();
        System.out.printf("  Consumer verified: %,d / %,d", consumed, totalMessages);

        topicManager.close();
    }

    private static long verifyWithConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "exp2-verifier-" + System.currentTimeMillis());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        long count = 0;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(TOPIC));
            long emptyPolls = 0;
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
}
