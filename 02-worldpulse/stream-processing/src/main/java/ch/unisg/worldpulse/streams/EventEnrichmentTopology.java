package ch.unisg.worldpulse.streams;

import ch.unisg.worldpulse.streams.avro.EnrichedAlert;
import ch.unisg.worldpulse.streams.avro.NormalizedSignup;
import ch.unisg.worldpulse.streams.avro.RiskLevel;
import ch.unisg.worldpulse.streams.avro.Tier;
import ch.unisg.worldpulse.streams.serialization.avro.AvroSerdes;
import ch.unisg.worldpulse.streams.serialization.json.JsonSerdes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Branched;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Named;
import org.apache.kafka.streams.kstream.Produced;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * App A — Stateless Event Enrichment Topology (E6: Stateless Stream Processing).
 *
 * Patterns implemented :
 *   - Content Filter       mapValues: strip CloudEvents envelope, keep domain fields only
 *   - Event Filter         filter: drop malformed events (null type or data)
 *   - Event Router         split/branch: route by event type — non-deprecated API per Lab 12 Part 1
 *   - Event Translator     mapValues per branch: enrich with riskLevel / normalize tier to enum
 *   - Event Stream Merger  merge: combine market + social alert branches into one stream
 *
 * Data flow:
 *   worldpulse (JSON CloudEvents)
 *     → Content Filter (mapValues: strip envelope)
 *     → Event Filter   (filter: drop null)
 *     → Event Router   (split → market | social | signup | other)
 *     → Event Translator (mapValues per branch)
 *     → Merge (market + social)
 *     → alerts-enriched    (Avro EnrichedAlert)
 *        signups-normalized (Avro NormalizedSignup)
 *
 * Ref: Implementation Plan §3 (App A), §10 Phase 2, Lab 12 Part 1 CryptoTopology
 */
public class EventEnrichmentTopology {

    private static final Logger logger = LoggerFactory.getLogger(EventEnrichmentTopology.class);

