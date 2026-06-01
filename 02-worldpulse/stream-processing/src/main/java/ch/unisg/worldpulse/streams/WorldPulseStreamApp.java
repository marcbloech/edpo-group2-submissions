package ch.unisg.worldpulse.streams;

import ch.unisg.worldpulse.streams.avro.AssetCategory;
import ch.unisg.worldpulse.streams.avro.SymbolMetadata;
import ch.unisg.worldpulse.streams.serialization.avro.AvroSerializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the WorldPulse Kafka Streams application.
 *
 * Startup sequence (order matters):
 *   1. ensureTopicsExist() — creates all required topics via AdminClient if they
 *      don't exist yet. This must run before any KafkaStreams instance starts,
 *      because Kafka Streams throws MissingSourceTopicException on startup when
 *      a source topic is absent. Topics are idempotent (TopicExistsException ignored).
 *   2. Start enrichment streams  (App A — worldpulse-enrichment)
 *   3. Start analytics streams   (App B — worldpulse-analytics)
 *   4. Start QueryService REST server on port 8097
 *
 * Two independent KafkaStreams instances with separate application IDs keep their
 * state stores, consumer groups, and changelog topics fully isolated.
 *
 * Ref: Implementation Plan §10 Phase 1 (step 5), §11 (Docker Compose env vars)
 */
public class WorldPulseStreamApp {

    private static final Logger logger = LoggerFactory.getLogger(WorldPulseStreamApp.class);

    // Replication factor for all stream-processing topics.
    // Must be <= number of available brokers (3 in docker-compose).
    private static final short REPLICATION_FACTOR = 3;
    private static final int PARTITIONS = 1;

