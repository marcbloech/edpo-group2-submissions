package ch.unisg.worldpulse.streams;

import ch.unisg.worldpulse.streams.avro.CorrelatedAlert;
import ch.unisg.worldpulse.streams.serialization.avro.AvroDeserializer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tails the "correlated-alerts" topic in a background thread and keeps the
 * most recent N records in an in-memory ring buffer for the REST query API.
 *
 * The C6 KStream-KStream windowed join writes its output to a topic rather
 * than to a queryable state store, so this is the cheapest way to expose the
 * latest correlations to the dashboard without changing the topology.
 */
public class CorrelatedAlertBuffer {

    private static final Logger logger = LoggerFactory.getLogger(CorrelatedAlertBuffer.class);

    private static final String TOPIC = "correlated-alerts";

    private final String bootstrapServers;
    private final int capacity;
    private final Deque<CorrelatedAlert> buffer = new ConcurrentLinkedDeque<>();

    private volatile KafkaConsumer<String, CorrelatedAlert> consumer;
    private volatile Thread pollThread;
    private volatile boolean running;

    public CorrelatedAlertBuffer(String bootstrapServers, int capacity) {
        this.bootstrapServers = bootstrapServers;
        this.capacity = capacity;
    }

    public void start() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // Unique group per process — every instance keeps its own tail of the topic,
        // independent of any other consumer.
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "correlated-alerts-tail-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        consumer = new KafkaConsumer<>(props,
                new StringDeserializer(),
                new AvroDeserializer<>(CorrelatedAlert.class));
        consumer.subscribe(java.util.List.of(TOPIC));

        running = true;
        pollThread = new Thread(this::pollLoop, "correlated-alerts-tail");
        pollThread.setDaemon(true);
        pollThread.start();
        logger.info("CorrelatedAlertBuffer started — tailing topic '{}' (capacity={})", TOPIC, capacity);
    }

    public void stop() {
        running = false;
        if (consumer != null) {
            consumer.wakeup();
        }
        if (pollThread != null) {
            try {
                pollThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Returns a newest-first snapshot of the buffer (safe to iterate). */
    public List<CorrelatedAlert> snapshot() {
        return new ArrayList<>(buffer);
    }

    /** Clears all buffered alerts. Used by the demo reset endpoint. */
    public void clear() {
        buffer.clear();
        logger.info("CorrelatedAlertBuffer cleared");
    }

    private void pollLoop() {
        try {
            while (running) {
                ConsumerRecords<String, CorrelatedAlert> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, CorrelatedAlert> record : records) {
                    if (record.value() != null) {
                        buffer.addFirst(record.value());
                        while (buffer.size() > capacity) {
                            buffer.pollLast();
                        }
                    }
                }
            }
        } catch (WakeupException expected) {
            // shutdown signal — exit cleanly
        } catch (Exception e) {
            logger.warn("CorrelatedAlertBuffer poll loop exited unexpectedly: {}", e.getMessage(), e);
        } finally {
            try {
                consumer.close();
            } catch (Exception ignored) {}
            logger.info("CorrelatedAlertBuffer stopped");
        }
    }
}
