package ch.unisg.worldpulse.streams;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tails the "signup-counts-final" topic — the suppressed output of C4b.
 *
 * Records arrive only when a window closes, so each record is a final count.
 * Key format: "TIER|windowStartMs|windowEndMs", value: count (Long).
 * Maintains a map of (tier, windowStart) → {tier, windowStart, windowEnd, count}.
 */
public class SuppressedSignupBuffer {

    private static final Logger logger = LoggerFactory.getLogger(SuppressedSignupBuffer.class);

    private static final String TOPIC = "signup-counts-final";

    private final String bootstrapServers;
    private final Map<String, List<WindowedCount>> byTier = new ConcurrentHashMap<>();

    private volatile KafkaConsumer<String, Long> consumer;
    private volatile Thread pollThread;
    private volatile boolean running;

    public SuppressedSignupBuffer(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public void start() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "signup-counts-tail-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        consumer = new KafkaConsumer<>(props, new StringDeserializer(), new LongDeserializer());
        consumer.subscribe(List.of(TOPIC));

        running = true;
        pollThread = new Thread(this::pollLoop, "signup-counts-tail");
        pollThread.setDaemon(true);
        pollThread.start();
        logger.info("SuppressedSignupBuffer started — tailing topic '{}'", TOPIC);
    }

    public void stop() {
        running = false;
        if (consumer != null) consumer.wakeup();
        if (pollThread != null) {
            try { pollThread.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    public void clear() {
        byTier.clear();
        logger.info("SuppressedSignupBuffer cleared");
    }

    public List<WindowedCount> getByTier(String tier) {
        return byTier.getOrDefault(tier.toUpperCase(), List.of());
    }

    public List<WindowedCount> getAll() {
        List<WindowedCount> all = new ArrayList<>();
        byTier.values().forEach(all::addAll);
        return all;
    }

    private void pollLoop() {
        try {
            while (running) {
                ConsumerRecords<String, Long> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, Long> record : records) {
                    if (record.key() == null || record.value() == null) continue;
                    String[] parts = record.key().split("\\|");
                    if (parts.length != 3) continue;

                    String tier = parts[0];
                    long windowStart = Long.parseLong(parts[1]);
                    long windowEnd = Long.parseLong(parts[2]);
                    long count = record.value();

                    WindowedCount wc = new WindowedCount(tier, windowStart, windowEnd, count);
                    byTier.compute(tier, (k, list) -> {
                        if (list == null) list = new ArrayList<>();
                        list.removeIf(existing -> existing.windowStart == windowStart);
                        list.add(0, wc);
                        while (list.size() > 20) list.remove(list.size() - 1);
                        return list;
                    });

                    logger.debug("Suppressed signup-count: tier={} window={}-{} count={}",
                            tier, windowStart, windowEnd, count);
                }
            }
        } catch (WakeupException expected) {
        } catch (Exception e) {
            logger.warn("SuppressedSignupBuffer poll loop exited: {}", e.getMessage(), e);
        } finally {
            try { consumer.close(); } catch (Exception ignored) {}
            logger.info("SuppressedSignupBuffer stopped");
        }
    }

    public static class WindowedCount {
        public final String tier;
        public final long windowStart;
        public final long windowEnd;
        public final long count;

        public WindowedCount(String tier, long windowStart, long windowEnd, long count) {
            this.tier = tier;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.count = count;
        }
    }
}
