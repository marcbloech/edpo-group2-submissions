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

/**
 * Experiment: Offset Misconfigurations
 * 
 * Tests auto.offset.reset behavior (earliest vs latest)
 * Demonstrates potential data loss scenarios
 */
public class OffsetResetExperiment {

    private static final String BOOTSTRAP_SERVERS = "localhost:9092";
    private static final String TOPIC = "exp-offset-reset";
    private static final int NUM_MESSAGES = 5_000;
    private static final int PARTITIONS = 1;
    private static final int REPLICATION_FACTOR = 1;

    public static void main(String[] args) throws Exception {
        System.out.println("Offset Reset Experiment");
        System.out.println("=".repeat(60));

        // Test both offset reset strategies
        String[] offsetResets = {"earliest", "latest"};

        for (String offsetReset : offsetResets) {
            runOffsetTest(offsetReset);
            Thread.sleep(2000);
        }

        System.out.println("\nExperiment completed.");
    }

    private static void runOffsetTest(String offsetReset) throws Exception {
        TopicManager topicManager = new TopicManager(BOOTSTRAP_SERVERS);
        topicManager.recreateTopic(TOPIC, PARTITIONS, REPLICATION_FACTOR);
        Thread.sleep(2000);

        // Phase 1: Produce messages while consumer is DOWN
        System.out.println("\nPhase 1: Producing " + NUM_MESSAGES + " messages (consumer is DOWN)...");
        produceMessages(NUM_MESSAGES);
        Thread.sleep(2000);

        // Phase 2: Start consumer with specified offset reset
        System.out.println("\nPhase 2: Starting consumer with auto.offset.reset=" + offsetReset + "...");

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "offset-reset-" + offsetReset);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, offsetReset);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        MetricsCollector metrics = new MetricsCollector("Offset Reset: " + offsetReset);
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singletonList(TOPIC));

        metrics.start();

        long messagesReceived = 0;
        long startTime = System.currentTimeMillis();
        long timeout = startTime + 30000; // 30 second timeout

        while (System.currentTimeMillis() < timeout) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
            
            for (ConsumerRecord<String, String> record : records) {
                messagesReceived++;
                metrics.incrementSent();
                
                if (messagesReceived % 1000 == 0) {
                    System.out.println("  Received " + messagesReceived + " messages...");
                }
            }
            
            // If no more messages and we've received some, break
            if (records.isEmpty() && messagesReceived > 0) {
                break;
            }
        }

        metrics.stop();
        consumer.close();

        System.out.println("\nResults for auto.offset.reset=" + offsetReset + ":");
        System.out.println("  Messages sent while consumer was DOWN: " + NUM_MESSAGES);
        System.out.println("  Messages received by consumer: " + messagesReceived);
        
        if (offsetReset.equals("latest")) {
            long lostMessages = NUM_MESSAGES - messagesReceived;
            System.out.println("  ⚠️  DATA LOSS: " + lostMessages + " messages were lost!");
        } else {
            System.out.println("  ✅ No data loss: All messages received");
        }

        metrics.printSummary();
        topicManager.close();
    }

    private static void produceMessages(int count) throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        
        KafkaProducer<String, String> producer = new KafkaProducer<>(props);
        
        for (int i = 0; i < count; i++) {
            producer.send(new ProducerRecord<>(
                TOPIC, "key-" + i, "value-" + i + "-ts-" + System.currentTimeMillis()));
            
            if (i % 1000 == 0 && i > 0) {
                producer.flush();
                System.out.println("  Produced " + i + " messages...");
            }
        }
        
        producer.flush();
        producer.close();
        System.out.println("  Produced all " + count + " messages.");
    }
}
