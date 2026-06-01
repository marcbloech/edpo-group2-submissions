package ch.unisg.worldpulse.streams;

import ch.unisg.worldpulse.streams.avro.AlertStats;
import ch.unisg.worldpulse.streams.avro.CorrelatedAlert;
import ch.unisg.worldpulse.streams.avro.CorrelatedAlertSummary;
import ch.unisg.worldpulse.streams.avro.EnrichedAlert;
import ch.unisg.worldpulse.streams.avro.NormalizedSignup;
import ch.unisg.worldpulse.streams.avro.PaymentSummary;
import ch.unisg.worldpulse.streams.avro.SymbolMetadata;
import ch.unisg.worldpulse.streams.avro.UserActivity;
import ch.unisg.worldpulse.streams.avro.UserDashboard;
import ch.unisg.worldpulse.streams.serialization.avro.AvroSerdes;
import ch.unisg.worldpulse.streams.serialization.json.JsonSerdes;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Branched;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.GlobalKTable;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.kstream.JoinWindows;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.StreamJoined;
import org.apache.kafka.streams.kstream.Suppressed;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.state.Stores;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * App B — Stateful Analytics Dashboard Topology (E7: Stateful Stream Processing).
 *
 * Reads raw CloudEvents JSON from the "worldpulse" topic, filters for user lifecycle
 * and payment events, branches them, rekeys by userId, and aggregates into two KTables:
 *   - "user-activity"   : latest user state per userId (tier, event count, last action)
 *   - "payment-history" : payment summary per userId (success/fail counts)
 *
 */
