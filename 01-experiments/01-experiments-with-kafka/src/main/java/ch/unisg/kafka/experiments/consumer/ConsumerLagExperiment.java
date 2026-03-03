package ch.unisg.kafka.experiments.consumer;

import ch.unisg.kafka.experiments.util.MetricsCollector;
import ch.unisg.kafka.experiments.util.TopicManager;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Experiment: Consumer Lag & Data Loss Risks
 * 
 * Tests how consumer lag builds up when processing is slow.
 * Introduces artificial delays to simulate slow processing.
 */
public class ConsumerLagExperiment {

    private static final String BOOTSTRAP_SERVERS = "localhost:9092";
    private static final String TOPIC = "exp-consumer-lag";
    private static final String GROUP_ID = "lag-experiment-group";
    private static final int NUM_MESSAGES = 10_000;
    private static final int PARTITIONS = 1;
    private static final int REPLICATION_FACTOR = 1;

    public static void main(String[] args) throws Exception {
        System.out.println("Consumer Lag Experiment");
        System.out.println("=".repeat(60));
        
        // Test different processing delays
        int[] delays = {0, 10, 50, 100}; // milliseconds per message
        
        for (int delay : delays) {
            runLagTest(delay);
            Thread.sleep(2000); // Wait between tests
        }
        
        System.out.println("\nExperiment completed.");
    }

    private static void runLagTest(int processingDelayMs) throws Exception {
        TopicManager topicManager = new TopicManager(BOOTSTRAP_SERVERS);
        topicManager.recreateTopic(TOPIC, PARTITIONS, REPLICATION_FACTOR);
        Thread.sleep(2000);

        // Start producer in background
        Thread producerThread = new Thread(() -> {
            try {
                produceMessages();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        producerThread.start();
        
        // Wait a bit for messages to accumulate
        Thread.sleep(5000);

        // Consumer properties
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP_ID + "-delay-" + processingDelayMs);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        MetricsCollector metrics = new MetricsCollector("Consumer Lag Test (delay=" + processingDelayMs + "ms)");
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singletonList(TOPIC));

        System.out.println("\nStarting consumer with " + processingDelayMs + "ms delay per message...");
        metrics.start();

        AtomicLong processedCount = new AtomicLong(0);
        CountDownLatch latch = new CountDownLatch(1);

        // Monitor lag in background
        Thread lagMonitorThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(5000); // Check every 5 seconds
                    System.out.println("  [Lag Check] Processed: " + processedCount.get() + " messages");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        lagMonitorThread.start();

        // Process messages with delay
        long startTime = System.currentTimeMillis();
        while (processedCount.get() < NUM_MESSAGES) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(100));
            
            for (ConsumerRecord<String, String> record : records) {
                // Simulate processing delay
                if (processingDelayMs > 0) {
                    Thread.sleep(processingDelayMs);
                }
                
                long latency = System.currentTimeMillis() - startTime;
                metrics.recordLatency(latency);
                metrics.incrementSent();
                processedCount.incrementAndGet();
            }
        }

        lagMonitorThread.interrupt();
        metrics.stop();
        consumer.close();
        topicManager.close();

        metrics.printSummary();
    }

    private static void produceMessages() throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        
        KafkaProducer<String, String> producer = new KafkaProducer<>(props);
        
        for (int i = 0; i < NUM_MESSAGES; i++) {
            producer.send(new ProducerRecord<>(TOPIC, "key-" + i, "value-" + i));
            if (i % 1000 == 0) {
                Thread.sleep(100); // Slow down production slightly
            }
        }
        
        producer.close();
    }
}