    public static void main(String[] args) throws Exception {
        String bootstrapServers = System.getenv().getOrDefault(
                "KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");
        String applicationServer = System.getenv().getOrDefault(
                "APPLICATION_SERVER", "localhost:8097");
        int queryPort = parsePort(applicationServer);

        logger.info("Starting WorldPulse Stream Processing");
        logger.info("Bootstrap servers : {}", bootstrapServers);
        logger.info("Application server: {}", applicationServer);

        // ── Step 1: Ensure all required topics exist ──────────────────────────────
        // This runs before any KafkaStreams instance is created.
        // Without this, App B crashes on startup if alerts-enriched, signups-normalized,
        // alerts-with-metadata, or symbol-metadata don't exist yet (they are outputs
        // of App A / App B itself but sources for downstream processors).
        ensureTopicsExist(bootstrapServers);

        // ── Step 1b: Seed symbol-metadata topic with reference data ──────────────
        // Must run after topic creation and before KafkaStreams starts, so the
        // GlobalKTable finds data already present when it begins reading.
        seedSymbolMetadata(bootstrapServers);

        // ── Step 2: App A — Stateless enrichment pipeline (E6) ───────────────────
        Properties enrichmentProps = baseProps(bootstrapServers, applicationServer);
        enrichmentProps.put(StreamsConfig.APPLICATION_ID_CONFIG, "worldpulse-enrichment");

        KafkaStreams enrichmentStreams = new KafkaStreams(
                EventEnrichmentTopology.build(), enrichmentProps);

        enrichmentStreams.setUncaughtExceptionHandler((thread, throwable) -> {
            logger.error("Uncaught exception in enrichment topology [{}]: {}",
                    thread.getName(), throwable.getMessage(), throwable);
        });

        // ── Step 3: App B — Stateful analytics dashboard (E7) ────────────────────
        // The state-store cache is disabled (== 0) ONLY on the analytics instance.
        // Why: the signup-counts pipeline uses `.suppress(untilWindowCloses(...))`,
        // and a non-zero cache prevents window-close detection in low-volume scenarios,
        // so suppress either emits intermediate results or never emits the final one.
        // The enrichment topology has no windows/suppress, so it keeps the default cache.
        Properties analyticsProps = baseProps(bootstrapServers, applicationServer);
        analyticsProps.put(StreamsConfig.APPLICATION_ID_CONFIG, "worldpulse-analytics");
        analyticsProps.put(StreamsConfig.STATESTORE_CACHE_MAX_BYTES_CONFIG, 0);

        KafkaStreams analyticsStreams = new KafkaStreams(
                AnalyticsDashboardTopology.build(), analyticsProps);

        analyticsStreams.setUncaughtExceptionHandler((thread, throwable) -> {
            logger.error("Uncaught exception in analytics topology [{}]: {}",
                    thread.getName(), throwable.getMessage(), throwable);
        });

        // ── Step 3b: Correlated-alerts tail buffer ────────────────────────────────
        // Tails the C6 output topic in a background consumer so the REST API can
        // surface recent KStream-KStream join results to the dashboard.
        CorrelatedAlertBuffer correlatedAlertBuffer = new CorrelatedAlertBuffer(bootstrapServers, 50);
        correlatedAlertBuffer.start();

        // ── Step 3c: Suppressed output tail buffers ──────────────────────────────
        // Tail the suppressed output topics so the dashboard only sees final
        // window results (interactive queries on the state store bypass suppress).
        SuppressedSignupBuffer signupBuffer = new SuppressedSignupBuffer(bootstrapServers);
        signupBuffer.start();

        SuppressedSummaryBuffer summaryBuffer = new SuppressedSummaryBuffer(bootstrapServers, 50);
        summaryBuffer.start();

        // ── Step 4: Interactive query REST server (D1-D5) ─────────────────────────
        QueryService queryService = new QueryService(analyticsStreams, correlatedAlertBuffer,
                signupBuffer, summaryBuffer, queryPort);
        queryService.start();

        // ── Graceful shutdown ──────────────────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown signal received — stopping WorldPulse Stream Processing");
            queryService.stop();
            correlatedAlertBuffer.stop();
            signupBuffer.stop();
            summaryBuffer.stop();
            enrichmentStreams.close();
            analyticsStreams.close();
            logger.info("WorldPulse Stream Processing stopped");
        }, "streams-shutdown-hook"));

        // ── Start both topologies ──────────────────────────────────────────────────
        enrichmentStreams.start();
        analyticsStreams.start();

        logger.info("Both topologies started — interactive queries available on port {}", queryPort);
    }

    /**
     * Pre-creates all topics required by both topologies before streams start.
     *
     * Why this is necessary: Kafka Streams throws MissingSourceTopicException on
     * rebalance if a source topic doesn't exist. Topics that are outputs of one
     * topology but inputs of another (e.g. alerts-enriched, signups-normalized,
     * alerts-with-metadata, symbol-metadata) must exist before either topology
     * subscribes to them.
     *
     * All creates are idempotent — TopicExistsException is silently ignored.
     */
    private static void ensureTopicsExist(String bootstrapServers) {
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);

        try (AdminClient admin = AdminClient.create(adminProps)) {
            List<NewTopic> topics = List.of(
                // ── worldpulse topic (main input) ──────────────────────────────
                // Created by the process service on startup, but we ensure it here
                // in case stream-processing starts before process.
                new NewTopic("worldpulse", PARTITIONS, REPLICATION_FACTOR),

                // ── App A output topics ────────────────────────────────────────
                // Also used as source topics by App B — must exist before App B starts.
                new NewTopic("alerts-enriched", PARTITIONS, REPLICATION_FACTOR),
                new NewTopic("signups-normalized", PARTITIONS, REPLICATION_FACTOR),

                // ── App B output/source topics ────────────────────────────────
                // symbol-metadata: compacted reference data topic for GlobalKTable.
                // cleanup.policy=compact keeps only the latest value per key.
                new NewTopic("symbol-metadata", PARTITIONS, REPLICATION_FACTOR)
                        .configs(Map.of("cleanup.policy", "compact")),
                new NewTopic("alerts-with-metadata", PARTITIONS, REPLICATION_FACTOR),
                new NewTopic("correlated-alerts", PARTITIONS, REPLICATION_FACTOR),

                // Suppressed output topics — consumed by tail buffers for the dashboard
                new NewTopic("signup-counts-final", PARTITIONS, REPLICATION_FACTOR),
                new NewTopic("correlated-alerts-summary-final", PARTITIONS, REPLICATION_FACTOR),

                // worldpulse-dead-letter: created by process service, ensured here too.
                new NewTopic("worldpulse-dead-letter", PARTITIONS, REPLICATION_FACTOR)
            );

            topics.forEach(topic -> {
                admin.createTopics(List.of(topic)).values().get(topic.name())
                        .whenComplete((result, ex) -> {
                            if (ex == null) {
                                logger.info("Created topic: {}", topic.name());
                            } else if (ex.getCause() instanceof TopicExistsException) {
                                logger.debug("Topic already exists (ok): {}", topic.name());
                            } else {
                                logger.warn("Could not create topic {}: {}", topic.name(),
                                        ex.getMessage());
                            }
                        });
            });

            // Give the broker a moment to register all topics before streams start
            Thread.sleep(2000);
            logger.info("Topic initialization complete");

        } catch (Exception e) {
            logger.warn("Topic pre-creation failed (will proceed anyway): {}", e.getMessage());
        }
    }

    /**
     * Reads symbol-metadata.json from the classpath and publishes each entry
     * to the "symbol-metadata" compacted topic via a plain KafkaProducer.
     *
     * (Uses the same AvroSerializer as the Kafka Streams topologies, so the
     * GlobalKTable's AvroDeserializer can read the records directly.)
     */
    private static void seedSymbolMetadata(String bootstrapServers) {
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, AvroSerializer.class.getName());

        try (InputStream is = WorldPulseStreamApp.class.getClassLoader()
                .getResourceAsStream("symbol-metadata.json");
             KafkaProducer<String, SymbolMetadata> producer = new KafkaProducer<>(producerProps)) {

            // quick sanity check
            if (is == null) {
                logger.warn("symbol-metadata.json not found on classpath — skipping seed");
                return;
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode entries = mapper.readTree(is);

            // Per-entry try/catch so a single malformed line (e.g. unknown category
            // enum value, missing field) doesn't abort the rest of the seeding.
            int count = 0;
            int skipped = 0;
            for (JsonNode entry : entries) {
                try {
                    SymbolMetadata metadata = new SymbolMetadata();
                    metadata.setSymbol(entry.get("symbol").asText());
                    metadata.setDisplayName(entry.get("displayName").asText());
                    metadata.setSector(entry.get("sector").asText());
                    metadata.setCategory(AssetCategory.valueOf(entry.get("category").asText()));

                    // send each json entry as a separate record to the symbol-metadata topic, using the symbol as the key
                    producer.send(new ProducerRecord<>(
                            "symbol-metadata", metadata.getSymbol(), metadata));
                    count++;
                } catch (Exception e) {
                    skipped++;
                    logger.warn("Skipping invalid symbol-metadata entry {}: {}", entry, e.getMessage());
                }
            }

            producer.flush();
            logger.info("Seeded {} entries to symbol-metadata topic ({} skipped)", count, skipped);

        } catch (Exception e) {
            logger.warn("Failed to seed symbol-metadata (will proceed anyway): {}", e.getMessage());
        }
    }

    private static Properties baseProps(String bootstrapServers, String applicationServer) {
        Properties props = new Properties();
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(StreamsConfig.APPLICATION_SERVER_CONFIG, applicationServer);
        props.put(StreamsConfig.DEFAULT_TIMESTAMP_EXTRACTOR_CLASS_CONFIG,
                WorldPulseTimestampExtractor.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return props;
    }

    private static int parsePort(String applicationServer) {
        try {
            String[] parts = applicationServer.split(":");
            return Integer.parseInt(parts[parts.length - 1]);
        } catch (Exception e) {
            return 8097;
        }
    }
}
