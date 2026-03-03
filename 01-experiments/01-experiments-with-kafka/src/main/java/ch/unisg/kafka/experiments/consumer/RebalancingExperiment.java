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

/**
 * Experiment: Multiple Consumers & Consumer Groups
 * 
 * Tests rebalancing behavior when consumers join/leave the group
 * Observes partition distribution among consumers
 */
public class RebalancingExperiment {

    private static final String BOOTSTRAP_SERVERS = "localhost:9092";
    private static final String TOPIC = "exp-rebalancing";
    private static final String GROUP_ID = "rebalancing-group";
    private static final int NUM_MESSAGES = 10_000;
    private static final int PARTITIONS = 4; // Multiple partitions for rebalancing
    private static final int REPLICATION_FACTOR = 1;

    public static void main(String[] args) throws Exception {
        System.out.println("Rebalancing Experiment");
        System.out.println("=".repeat(60));

        TopicManager topicManager = new TopicManager(BOOTSTRAP_SERVERS);
        topicManager.recreateTopic(TOPIC, PARTITIONS, REPLICATION_FACTOR);
        Thread.sleep(2000);

        // Produce messages first
        System.out.println("\nProducing " + NUM_MESSAGES + " messages...");
        produceMessages();
        Thread.sleep(2000);

        // Test 1: Single consumer
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Test 1: Single Consumer");
        System.out.println("=".repeat(60));
        runConsumer("consumer-1", GROUP_ID, 0);

        // Test 2: Two consumers (rebalancing)
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Test 2: Two Consumers (Rebalancing)");
        System.out.println("=".repeat(60));
        
        CountDownLatch latch = new CountDownLatch(2);
        
        Thread consumer1 = new Thread(() -> {
            runConsumer("consumer-1", GROUP_ID, 2000); // Start after 2 seconds
            latch.countDown();
        });
        
        Thread consumer2 = new Thread(() -> {
            runConsumer("consumer-2", GROUP_ID, 5000); // Start after 5 seconds
            latch.countDown();
        });

        consumer1.start();
        consumer2.start();
        
        latch.await();
        
        // Test 3: Different consumer groups
        System.out.println("\n" + "=".repeat(60));
        System.out.println("Test 3: Different Consumer Groups");
        System.out.println("=".repeat(60));
        
        CountDownLatch groupLatch = new CountDownLatch(2);
        
        Thread groupA = new Thread(() -> {
            runConsumer("groupA-consumer", "groupA", 0);
            groupLatch.countDown();
        });
        
        Thread groupB = new Thread(() -> {
            runConsumer("groupB-consumer", "groupB", 0);
            groupLatch.countDown();
        });

        groupA.start();
        groupB.start();
        
        groupLatch.await();

        topicManager.close();
        System.out.println("\nExperiment completed.");
    }

    private static void runConsumer(String consumerName, String groupId, long delayMs) {
        try {
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }

            Properties props = new Properties();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
            props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
            props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

            MetricsCollector metrics = new MetricsCollector(consumerName + " (group: " + groupId + ")");
            KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
            consumer.subscribe(Collections.singletonList(TOPIC));

            System.out.println("\n[" + consumerName + "] Starting consumer...");
            metrics.start();

            long messagesReceived = 0;
            long startTime = System.currentTimeMillis();
            long timeout = startTime + 15000; // 15 second timeout

            while (System.currentTimeMillis() < timeout && messagesReceived < NUM_MESSAGES) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
                
                for (ConsumerRecord<String, String> record : records) {
                    messagesReceived++;
                    metrics.incrementSent();
                    
                    // Print partition assignment info
                    if (messagesReceived == 1 || messagesReceived % 1000 == 0) {
                        System.out.println("[" + consumerName + "] Received message from partition " + 
                                         record.partition() + " (total: " + messagesReceived + ")");
                    }
                }
            }

            metrics.stop();
            consumer.close();

            System.out.println("\n[" + consumerName + "] Finished. Total messages: " + messagesReceived);
            metrics.printSummary();

        } catch (Exception e) {
            System.err.println("Error in " + consumerName + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void produceMessages() throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        
        KafkaProducer<String, String> producer = new KafkaProducer<>(props);
        
        for (int i = 0; i < NUM_MESSAGES; i++) {
            producer.send(new ProducerRecord<>(
                TOPIC, "key-" + i, "value-" + i));
            
            if (i % 2000 == 0 && i > 0) {
                producer.flush();
                System.out.println("  Produced " + i + " messages...");
            }
        }
        
        producer.flush();
        producer.close();
        System.out.println("  Produced all " + NUM_MESSAGES + " messages.");
    }
}