    // Single shared ObjectMapper — creating per-record is expensive
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static Topology build() {
        StreamsBuilder builder = new StreamsBuilder();

        // ── Source ───────────────────────────────────────────────────────────────────
        // Read raw CloudEvents JSON from the shared worldpulse topic.
        // Key is null for all existing producers (bluesky-scanner, market-scanner, signup service).
        KStream<String, JsonNode> worldpulse = builder.stream(
                "worldpulse",
                Consumed.with(Serdes.String(), JsonSerdes.JsonNode()));

        // ── Content Filter ( Single-Event Processing) ──────────────────────
        // Strip the CloudEvents envelope (id, source, specversion, correlationid, group).
        // Keep only the domain-relevant fields: type, data, time.
        // Returns null for events missing required fields — handled by the Event Filter below.
        KStream<String, JsonNode> domainEvents = worldpulse
                .mapValues(EventEnrichmentTopology::extractDomainFields);

        // ── Event Filter ( Single-Event Processing) ────────────────────────
        // Drop nulls produced by the Content Filter (malformed or incomplete events).
        KStream<String, JsonNode> validEvents = domainEvents
                .filter((key, value) -> {
                    if (value == null) {
                        logger.debug("Dropping malformed CloudEvent (missing type or data field)");
                        return false;
                    }
                    return true;
                });

        // ── Event Router ( Single-Event Processing) ────────────────────────────────────────
        // Non-deprecated split() API — returns Map<String, KStream> keyed by branch name.
        // Branch name = Named prefix + Branched suffix (e.g. "type-" + "market" = "type-market").
        // Pattern ref: Lab 12 Part 1 CryptoTopology.
        Map<String, KStream<String, JsonNode>> branches = validEvents
                .split(Named.as("type-"))
                .branch(
                        (key, value) -> "MarketAlertEvent".equals(extractType(value)),
                        Branched.as("market"))
                .branch(
                        (key, value) -> "SocialTrendEvent".equals(extractType(value)),
                        Branched.as("social"))
                .branch(
                        (key, value) -> isSignupEvent(extractType(value)),
                        Branched.as("signup"))
                .defaultBranch(Branched.as("other"));

        // ── Event Translator — Market branch (Lecture 8: Single-Event Processing) ────
        // Translates MarketAlertEvent JSON → EnrichedAlert Avro record.
        // Computes riskLevel from |changePercent| per Plan §3 enrichment logic.
        KStream<String, EnrichedAlert> marketAlerts = branches.get("type-market")
                .mapValues(EventEnrichmentTopology::toMarketAlert)
                .filter((key, value) -> value != null); // drop translation failures

        // ── Event Translator — Social branch ─────────────────────────────────────────
        // Translates SocialTrendEvent JSON → EnrichedAlert Avro record.
        // Computes riskLevel from postCount (urgency mapping) per Plan §3.
        KStream<String, EnrichedAlert> socialAlerts = branches.get("type-social")
                .mapValues(EventEnrichmentTopology::toSocialAlert)
                .filter((key, value) -> value != null);

        // ── Event Translator — Signup branch ─────────────────────────────────────────
        // Translates SignupRequestedEvent / UpgradeRequestedEvent / AccountDeactivationRequestedEvent
        // JSON → NormalizedSignup Avro record. Normalizes tier string to Tier enum.
        // Events without a userId are dropped (userId required as downstream key).
        KStream<String, NormalizedSignup> signups = branches.get("type-signup")
                .mapValues(EventEnrichmentTopology::toNormalizedSignup)
                .filter((key, value) -> value != null);

        // ── Rekey alerts by symbol/topic ─────────────────────────────────────────────
        // Market alerts keyed by symbol (e.g. "AAPL"), social alerts keyed by topic
        // (e.g. "Iran oil strike"). Required for C5 GlobalKTable join — the key must
        // match the symbol-metadata GlobalKTable key.
        KStream<String, EnrichedAlert> keyedMarket = marketAlerts
                .selectKey((key, alert) -> alert.getSymbol());
        KStream<String, EnrichedAlert> keyedSocial = socialAlerts
                .selectKey((key, alert) -> alert.getTopic());

        // ── Event Stream Merger (Lecture 8: Merging) ─────────────────────────────────
        // Combine market and social alert streams into a single alerts stream.
        // Order within the merged stream is non-deterministic (arrival order).
        KStream<String, EnrichedAlert> mergedAlerts = keyedMarket.merge(keyedSocial);

        // ── Sinks ─────────────────────────────────────────────────────────────────────
        // alerts-enriched   → consumed by App B windowed aggregations (Marc's C4)
        //                     and KStream-GlobalKTable join (Marc's C5)
        // signups-normalized → consumed by App B GlobalKTable projection pipeline (Marc's C3)
        mergedAlerts.to(
                "alerts-enriched",
                Produced.with(Serdes.String(), AvroSerdes.EnrichedAlert()));

        signups.to(
                "signups-normalized",
                Produced.with(Serdes.String(), AvroSerdes.NormalizedSignup()));

        return builder.build();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Content Filter logic
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Strips the CloudEvents envelope and returns a simplified domain node with
     * only: type, data, time. Returns null if required fields are absent.
     *
     * This is the Event Envelope pattern (Plan §8): "provides a standard set of
     * fields across all events independent of the underlying format."
     */
    static JsonNode extractDomainFields(JsonNode envelope) {
        if (envelope == null) return null;

        JsonNode typeNode = envelope.get("type");
        JsonNode dataNode = envelope.get("data");

        // Required fields: type must be non-null non-blank, data must be present
        if (typeNode == null || typeNode.isNull() || typeNode.asText().isBlank()) return null;
        if (dataNode == null || dataNode.isNull()) return null;

        ObjectNode domain = MAPPER.createObjectNode();
        domain.set("type", typeNode);
        domain.set("data", dataNode);

        // Preserve event time for timestamp extraction (optional field)
        JsonNode timeNode = envelope.get("time");
        if (timeNode != null && !timeNode.isNull()) {
            domain.set("time", timeNode);
        }

        return domain;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Predicate helpers (used by Event Router branches)
    // ═══════════════════════════════════════════════════════════════════════════════

    static String extractType(JsonNode domain) {
        if (domain == null) return null;
        JsonNode t = domain.get("type");
        return (t != null && !t.isNull()) ? t.asText() : null;
    }

    static boolean isSignupEvent(String type) {
        return "SignupRequestedEvent".equals(type)
                || "UpgradeRequestedEvent".equals(type)
                || "AccountDeactivationRequestedEvent".equals(type);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Event Translators
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * MarketAlertEvent → EnrichedAlert.
     *
     * Enrichment: riskLevel computed from |changePercent| (Plan §3):
     *   |changePercent| >= 8% → CRITICAL
     *   |changePercent| >= 5% → HIGH
     *   |changePercent| >= 3% → MEDIUM
     *   else                  → LOW
     */
    static EnrichedAlert toMarketAlert(JsonNode domain) {
        try {
            JsonNode data = domain.get("data");
            String symbol      = fieldText(data, "symbol");
            String alertType   = fieldText(data, "alertType");
            double changePct   = data.has("changePercent") ? data.get("changePercent").asDouble() : 0.0;

            EnrichedAlert alert = new EnrichedAlert();
            alert.setAlertId(UUID.randomUUID().toString());
            alert.setSource("WorldPulse-MarketScanner");
            alert.setAlertType(alertType != null ? alertType : "UNKNOWN");
            alert.setSymbol(symbol);
            alert.setTopic(null);
            alert.setUserId(null); // market alerts have no userId — leftJoin in C5 handles null
            alert.setDescription(String.format("Market alert: %s changed by %.2f%%",
                    symbol != null ? symbol : "UNKNOWN", changePct));
            alert.setRiskLevel(marketRiskLevel(changePct));
            alert.setChangePercent(changePct);
            alert.setPostCount(null);
            alert.setTimestamp(parseTimestamp(domain));
            return alert;
        } catch (Exception e) {
            logger.warn("Failed to translate MarketAlertEvent, dropping record: {}", e.getMessage());
            return null;
        }
    }

    /**
     * SocialTrendEvent → EnrichedAlert.
     *
     * Enrichment: riskLevel mapped from postCount urgency (Plan §3):
     *   postCount > 100 → CRITICAL
     *   postCount >  50 → HIGH
     *   postCount >  10 → MEDIUM
     *   else            → LOW
     */
    static EnrichedAlert toSocialAlert(JsonNode domain) {
        try {
            JsonNode data = domain.get("data");
            String topic     = fieldText(data, "topic");
            String alertType = fieldText(data, "alertType");
            int postCount    = data.has("postCount") ? data.get("postCount").asInt() : 1;

            EnrichedAlert alert = new EnrichedAlert();
            alert.setAlertId(UUID.randomUUID().toString());
            alert.setSource("WorldPulse-BlueSkyScanner");
            alert.setAlertType(alertType != null ? alertType : "KEYWORD_MATCH");
            alert.setSymbol(null);
            alert.setTopic(topic);
            alert.setUserId(null); // social alerts have no userId
            alert.setDescription(String.format("Social trend: '%s' with %d post(s)",
                    topic != null ? topic : "UNKNOWN", postCount));
            alert.setRiskLevel(socialRiskLevel(postCount));
            alert.setChangePercent(null);
            alert.setPostCount(postCount);
            alert.setTimestamp(parseTimestamp(domain));
            return alert;
        } catch (Exception e) {
            logger.warn("Failed to translate SocialTrendEvent, dropping record: {}", e.getMessage());
            return null;
        }
    }

    /**
     * SignupRequestedEvent / UpgradeRequestedEvent / AccountDeactivationRequestedEvent
     * → NormalizedSignup.
     *
     * Normalization: tier string → Tier enum (FREE / BASIC / PREMIUM / ENTERPRISE).
     * Records without a userId are dropped — userId is required for downstream keying (C3).
     */
    static NormalizedSignup toNormalizedSignup(JsonNode domain) {
        try {
            JsonNode data = domain.get("data");
            String userId = fieldText(data, "userId");

            // userId is required — downstream GlobalKTable projection keys by userId
            if (userId == null || userId.isBlank()) {
                logger.debug("Dropping signup event with missing userId");
                return null;
            }

            // UpgradeRequestedEvent carries targetTier (the tier being upgraded TO)
            String tierStr = data.has("targetTier")
                    ? fieldText(data, "targetTier")
                    : fieldText(data, "tier");

            NormalizedSignup signup = new NormalizedSignup();
            signup.setUserId(userId);
            signup.setName(coalesce(fieldText(data, "name"), ""));
            signup.setEmail(coalesce(fieldText(data, "email"), ""));
            signup.setTier(parseTier(tierStr));
            signup.setEventType(coalesce(extractType(domain), "UNKNOWN"));
            signup.setTimestamp(parseTimestamp(domain));
            return signup;
        } catch (Exception e) {
            logger.warn("Failed to translate signup event, dropping record: {}", e.getMessage());
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Enrichment logic (Plan §3)
    // ═══════════════════════════════════════════════════════════════════════════════

    /** Computes market risk level from the absolute value of changePercent. */
    static RiskLevel marketRiskLevel(double changePercent) {
        double abs = Math.abs(changePercent);
        if (abs >= 8.0) return RiskLevel.CRITICAL;
        if (abs >= 5.0) return RiskLevel.HIGH;
        if (abs >= 3.0) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    /** Maps social postCount to risk level (urgency score per Plan §3). */
    static RiskLevel socialRiskLevel(int postCount) {
        if (postCount > 100) return RiskLevel.CRITICAL;
        if (postCount > 50)  return RiskLevel.HIGH;
        if (postCount > 10)  return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Utilities
    // ═══════════════════════════════════════════════════════════════════════════════

    static String fieldText(JsonNode node, String field) {
        if (node == null || !node.has(field)) return null;
        JsonNode val = node.get(field);
        return val.isNull() ? null : val.asText();
    }

    /** Returns the first non-null value, or the fallback. */
    static String coalesce(String value, String fallback) {
        return value != null ? value : fallback;
    }

    /**
     * Parses the CloudEvents ISO 8601 "time" field to {@link Instant}.
     *
     * Policy on malformed input:
     * <ul>
     *   <li><b>Missing</b> time field — CloudEvents marks {@code time} as
     *       OPTIONAL, so we fall back to wall-clock as a best-effort
     *       approximation of "arrival time". This is non-fatal and logged
     *       at DEBUG.</li>
     *   <li><b>Present but unparseable</b> — a real producer bug. We throw
     *       {@link IllegalArgumentException} so the surrounding translator
     *       (which catches all exceptions and returns {@code null}) drops
     *       the record and logs a WARN. Silently rewriting a malformed
     *       timestamp with the wall-clock would hide the producer bug and
     *       let bad data leak downstream into time-correlated joins.</li>
     * </ul>
     */
    static Instant parseTimestamp(JsonNode domain) {
        JsonNode timeNode = domain.get("time");
        if (timeNode == null || timeNode.isNull()) {
            logger.debug("CloudEvent has no 'time' field; using wall-clock fallback");
            return Instant.now();
        }
        String raw = timeNode.asText();
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "CloudEvent 'time' is present but unparseable: '" + raw + "'", e);
        }
    }

    /** Parses a tier string to the Tier Avro enum. Defaults to FREE on unknown values. */
    static Tier parseTier(String tierStr) {
        if (tierStr == null) return Tier.FREE;
        try {
            return Tier.valueOf(tierStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            logger.debug("Unknown tier '{}', defaulting to FREE", tierStr);
            return Tier.FREE;
        }
    }
}
