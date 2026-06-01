package ch.unisg.worldpulse.streams;

import ch.unisg.worldpulse.streams.avro.CorrelatedAlertSummary;
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
 * Tails the "correlated-alerts-summary-final" topic — the suppressed output of C7.
 *
 * Records arrive only when a window closes, so each record is a final summary.
 * The CorrelatedAlertSummary Avro record already contains windowStart/windowEnd.
 */
public class SuppressedSummaryBuffer {

    private static final Logger logger = LoggerFactory.getLogger(SuppressedSummaryBuffer.class);

    private static final String TOPIC = "correlated-alerts-summary-final";

    private final String bootstrapServers;
    private final int capacity;
    private final Deque<CorrelatedAlertSummary> buffer = new ConcurrentLinkedDeque<>();

    private volatile KafkaConsumer<String, CorrelatedAlertSummary> consumer;
    private volatile Thread pollThread;
    private volatile boolean running;

    public SuppressedSummaryBuffer(String bootstrapServers, int capacity) {
        this.bootstrapServers = bootstrapServers;
        this.capacity = capacity;
    }

    public void start() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "correlated-summary-tail-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        consumer = new KafkaConsumer<>(props,
                new StringDeserializer(),
                new AvroDeserializer<>(CorrelatedAlertSummary.class));
        consumer.subscribe(List.of(TOPIC));

        running = true;
        pollThread = new Thread(this::pollLoop, "correlated-summary-tail");
        pollThread.setDaemon(true);
        pollThread.start();
        logger.info("SuppressedSummaryBuffer started — tailing topic '{}'", TOPIC);
    }

    public void stop() {
        running = false;
        if (consumer != null) consumer.wakeup();
        if (pollThread != null) {
            try { pollThread.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    public void clear() {
        buffer.clear();
        logger.info("SuppressedSummaryBuffer cleared");
    }

    public List<CorrelatedAlertSummary> snapshot() {
        return new ArrayList<>(buffer);
    }

    private void pollLoop() {
        try {
            while (running) {
                ConsumerRecords<String, CorrelatedAlertSummary> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, CorrelatedAlertSummary> record : records) {
                    if (record.value() != null) {
                        buffer.addFirst(record.value());
                        while (buffer.size() > capacity) buffer.pollLast();
                        logger.debug("Suppressed summary: sector={} matches={}",
                                record.value().getSector(), record.value().getMatchCount());
                    }
                }
            }
        } catch (WakeupException expected) {
        } catch (Exception e) {
            logger.warn("SuppressedSummaryBuffer poll loop exited: {}", e.getMessage(), e);
        } finally {
            try { consumer.close(); } catch (Exception ignored) {}
            logger.info("SuppressedSummaryBuffer stopped");
        }
    }
}
