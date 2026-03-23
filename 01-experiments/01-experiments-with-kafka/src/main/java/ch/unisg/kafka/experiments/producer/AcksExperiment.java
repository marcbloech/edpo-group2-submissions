package ch.unisg.kafka.experiments.producer;

import ch.unisg.kafka.experiments.util.MetricsCollector;
import ch.unisg.kafka.experiments.util.ResourceMonitor;
import ch.unisg.kafka.experiments.util.TopicManager;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;
import java.util.concurrent.CountDownLatch;

// Compare throughput and latency for acks=0, acks=1, acks=all
public class AcksExperiment {

    private static final String BOOTSTRAP_SERVERS = "localhost:9092,localhost:9094,localhost:9096";
    private static final String TOPIC = "exp1-acks-test";
    private static final int NUM_MESSAGES = 500_000;
    private static final int REPLICATION_FACTOR = 3;

    public static void main(String[] args) throws Exception {
        String[] acksValues = {"0", "1", "all"};

        System.out.println("Experiment 1: Acks Configuration (" + NUM_MESSAGES + " messages per run)");

        for (String acks : acksValues) {
            runAcksTest(acks);
        }

        System.out.println("\nExperiment completed.");
    }

    private static void runAcksTest(String acks) throws Exception {
        TopicManager topicManager = new TopicManager(BOOTSTRAP_SERVERS);
        // delete and recreate existing topic with same name just in case
        topicManager.recreateTopic(TOPIC, 1, REPLICATION_FACTOR);

        // Wait for topic to be fully ready
        Thread.sleep(5000);
        System.out.println("\nTopic '" + TOPIC + "' is ready.");

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, acks);

        // use our helper class to collect metrics about latency, throughput, and success/failure counts
        MetricsCollector metrics = new MetricsCollector("acks=" + acks);
        ResourceMonitor resourceMonitor = new ResourceMonitor();

        // Create new producer
        KafkaProducer<String, String> producer = new KafkaProducer<>(props);
        System.out.println("\nProducer created with acks=" + acks);
        
        // send() is async, so need to use latch to block until all callbacks have fired
        CountDownLatch latch = new CountDownLatch(NUM_MESSAGES);

        // Wait for everything to be fully ready
        Thread.sleep(5000);

        System.out.printf("%nSending %,d messages with acks=%s ...%n", NUM_MESSAGES, acks);
        metrics.start();
        resourceMonitor.start(500);

        for (int i = 0; i < NUM_MESSAGES; i++) {
            String key = "key-" + i;
            String value = "msg-" + i + "-ts-" + System.currentTimeMillis();

            ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, key, value);
            long sendStart = System.currentTimeMillis();

            // send the message (and record latency and success/failure in the callback)
            producer.send(record, (metadata, exception) -> {
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

        latch.await();
        metrics.stop();
        resourceMonitor.stop();

        producer.close();
        topicManager.close();

        metrics.printSummary();
        resourceMonitor.printSummary();
    }
}