public class AnalyticsDashboardTopology {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsDashboardTopology.class);

    // Define event types of interest for filtering and branching (based on "type" field in CloudEvents)
    // Event types that represent user lifecycle changes (signup, upgrade, deactivation)
    private static final Set<String> USER_LIFECYCLE_EVENTS = Set.of(
            "SignupRequestedEvent", "UpgradeRequestedEvent", "AccountDeactivationRequestedEvent");
    // Event types that represent payment outcomes
    private static final Set<String> PAYMENT_EVENTS = Set.of(
            "PaymentReceivedEvent", "PaymentFailedEvent");

    /**
     * Builds the stateful analytics topology.
     *
     * Data flow:
     *   worldpulse → filter (keep only user+payment events)
     *             → split/branch (route to user vs payment sub-streams)
     *             → selectKey by userId (triggers internal repartition topic)
     *             → groupByKey → aggregate into in-memory KTables
     */
    public static Topology build() {
        StreamsBuilder builder = new StreamsBuilder();

        // Source: read all raw CloudEvents from worldpulse topic as JSON
        KStream<String, JsonNode> worldpulse = builder.stream(
                "worldpulse",
                Consumed.with(Serdes.String(), JsonSerdes.JsonNode()));

        // Filter: drop irrelevant events (market alerts, social trends, BPMN internals)
        // Only keep the 5 event types we need for user/payment aggregation (defined in the static sets above)
        KStream<String, JsonNode> relevantEvents = worldpulse.filter(
                (key, value) -> {
                    String type = extractEventType(value);
                    return USER_LIFECYCLE_EVENTS.contains(type) || PAYMENT_EVENTS.contains(type);
                });

        // Branch: route events to user-lifecycle vs payment sub-streams
        // Uses the non-deprecated split() API (Kafka Streams 2.8+) which returns a named Map
        // Keys are "prefix + branchName", e.g. "event-user", "event-payment"
        Map<String, KStream<String, JsonNode>> branches = relevantEvents
                .split(Named.as("event-"))
                .branch((key, value) -> USER_LIFECYCLE_EVENTS.contains(extractEventType(value)),
                        Branched.as("user")) // Branch 1: user lifecycle events
                .branch((key, value) -> PAYMENT_EVENTS.contains(extractEventType(value)),
                        Branched.as("payment")) // Branch 2: payment events
                .defaultBranch(Branched.as("other"));

        KStream<String, JsonNode> userEvents = branches.get("event-user");
        KStream<String, JsonNode> paymentEvents = branches.get("event-payment");

        // C1a: User Activity KTable
        // Aggregates user lifecycle events into a running summary per userId
        // selectKey triggers a repartition
        // The aggregate maintains: current tier, event count, last event type/timestamp
        // Filter null-userId events explicitly so they are logged rather than silently
        // dropped by groupByKey (which drops null keys without notice).
        KTable<String, UserActivity> userActivityTable = userEvents
                .filter((k, v) -> hasUserId(v, "user lifecycle"))
                // step 1: selectKey: extract userId from the CloudEvents envelope
                .selectKey((key, value) -> {
                    String userId = extractUserId(value);
                    return userId == null ? null : userId.trim();
                }) // rekey by normalized userId
                // Guard against regressions in helper methods: do not allow null/blank/"null"
                // keys to reach groupByKey where null keys would otherwise be dropped silently.
                .filter((userId, value) -> userId != null
                        && !userId.isBlank()
                        && !"null".equalsIgnoreCase(userId))
                // step 2: groupByKey: group events by userId for aggregation
                .groupByKey(org.apache.kafka.streams.kstream.Grouped.with(
                        Serdes.String(), JsonSerdes.JsonNode()))
                // step 3: aggregate: take an initializer (blank UserActivity record) and an adder (updateUserActivity) to maintain the aggregate state per userId
                // --> result: KTable
                .aggregate(
                        AnalyticsDashboardTopology::initUserActivity, // initializer   
                        (userId, event, current) -> updateUserActivity(userId, event, current), // adder   
                        Materialized.<String, UserActivity>as( // Materialized = can be queried via Interactive Queries; backed by an in-memory state store named "user-activity"
                                Stores.inMemoryKeyValueStore("user-activity"))
                                .withKeySerde(Serdes.String())
                                .withValueSerde(AvroSerdes.UserActivity()));

        // C1b: Payment History KTable
        // Aggregates payment events into a running summary per userId.
        // Tracks: total payments, successful count, failed count, last status.
        //
        // Note: the rekey here trims whitespace and drops null/blank/"null"
        // values, exactly like the user-lifecycle branch above. Without the
        // trim, a payment with userId "  alice " would never join the signup
        // for "alice" in the KTable–KTable join (C2).
        KTable<String, PaymentSummary> paymentHistoryTable = paymentEvents
                .filter((k, v) -> hasUserId(v, "payment"))
                .selectKey((key, value) -> {
                    String userId = extractUserId(value);
                    return userId == null ? null : userId.trim();
                })
                .filter((userId, value) -> userId != null
                        && !userId.isBlank()
                        && !"null".equalsIgnoreCase(userId))
                .groupByKey(org.apache.kafka.streams.kstream.Grouped.with(
                        Serdes.String(), JsonSerdes.JsonNode()))
                .aggregate(
                        AnalyticsDashboardTopology::initPaymentSummary, // initializer
                        (userId, event, current) -> updatePaymentSummary(userId, event, current), // adder
                        Materialized.<String, PaymentSummary>as( // also materialized for Interactive Queries; separate state store named "payment-history"
                                Stores.inMemoryKeyValueStore("payment-history"))
                                .withKeySerde(Serdes.String())
                                .withValueSerde(AvroSerdes.PaymentSummary()));

        // C2: KTable-KTable Left Join (UserActivity x PaymentSummary → UserDashboard)
        // Both tables are keyed by userId — no repartitioning needed.
        // leftJoin: every user with a signup appears even if they have no payments yet
        // (inner join would hide free-tier users who never paid)
        KTable<String, UserDashboard> userDashboardTable = userActivityTable.leftJoin(
                paymentHistoryTable,
                AnalyticsDashboardTopology::joinUserDashboard,
                Materialized.<String, UserDashboard>as(
                        Stores.inMemoryKeyValueStore("user-dashboard"))
                        .withKeySerde(Serdes.String())
                        .withValueSerde(AvroSerdes.UserDashboard()));

        // --- C4a: Alert Stats — windowed aggregation (1-minute tumbling window) ---
        // Reads enriched alerts (produced by App A), groups by symbol/topic,
        // and aggregates into 1-minute tumbling windows with 30s grace period.
        // No suppress() — we emit intermediate results for live interactive queries.
        KStream<String, EnrichedAlert> alertsStream = builder.stream(
                "alerts-enriched",
                Consumed.with(Serdes.String(), AvroSerdes.EnrichedAlert()));

        // alerts-enriched is already keyed by symbol (market) or topic (social) in
        // EventEnrichmentTopology, so we can groupByKey directly — no selectKey, no
        // unnecessary repartition topic.
        alertsStream
                .groupByKey(org.apache.kafka.streams.kstream.Grouped.with(
                        Serdes.String(), AvroSerdes.EnrichedAlert()))
                .windowedBy(TimeWindows.ofSizeAndGrace(
                        Duration.ofMinutes(1), Duration.ofSeconds(30)))
                .aggregate(
                        AnalyticsDashboardTopology::initAlertStats,
                        (key, alert, current) -> updateAlertStats(key, alert, current),
                        Materialized.<String, AlertStats>as(
                                Stores.inMemoryWindowStore(
                                        "alert-stats",
                                        Duration.ofMinutes(30),  // retention period
                                        Duration.ofMinutes(1),   // window size
                                        false))                  // no duplicates
                                .withKeySerde(Serdes.String())
                                .withValueSerde(AvroSerdes.AlertStats()));

        // --- C4b: Signup Counts — windowed aggregation (5-minute tumbling window) ---
        // Reads signups-normalized (produced by App A), groups by tier, counts per 5-minute window.
        KStream<String, NormalizedSignup> signupsStream = builder.stream(
                "signups-normalized",
                Consumed.with(Serdes.String(), AvroSerdes.NormalizedSignup()));

        signupsStream
                .groupBy((key, signup) -> signup.getTier().name(),
                        org.apache.kafka.streams.kstream.Grouped.with(
                                Serdes.String(), AvroSerdes.NormalizedSignup()))
                .windowedBy(TimeWindows.ofSizeAndGrace(
                        Duration.ofMinutes(1), Duration.ofSeconds(30)))
                .count(Materialized.<String, Long>as(
                        Stores.inMemoryWindowStore(
                                "signup-counts",
                                Duration.ofMinutes(30),  // retention period
                                Duration.ofMinutes(1),   // window size (1min for demo; production: 5-15min)
                                false))                  // no duplicates
                        .withKeySerde(Serdes.String())
                        .withValueSerde(Serdes.Long()))
                // Bounded suppress buffer: caps memory at 16 MiB so a burst
                // of unique tier keys can't grow the heap without limit. We
                // keep shutDownWhenFull (the only option allowed by
                // untilWindowCloses — emitting early would violate "wait
                // for window close" semantics), but the bound makes the
                // failure mode predictable instead of letting the JVM run
                // until OOM. The previous unbounded().shutDownWhenFull() was
                // self-contradictory: an unbounded buffer never fills, so
                // shutDownWhenFull was unreachable.
                .suppress(Suppressed.untilWindowCloses(
                        Suppressed.BufferConfig.maxBytes(16L * 1024 * 1024).shutDownWhenFull()))
                .toStream()
                .selectKey((Windowed<String> windowedKey, Long count) ->
                        windowedKey.key() + "|" + windowedKey.window().start() + "|" + windowedKey.window().end())
                .to("signup-counts-final",
                        Produced.with(Serdes.String(), Serdes.Long()));

        // --- C4c: Login Stats — windowed aggregation (30s tumbling window for demo) ---
        // Reads LoginEvent from worldpulse, groups by location, counts per window.
        // In production this would be ~1 hour; we use 30s so windows turn over
        // fast enough to see results during a live demo.
        // NO suppress — emits intermediate results for real-time geographic monitoring.
        // Design contrast with signup-counts (suppress): see report for trade-off discussion.
        //
        // The location is normalised to lower-case here so producers emitting
        // "Zurich" / "zurich" / "ZURICH" land in the same bucket, and so the
        // REST endpoint (QueryService#handleLoginStats) can perform a
        // case-insensitive lookup by lower-casing the path parameter too.
        worldpulse
                .filter((key, value) -> "LoginEvent".equals(extractEventType(value)))
                .selectKey((key, value) -> normalizeLocation(extractLocation(value)))
                .groupByKey(org.apache.kafka.streams.kstream.Grouped.with(
                        Serdes.String(), JsonSerdes.JsonNode()))
                .windowedBy(TimeWindows.ofSizeAndGrace(
                        Duration.ofSeconds(30), Duration.ofSeconds(5)))
                .count(Materialized.<String, Long>as(
                        Stores.inMemoryWindowStore(
                                "login-stats",
                                Duration.ofMinutes(30),  // retention period
                                Duration.ofSeconds(30),  // window size
                                false))                  // no duplicates
                        .withKeySerde(Serdes.String())
                        .withValueSerde(Serdes.Long()));

        // --- C3: Symbol Metadata GlobalKTable (reference data lookup) ---
        // Reads from the compacted "symbol-metadata" topic, seeded on startup by
        // WorldPulseStreamApp.seedSymbolMetadata(). Fully replicated on every
        // instance; no co-partitioning needed for downstream joins.
        GlobalKTable<String, SymbolMetadata> symbolMetadataTable = builder.globalTable(
                "symbol-metadata",
                Consumed.with(Serdes.String(), AvroSerdes.SymbolMetadata()));

        // --- C5: KStream-GlobalKTable leftJoin (alert metadata enrichment) ---
        // Enriches each alert with displayName, sector, category from reference data.
        // leftJoin: unknown symbols pass through with null metadata fields.
        // KeyValueMapper: (key, alert) -> key — alerts are already keyed by symbol/topic,
        // Pattern reference: Leaderboard lab withPlayers.join(products, keyMapper, productJoiner)
        alertsStream
                .leftJoin(symbolMetadataTable,
                        (key, alert) -> key,
                        AnalyticsDashboardTopology::enrichAlertWithMetadata)
                .to("alerts-with-metadata",
                        Produced.with(Serdes.String(), AvroSerdes.EnrichedAlert()));

        // --- C6: KStream-KStream Windowed Join — Correlated Alerts ---
        // Detects when market AND social alerts occur in the same sector within 5 minutes.
        // Flow: alerts-with-metadata → filter nulls → split market/social → rekey by sector → join
        KStream<String, EnrichedAlert> alertsWithMetadata = builder.stream(
                "alerts-with-metadata",
                Consumed.with(Serdes.String(), AvroSerdes.EnrichedAlert()));

        // Filter out alerts with null sector (unknown symbols from C5 leftJoin)
        KStream<String, EnrichedAlert> alertsWithSector = alertsWithMetadata
                .filter((key, alert) -> alert.getSector() != null);

        // Split into market and social sub-streams, rekey both by sector
        KStream<String, EnrichedAlert> marketAlerts = alertsWithSector
                .filter((key, alert) -> "WorldPulse-MarketScanner".equals(alert.getSource()))
                .selectKey((key, alert) -> alert.getSector());

        KStream<String, EnrichedAlert> socialAlerts = alertsWithSector
                .filter((key, alert) -> "WorldPulse-BlueSkyScanner".equals(alert.getSource()))
                .selectKey((key, alert) -> alert.getSector());

        // Join: market + social alerts in the same sector within 5 minutes
        JoinWindows joinWindows = JoinWindows.ofTimeDifferenceAndGrace(
                Duration.ofMinutes(5), Duration.ofSeconds(30));

        // Force in-memory join stores so the whole analytics topology stays in-memory
        // (matching the rest of the state stores).
        //
        // For a stream-stream join with JoinWindows.ofTimeDifferenceAndGrace(5min, 30s),
        // Kafka Streams requires EXACT match against JoinWindows for each side's
        // buffer store:
        //   windowSize    = beforeMs + afterMs        = 10 min
        //   retention     = windowSize + gracePeriod  = 10 min 30 s
        //   retainDuplicates = true                   (REQUIRED for stream-stream join)
        Duration joinWindowSize = Duration.ofMinutes(10);
        Duration joinStoreRetention = joinWindowSize.plus(Duration.ofSeconds(30));
        StreamJoined<String, EnrichedAlert, EnrichedAlert> joinParams = StreamJoined
                .<String, EnrichedAlert, EnrichedAlert>with(
                        Serdes.String(), AvroSerdes.EnrichedAlert(), AvroSerdes.EnrichedAlert())
                .withName("correlated-alerts-join")
                .withThisStoreSupplier(Stores.inMemoryWindowStore(
                        "correlated-alerts-join-this",
                        joinStoreRetention, joinWindowSize, true))
                .withOtherStoreSupplier(Stores.inMemoryWindowStore(
                        "correlated-alerts-join-other",
                        joinStoreRetention, joinWindowSize, true));

        marketAlerts
                .join(socialAlerts,
                        AnalyticsDashboardTopology::createCorrelatedAlert,
                        joinWindows,
                        joinParams)
                .to("correlated-alerts",
                        Produced.with(Serdes.String(), AvroSerdes.CorrelatedAlert()));

        // --- C7: Correlated Alert Summary — windowed aggregation with suppress ---
        // Collapses the cartesian join output from C6 into one summary per sector
        // per 5-minute window: distinct market symbols, distinct social topics,
        // worst risk level, and total match count. Uses suppress to emit only the
        // final result when the window closes — same Suppressed Event Aggregator
        // pattern as signup-counts (C4b).
        KStream<String, CorrelatedAlert> correlatedStream = builder.stream(
                "correlated-alerts",
                Consumed.with(Serdes.String(), AvroSerdes.CorrelatedAlert()));

        Duration summaryWindowSize = Duration.ofMinutes(1);  // 1min for demo; production: 5min
        Duration summaryGrace = Duration.ofSeconds(30);
        Duration summaryRetention = Duration.ofMinutes(30);

        correlatedStream
                .groupByKey(org.apache.kafka.streams.kstream.Grouped.with(
                        Serdes.String(), AvroSerdes.CorrelatedAlert()))
                .windowedBy(TimeWindows.ofSizeAndGrace(summaryWindowSize, summaryGrace))
                .aggregate(
                        CorrelatedAlertSummary::new,
                        AnalyticsDashboardTopology::addToCorrelatedSummary,
                        Materialized.<String, CorrelatedAlertSummary>as(
                                Stores.inMemoryWindowStore(
                                        "correlated-alerts-summary",
                                        summaryRetention,
                                        summaryWindowSize,
                                        false))
                                .withKeySerde(Serdes.String())
                                .withValueSerde(AvroSerdes.CorrelatedAlertSummary()))
                .suppress(Suppressed.untilWindowCloses(
                        Suppressed.BufferConfig.maxBytes(16L * 1024 * 1024).shutDownWhenFull()))
                .toStream()
                .map((Windowed<String> windowedKey, CorrelatedAlertSummary summary) -> {
                    summary.setWindowStart(Instant.ofEpochMilli(windowedKey.window().start()));
                    summary.setWindowEnd(Instant.ofEpochMilli(windowedKey.window().end()));
                    return new org.apache.kafka.streams.KeyValue<>(windowedKey.key(), summary);
                })
                .to("correlated-alerts-summary-final",
                        Produced.with(Serdes.String(), AvroSerdes.CorrelatedAlertSummary()));

        return builder.build();
    }

    // ==================== Helper: field extraction from CloudEvents envelope ====================

    /** Extracts the "type" field from the CloudEvents envelope (e.g. "SignupRequestedEvent") */
    static String extractEventType(JsonNode envelope) {
        if (envelope == null) return null;
        JsonNode typeNode = envelope.get("type");
        // Guard against NullNode — `asText()` on it returns the literal string "null".
        if (typeNode == null || typeNode.isNull()) return null;
        return typeNode.asText();
    }

    /**
     * Returns true if the event has a non-blank userId. Otherwise logs the drop
     * (so we don't silently lose events at the subsequent groupByKey, which drops
     * null keys without notice).
     */
    static boolean hasUserId(JsonNode envelope, String contextLabel) {
        String userId = extractUserId(envelope);
        if (userId == null || userId.isBlank()) {
            logger.debug("Dropping {} event without userId: type={}",
                    contextLabel, extractEventType(envelope));
            return false;
        }
        return true;
    }

    /** Extracts "data.location" from the CloudEvents envelope — used as key for login-stats */
    static String extractLocation(JsonNode envelope) {
        if (envelope == null) return null;
        JsonNode data = envelope.get("data");
        if (data == null) return null;
        JsonNode location = data.get("location");
        return location != null ? location.asText() : null;
    }

    /**
     * Normalises a location string for use as the login-stats key.
     * Trims surrounding whitespace and lower-cases so producers that send
     * "Zurich", "zurich", and "  ZURICH  " all map to the same bucket.
     */
    static String normalizeLocation(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(java.util.Locale.ROOT);
    }

    /** Extracts "data.userId" from the CloudEvents envelope — used as the new key for repartitioning */
    static String extractUserId(JsonNode envelope) {
        if (envelope == null) return null;
        JsonNode data = envelope.get("data");
        if (data == null) return null;
        JsonNode userId = data.get("userId");
        // Guard against NullNode — `asText()` on it returns the literal string "null".
        if (userId == null || userId.isNull()) return null;
        return userId.asText();
    }

    // ==================== Aggregation: UserActivity (user lifecycle events) ====================

    /** Initializer: creates a blank UserActivity record (called once per new userId) */
    static UserActivity initUserActivity() {
        UserActivity ua = new UserActivity();
        ua.setUserId("");
        ua.setName("");
        ua.setEmail("");
        ua.setCurrentTier("");
        ua.setEventCount(0L);
        ua.setLastEventType("");
        ua.setLastEventTimestamp(Instant.EPOCH);
        return ua;
    }

    /**
     * Adder: updates the UserActivity aggregate with a new lifecycle event.
     * Called for each new event arriving for a given userId.
     * Mutates and returns 'current' — standard Kafka Streams aggregate pattern.
     */
    static UserActivity updateUserActivity(String userId, JsonNode event, UserActivity current) {
        JsonNode data = event.get("data");
        String eventType = extractEventType(event);

        current.setUserId(userId);
        if (data.has("name")) current.setName(data.get("name").asText());
        if (data.has("email")) current.setEmail(data.get("email").asText());

        // UpgradeRequestedEvent carries "targetTier" (the tier being upgraded TO)
        if (data.has("targetTier")) {
            current.setCurrentTier(data.get("targetTier").asText());
        } else if (data.has("tier")) {
            current.setCurrentTier(data.get("tier").asText());
        }

        current.setEventCount(current.getEventCount() + 1);
        current.setLastEventType(eventType);

        // Parse ISO 8601 timestamp from CloudEvents "time" field
        JsonNode timeNode = event.get("time");
        if (timeNode != null) {
            try {
                current.setLastEventTimestamp(Instant.parse(timeNode.asText()));
            } catch (Exception e) {
                current.setLastEventTimestamp(Instant.now());
            }
        }

        return current;
    }

    // ==================== Aggregation: PaymentSummary (payment events) ====================

    /** Initializer: creates a blank PaymentSummary record (called once per new userId) */
    static PaymentSummary initPaymentSummary() {
        PaymentSummary ps = new PaymentSummary();
        ps.setUserId("");
        ps.setTotalPayments(0L);
        ps.setSuccessfulPayments(0L);
        ps.setFailedPayments(0L);
        ps.setLastPaymentStatus("");
        ps.setLastPaymentTimestamp(Instant.EPOCH);
        return ps;
    }

    /**
     * Adder: updates the PaymentSummary aggregate with a new payment event.
     * PaymentReceivedEvent → increment successfulPayments, status = "SUCCESS"
     * PaymentFailedEvent   → increment failedPayments, status = "FAILED"
     */
    static PaymentSummary updatePaymentSummary(String userId, JsonNode event, PaymentSummary current) {
        String eventType = extractEventType(event);

        current.setUserId(userId);
        current.setTotalPayments(current.getTotalPayments() + 1);

        if ("PaymentReceivedEvent".equals(eventType)) {
            current.setSuccessfulPayments(current.getSuccessfulPayments() + 1);
            current.setLastPaymentStatus("SUCCESS");
        } else if ("PaymentFailedEvent".equals(eventType)) {
            current.setFailedPayments(current.getFailedPayments() + 1);
            current.setLastPaymentStatus("FAILED");
        }

        // Parse ISO 8601 timestamp from CloudEvents "time" field
        JsonNode timeNode = event.get("time");
        if (timeNode != null) {
            try {
                current.setLastPaymentTimestamp(Instant.parse(timeNode.asText()));
            } catch (Exception e) {
                current.setLastPaymentTimestamp(Instant.now());
            }
        }

        return current;
    }

    // ==================== C2: KTable-KTable Join Joiner ====================

    /**
     * ValueJoiner: combines UserActivity + PaymentSummary → UserDashboard.
     * Copies fields from both sides into a single denormalized record.
     * payment is null for users with no payment events (leftJoin).
     */
    static UserDashboard joinUserDashboard(UserActivity activity, PaymentSummary payment) {
        UserDashboard dashboard = new UserDashboard();
        dashboard.setUserId(activity.getUserId());
        dashboard.setName(activity.getName());
        dashboard.setEmail(activity.getEmail());
        dashboard.setCurrentTier(activity.getCurrentTier());
        dashboard.setLifecycleEventCount(activity.getEventCount());
        dashboard.setLastLifecycleEvent(activity.getLastEventType());
        if (payment != null) {
            dashboard.setTotalPayments(payment.getTotalPayments());
            dashboard.setSuccessfulPayments(payment.getSuccessfulPayments());
            dashboard.setFailedPayments(payment.getFailedPayments());
            dashboard.setLastPaymentStatus(payment.getLastPaymentStatus());
        } else {
            dashboard.setTotalPayments(0L);
            dashboard.setSuccessfulPayments(0L);
            dashboard.setFailedPayments(0L);
            dashboard.setLastPaymentStatus("");
        }
        return dashboard;
    }

    // ==================== C4a: AlertStats windowed aggregation ====================

    /** Initializer: blank AlertStats for a new window bucket */
    static AlertStats initAlertStats() {
        AlertStats stats = new AlertStats();
        stats.setSymbolOrTopic("");
        stats.setAlertCount(0L);
        stats.setAvgChangePercent(0.0);
        stats.setLastRiskLevel("");
        return stats;
    }

    /**
     * Adder: updates AlertStats within a tumbling window.
     * Maintains a running count, a running average of changePercent, and the last risk level.
     */
    static AlertStats updateAlertStats(String key, EnrichedAlert alert, AlertStats current) {
        current.setSymbolOrTopic(key);
        long newCount = current.getAlertCount() + 1;

        // Running average: ((oldAvg * oldCount) + newValue) / newCount
        Double changePercent = alert.getChangePercent();
        double newValue = changePercent != null ? changePercent : 0.0;
        current.setAvgChangePercent(
                ((current.getAvgChangePercent() * current.getAlertCount()) + newValue) / newCount);

        current.setAlertCount(newCount);
        current.setLastRiskLevel(alert.getRiskLevel().name());
        return current;
    }

    // ==================== C5: KStream-GlobalKTable leftJoin Joiner ====================

    /**
     * ValueJoiner: stamps an EnrichedAlert with metadata from SymbolMetadata.
     * metadata is null when the alert's key has no match in the GlobalKTable (leftJoin).
     *
     * Returns a fresh copy rather than mutating the input — downstream operators,
     * caches, and repartition serializers can otherwise observe the mutation in
     * unpredictable orders.
     */
    static EnrichedAlert enrichAlertWithMetadata(EnrichedAlert alert, SymbolMetadata metadata) {
        EnrichedAlert.Builder builder = EnrichedAlert.newBuilder(alert);
        if (metadata != null) {
            builder.setDisplayName(metadata.getDisplayName());
            builder.setSector(metadata.getSector());
            builder.setCategory(metadata.getCategory() != null ? metadata.getCategory().name() : null);
        }
        return builder.build();
    }

    // ==================== C6: KStream-KStream Windowed Join Joiner ====================

    /**
     * ValueJoiner: combines a market alert + social alert into a CorrelatedAlert.
     * Called when both arrive in the same sector within the 5-minute join window.
     *
     * correlationTimestamp uses event time (the later of the two source events)
     * rather than wall-clock — keeps the whole pipeline event-time consistent.
     */
    static CorrelatedAlert createCorrelatedAlert(EnrichedAlert market, EnrichedAlert social) {
        CorrelatedAlert correlated = new CorrelatedAlert();
        // and set a bunch of fields from both alerts for display on the dashboard
        correlated.setSector(market.getSector());
        correlated.setMarketSymbol(market.getSymbol() != null ? market.getSymbol() : "");
        correlated.setMarketAlertType(market.getAlertType());
        correlated.setMarketRiskLevel(market.getRiskLevel().name());
        correlated.setMarketChangePercent(market.getChangePercent());
        correlated.setSocialTopic(social.getTopic() != null ? social.getTopic() : "");
        correlated.setSocialPostCount(social.getPostCount());
        correlated.setSocialRiskLevel(social.getRiskLevel().name());
        correlated.setCorrelationTimestamp(laterOf(market.getTimestamp(), social.getTimestamp()));
        return correlated;
    }

    /** Returns the later of two Instants, treating nulls as "missing". */
    static Instant laterOf(Instant a, Instant b) {
        if (a == null) return b != null ? b : Instant.EPOCH;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    // ==================== C7: Correlated Alert Summary Aggregator ====================

    private static final Map<String, Integer> RISK_SEVERITY = Map.of(
            "LOW", 0, "MEDIUM", 1, "HIGH", 2, "CRITICAL", 3);

    static CorrelatedAlertSummary addToCorrelatedSummary(
            String sector, CorrelatedAlert alert, CorrelatedAlertSummary summary) {

        summary.setSector(sector);
        summary.setMatchCount(summary.getMatchCount() + 1);

        List<String> symbols = summary.getMarketSymbols() != null
                ? new ArrayList<>(summary.getMarketSymbols()) : new ArrayList<>();
        String sym = alert.getMarketSymbol();
        if (sym != null && !sym.isEmpty() && !symbols.contains(sym)) {
            symbols.add(sym);
        }
        summary.setMarketSymbols(symbols);

        List<String> topics = summary.getSocialTopics() != null
                ? new ArrayList<>(summary.getSocialTopics()) : new ArrayList<>();
        String topic = alert.getSocialTopic();
        if (topic != null && !topic.isEmpty() && !topics.contains(topic)) {
            topics.add(topic);
        }
        summary.setSocialTopics(topics);

        Double change = alert.getMarketChangePercent();
        if (change != null) {
            Double current = summary.getMaxAbsMarketChange();
            if (current == null || Math.abs(change) > Math.abs(current)) {
                summary.setMaxAbsMarketChange(change);
            }
        }

        String worstRisk = worseRisk(summary.getMaxRiskLevel(), alert.getMarketRiskLevel());
        summary.setMaxRiskLevel(worseRisk(worstRisk, alert.getSocialRiskLevel()));

        return summary;
    }

    static String worseRisk(String a, String b) {
        int sevA = a != null ? RISK_SEVERITY.getOrDefault(a, -1) : -1;
        int sevB = b != null ? RISK_SEVERITY.getOrDefault(b, -1) : -1;
        return sevA >= sevB ? (a != null ? a : "") : b;
    }
}
