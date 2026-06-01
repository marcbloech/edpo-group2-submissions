package ch.unisg.worldpulse.streams;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.List;
import java.util.Properties;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for EventEnrichmentTopology (App A — B1).
 *
 * Uses TopologyTestDriver (no real Kafka needed) — same pattern as AnalyticsDashboardTopologyTest.
 * Covers all 5 EIP patterns implemented in the topology:
 *   - Content Filter  (extractDomainFields)
 *   - Event Filter    (null/malformed events dropped)
 *   - Event Router    (split/branch by type)
 *   - Event Translator (toMarketAlert, toSocialAlert, toNormalizedSignup)
 *   - Event Stream Merger (market + social → single alerts-enriched topic)
 */
class EventEnrichmentTopologyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TopologyTestDriver testDriver;
    private TestInputTopic<String, JsonNode> worldpulseTopic;
    private TestOutputTopic<String, EnrichedAlert> alertsEnrichedTopic;
    private TestOutputTopic<String, NormalizedSignup> signupsNormalizedTopic;

    @BeforeEach
    void setup() {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "test-enrichment");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.StringSerde.class);

        testDriver = new TopologyTestDriver(EventEnrichmentTopology.build(), props);

        worldpulseTopic = testDriver.createInputTopic(
                "worldpulse",
                new StringSerializer(),
                JsonSerdes.JsonNode().serializer());

        alertsEnrichedTopic = testDriver.createOutputTopic(
                "alerts-enriched",
                new StringDeserializer(),
                AvroSerdes.EnrichedAlert().deserializer());

        signupsNormalizedTopic = testDriver.createOutputTopic(
                "signups-normalized",
                new StringDeserializer(),
                AvroSerdes.NormalizedSignup().deserializer());
    }

    @AfterEach
    void teardown() {
        if (testDriver != null) testDriver.close();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private JsonNode cloudEvent(String type, ObjectNode data) {
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("type", type);
        envelope.put("id", java.util.UUID.randomUUID().toString());
        envelope.put("source", "test");
        envelope.put("time", Instant.now().toString());
        envelope.set("data", data);
        envelope.put("specversion", "1.0");
        return envelope;
    }

    private ObjectNode marketData(String symbol, String alertType, double changePercent) {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("symbol", symbol);
        d.put("alertType", alertType);
        d.put("changePercent", changePercent);
        return d;
    }

    private ObjectNode socialData(String topic, String alertType, int postCount) {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("topic", topic);
        d.put("alertType", alertType);
        d.put("postCount", postCount);
        return d;
    }

    private ObjectNode signupData(String userId, String name, String email, String tier) {
        ObjectNode d = MAPPER.createObjectNode();
        d.put("userId", userId);
        d.put("name", name);
        d.put("email", email);
        d.put("tier", tier);
        return d;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Content Filter — extractDomainFields (unit tests, no Kafka needed)
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Content Filter: extractDomainFields")
    class ContentFilterTest {

        @Test
        @DisplayName("Valid envelope returns domain node with type + data + time")
        void validEnvelopeReturnsDomainFields() {
            ObjectNode envelope = MAPPER.createObjectNode();
            envelope.put("type", "MarketAlertEvent");
            envelope.put("id", "some-id");
            envelope.put("source", "test");
            envelope.put("specversion", "1.0");
            envelope.put("time", "2026-01-01T00:00:00Z");
            envelope.set("data", MAPPER.createObjectNode().put("symbol", "BTC"));

            JsonNode domain = EventEnrichmentTopology.extractDomainFields(envelope);

            assertThat(domain).isNotNull();
            assertThat(domain.get("type").asText()).isEqualTo("MarketAlertEvent");
            assertThat(domain.get("data").get("symbol").asText()).isEqualTo("BTC");
            assertThat(domain.get("time").asText()).isEqualTo("2026-01-01T00:00:00Z");
            // Envelope-only fields must be stripped
            assertThat(domain.has("id")).isFalse();
            assertThat(domain.has("source")).isFalse();
            assertThat(domain.has("specversion")).isFalse();
        }

        @Test
        @DisplayName("Null envelope returns null")
        void nullEnvelopeReturnsNull() {
            assertThat(EventEnrichmentTopology.extractDomainFields(null)).isNull();
        }

        @Test
        @DisplayName("Missing type field returns null")
        void missingTypeReturnsNull() {
            ObjectNode envelope = MAPPER.createObjectNode();
            envelope.set("data", MAPPER.createObjectNode());
            assertThat(EventEnrichmentTopology.extractDomainFields(envelope)).isNull();
        }

        @Test
        @DisplayName("Blank type field returns null")
        void blankTypeReturnsNull() {
            ObjectNode envelope = MAPPER.createObjectNode();
            envelope.put("type", "   ");
            envelope.set("data", MAPPER.createObjectNode());
            assertThat(EventEnrichmentTopology.extractDomainFields(envelope)).isNull();
        }

        @Test
        @DisplayName("Missing data field returns null")
        void missingDataReturnsNull() {
            ObjectNode envelope = MAPPER.createObjectNode();
            envelope.put("type", "MarketAlertEvent");
            assertThat(EventEnrichmentTopology.extractDomainFields(envelope)).isNull();
        }

        @Test
        @DisplayName("Optional time field omitted when absent in envelope")
        void missingTimeIsOmitted() {
            ObjectNode envelope = MAPPER.createObjectNode();
            envelope.put("type", "MarketAlertEvent");
            envelope.set("data", MAPPER.createObjectNode());
            // no "time" field

            JsonNode domain = EventEnrichmentTopology.extractDomainFields(envelope);
            assertThat(domain).isNotNull();
            assertThat(domain.has("time")).isFalse();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Risk Level thresholds (unit tests)
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Risk Level: marketRiskLevel thresholds")
    class MarketRiskLevelTest {

        @Test @DisplayName("|changePercent| >= 8% → CRITICAL")
        void criticalThreshold() {
            assertThat(EventEnrichmentTopology.marketRiskLevel(8.0)).isEqualTo(RiskLevel.CRITICAL);
            assertThat(EventEnrichmentTopology.marketRiskLevel(9.5)).isEqualTo(RiskLevel.CRITICAL);
            assertThat(EventEnrichmentTopology.marketRiskLevel(-8.1)).isEqualTo(RiskLevel.CRITICAL);
        }

        @Test @DisplayName("|changePercent| >= 5% and < 8% → HIGH")
        void highThreshold() {
            assertThat(EventEnrichmentTopology.marketRiskLevel(5.0)).isEqualTo(RiskLevel.HIGH);
            assertThat(EventEnrichmentTopology.marketRiskLevel(7.9)).isEqualTo(RiskLevel.HIGH);
            assertThat(EventEnrichmentTopology.marketRiskLevel(-6.0)).isEqualTo(RiskLevel.HIGH);
        }

        @Test @DisplayName("|changePercent| >= 3% and < 5% → MEDIUM")
        void mediumThreshold() {
            assertThat(EventEnrichmentTopology.marketRiskLevel(3.0)).isEqualTo(RiskLevel.MEDIUM);
            assertThat(EventEnrichmentTopology.marketRiskLevel(4.9)).isEqualTo(RiskLevel.MEDIUM);
            assertThat(EventEnrichmentTopology.marketRiskLevel(-3.5)).isEqualTo(RiskLevel.MEDIUM);
        }

        @Test @DisplayName("|changePercent| < 3% → LOW")
        void lowThreshold() {
            assertThat(EventEnrichmentTopology.marketRiskLevel(0.0)).isEqualTo(RiskLevel.LOW);
            assertThat(EventEnrichmentTopology.marketRiskLevel(2.9)).isEqualTo(RiskLevel.LOW);
            assertThat(EventEnrichmentTopology.marketRiskLevel(-1.0)).isEqualTo(RiskLevel.LOW);
        }
    }

    @Nested
    @DisplayName("Risk Level: socialRiskLevel thresholds")
    class SocialRiskLevelTest {

        @Test @DisplayName("postCount > 100 → CRITICAL")
        void criticalThreshold() {
            assertThat(EventEnrichmentTopology.socialRiskLevel(101)).isEqualTo(RiskLevel.CRITICAL);
            assertThat(EventEnrichmentTopology.socialRiskLevel(500)).isEqualTo(RiskLevel.CRITICAL);
        }

        @Test @DisplayName("postCount > 50 and <= 100 → HIGH")
        void highThreshold() {
            assertThat(EventEnrichmentTopology.socialRiskLevel(51)).isEqualTo(RiskLevel.HIGH);
            assertThat(EventEnrichmentTopology.socialRiskLevel(100)).isEqualTo(RiskLevel.HIGH);
        }

        @Test @DisplayName("postCount > 10 and <= 50 → MEDIUM")
        void mediumThreshold() {
            assertThat(EventEnrichmentTopology.socialRiskLevel(11)).isEqualTo(RiskLevel.MEDIUM);
            assertThat(EventEnrichmentTopology.socialRiskLevel(50)).isEqualTo(RiskLevel.MEDIUM);
        }

        @Test @DisplayName("postCount <= 10 → LOW")
        void lowThreshold() {
            assertThat(EventEnrichmentTopology.socialRiskLevel(1)).isEqualTo(RiskLevel.LOW);
            assertThat(EventEnrichmentTopology.socialRiskLevel(10)).isEqualTo(RiskLevel.LOW);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Event Router — topology-level branching
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Event Router: branching by event type")
    class EventRouterTest {

        @Test
        @DisplayName("MarketAlertEvent routed to alerts-enriched, not signups-normalized")
        void marketGoesToAlertsOnly() {
            worldpulseTopic.pipeInput("k1",
                    cloudEvent("MarketAlertEvent", marketData("BTC", "PRICE_SPIKE", 5.0)));

            assertThat(alertsEnrichedTopic.isEmpty()).isFalse();
            assertThat(signupsNormalizedTopic.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("SocialTrendEvent routed to alerts-enriched, not signups-normalized")
        void socialGoesToAlertsOnly() {
            worldpulseTopic.pipeInput("k1",
                    cloudEvent("SocialTrendEvent", socialData("bitcoin", "KEYWORD_MATCH", 20)));

            assertThat(alertsEnrichedTopic.isEmpty()).isFalse();
            assertThat(signupsNormalizedTopic.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("SignupRequestedEvent routed to signups-normalized, not alerts-enriched")
        void signupGoesToSignupsOnly() {
            worldpulseTopic.pipeInput("k1",
                    cloudEvent("SignupRequestedEvent", signupData("u1", "Alice", "a@b.com", "FREE")));

            assertThat(signupsNormalizedTopic.isEmpty()).isFalse();
            assertThat(alertsEnrichedTopic.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("UpgradeRequestedEvent routed to signups-normalized")
        void upgradeGoesToSignups() {
            ObjectNode data = signupData("u1", "Alice", "a@b.com", "FREE");
            data.put("targetTier", "PREMIUM");
            worldpulseTopic.pipeInput("k1", cloudEvent("UpgradeRequestedEvent", data));

            assertThat(signupsNormalizedTopic.isEmpty()).isFalse();
            assertThat(alertsEnrichedTopic.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("AccountDeactivationRequestedEvent routed to signups-normalized")
        void deactivationGoesToSignups() {
            worldpulseTopic.pipeInput("k1",
                    cloudEvent("AccountDeactivationRequestedEvent",
                            signupData("u1", "Alice", "a@b.com", "FREE")));

            assertThat(signupsNormalizedTopic.isEmpty()).isFalse();
            assertThat(alertsEnrichedTopic.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("Unknown event type is dropped (default branch produces no output)")
        void unknownTypeDropped() {
            worldpulseTopic.pipeInput("k1",
                    cloudEvent("SomeOtherEvent", MAPPER.createObjectNode().put("foo", "bar")));

            assertThat(alertsEnrichedTopic.isEmpty()).isTrue();
            assertThat(signupsNormalizedTopic.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("Malformed event missing data field is dropped by Event Filter")
        void malformedEventDropped() {
            ObjectNode envelope = MAPPER.createObjectNode();
            envelope.put("type", "MarketAlertEvent");
            // no "data" field — extractDomainFields returns null → filter drops it

            worldpulseTopic.pipeInput("k1", envelope);

            assertThat(alertsEnrichedTopic.isEmpty()).isTrue();
            assertThat(signupsNormalizedTopic.isEmpty()).isTrue();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Event Translator — MarketAlertEvent → EnrichedAlert
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Event Translator: MarketAlertEvent → EnrichedAlert")
    class MarketTranslatorTest {

        @Test
        @DisplayName("All fields correctly mapped from MarketAlertEvent")
        void allFieldsMapped() {
            worldpulseTopic.pipeInput("k1",
                    cloudEvent("MarketAlertEvent", marketData("ETH", "PRICE_DROP", -9.0)));

            EnrichedAlert alert = alertsEnrichedTopic.readValue();

            assertThat(alert.getAlertId()).isNotBlank();
            assertThat(alert.getSource()).isEqualTo("WorldPulse-MarketScanner");
            assertThat(alert.getAlertType()).isEqualTo("PRICE_DROP");
            assertThat(alert.getSymbol()).isEqualTo("ETH");
            assertThat(alert.getTopic()).isNull();
            assertThat(alert.getUserId()).isNull();
            assertThat(alert.getChangePercent()).isEqualTo(-9.0);
            assertThat(alert.getPostCount()).isNull();
            assertThat(alert.getRiskLevel()).isEqualTo(RiskLevel.CRITICAL); // |-9| >= 8
            assertThat(alert.getTimestamp()).isNotNull();
        }

        @Test
        @DisplayName("riskLevel CRITICAL when |changePercent| >= 8%")
        void riskLevelCritical() {
            worldpulseTopic.pipeInput("k1",
                    cloudEvent("MarketAlertEvent", marketData("BTC", "SPIKE", 8.0)));
            assertThat(alertsEnrichedTopic.readValue().getRiskLevel()).isEqualTo(RiskLevel.CRITICAL);
        }

        @Test
        @DisplayName("riskLevel HIGH when |changePercent| in [5, 8)")
        void riskLevelHigh() {
            worldpulseTopic.pipeInput("k1",
                    cloudEvent("MarketAlertEvent", marketData("BTC", "SPIKE", 6.0)));
            assertThat(alertsEnrichedTopic.readValue().getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        }

        @Test
        @DisplayName("riskLevel MEDIUM when |changePercent| in [3, 5)")
        void riskLevelMedium() {
            worldpulseTopic.pipeInput("k1",
                    cloudEvent("MarketAlertEvent", marketData("BTC", "SPIKE", 4.0)));
            assertThat(alertsEnrichedTopic.readValue().getRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
        }

        @Test
        @DisplayName("riskLevel LOW when |changePercent| < 3%")
        void riskLevelLow() {
            worldpulseTopic.pipeInput("k1",
                    cloudEvent("MarketAlertEvent", marketData("BTC", "SPIKE", 1.0)));
            assertThat(alertsEnrichedTopic.readValue().getRiskLevel()).isEqualTo(RiskLevel.LOW);
        }

        @Test
        @DisplayName("description contains symbol and changePercent")
        void descriptionFormatted() {
            worldpulseTopic.pipeInput("k1",
                    cloudEvent("MarketAlertEvent", marketData("AAPL", "PRICE_SPIKE", 5.5)));
            EnrichedAlert alert = alertsEnrichedTopic.readValue();
            assertThat(alert.getDescription()).contains("AAPL").contains("5.50");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Event Translator — SocialTrendEvent → EnrichedAlert
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Event Translator: SocialTrendEvent → EnrichedAlert")
    class SocialTranslatorTest {

        @Test
        @DisplayName("All fields correctly mapped from SocialTrendEvent")
        void allFieldsMapped() {
            worldpulseTopic.pipeInput("k1",
                    cloudEvent("SocialTrendEvent", socialData("bitcoin", "KEYWORD_MATCH", 150)));

            EnrichedAlert alert = alertsEnrichedTopic.readValue();

            assertThat(alert.getAlertId()).isNotBlank();
            assertThat(alert.getSource()).isEqualTo("WorldPulse-BlueSkyScanner");
            assertThat(alert.getAlertType()).isEqualTo("KEYWORD_MATCH");
            assertThat(alert.getTopic()).isEqualTo("bitcoin");
            assertThat(alert.getSymbol()).isNull();
            assertThat(alert.getUserId()).isNull();
            assertThat(alert.getPostCount()).isEqualTo(150);
            assertThat(alert.getChangePercent()).isNull();
            assertThat(alert.getRiskLevel()).isEqualTo(RiskLevel.CRITICAL); // 150 > 100
            assertThat(alert.getTimestamp()).isNotNull();
        }

        @Test
        @DisplayName("riskLevel CRITICAL when postCount > 100")
        void riskLevelCritical() {
            worldpulseTopic.pipeInput("k1",
                    cloudEvent("SocialTrendEvent", socialData("eth", "MATCH", 101)));
            assertThat(alertsEnrichedTopic.readValue().getRiskLevel()).isEqualTo(RiskLevel.CRITICAL);
        }

        @Test
        @DisplayName("riskLevel HIGH when postCount in (50, 100]")
        void riskLevelHigh() {
            worldpulseTopic.pipeInput("k1",
                    cloudEvent("SocialTrendEvent", socialData("eth", "MATCH", 75)));
            assertThat(alertsEnrichedTopic.readValue().getRiskLevel()).isEqualTo(RiskLevel.HIGH);
        }

        @Test
        @DisplayName("riskLevel MEDIUM when postCount in (10, 50]")
        void riskLevelMedium() {
            worldpulseTopic.pipeInput("k1",
                    cloudEvent("SocialTrendEvent", socialData("eth", "MATCH", 25)));
            assertThat(alertsEnrichedTopic.readValue().getRiskLevel()).isEqualTo(RiskLevel.MEDIUM);
        }

        @Test
        @DisplayName("riskLevel LOW when postCount <= 10")
        void riskLevelLow() {
            worldpulseTopic.pipeInput("k1",
                    cloudEvent("SocialTrendEvent", socialData("eth", "MATCH", 5)));
            assertThat(alertsEnrichedTopic.readValue().getRiskLevel()).isEqualTo(RiskLevel.LOW);
        }

        @Test
        @DisplayName("description contains topic and postCount")
        void descriptionFormatted() {
            worldpulseTopic.pipeInput("k1",
                    cloudEvent("SocialTrendEvent", socialData("iran-oil", "MATCH", 42)));
            assertThat(alertsEnrichedTopic.readValue().getDescription())
                    .contains("iran-oil").contains("42");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Event Translator — Signup events → NormalizedSignup
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Event Translator: Signup events → NormalizedSignup")
    class SignupTranslatorTest {

        @Test
        @DisplayName("SignupRequestedEvent: all fields mapped, tier normalized to enum")
        void signupAllFieldsMapped() {
            worldpulseTopic.pipeInput("k1",
                    cloudEvent("SignupRequestedEvent",
                            signupData("user-1", "Alice", "alice@test.com", "PREMIUM")));

            NormalizedSignup signup = signupsNormalizedTopic.readValue();

            assertThat(signup.getUserId()).isEqualTo("user-1");
            assertThat(signup.getName()).isEqualTo("Alice");
            assertThat(signup.getEmail()).isEqualTo("alice@test.com");
            assertThat(signup.getTier()).isEqualTo(Tier.PREMIUM);
            assertThat(signup.getEventType()).isEqualTo("SignupRequestedEvent");
            assertThat(signup.getTimestamp()).isNotNull();
        }

        @Test
        @DisplayName("UpgradeRequestedEvent: targetTier field used for tier normalization")
        void upgradeUsesTargetTier() {
            ObjectNode data = signupData("user-1", "Alice", "alice@test.com", "FREE");
            data.put("targetTier", "ENTERPRISE");
            worldpulseTopic.pipeInput("k1", cloudEvent("UpgradeRequestedEvent", data));

            NormalizedSignup signup = signupsNormalizedTopic.readValue();

            assertThat(signup.getTier()).isEqualTo(Tier.ENTERPRISE);
            assertThat(signup.getEventType()).isEqualTo("UpgradeRequestedEvent");
        }

        @Test
        @DisplayName("AccountDeactivationRequestedEvent: processed correctly")
        void deactivationProcessed() {
            worldpulseTopic.pipeInput("k1",
                    cloudEvent("AccountDeactivationRequestedEvent",
                            signupData("user-1", "Alice", "alice@test.com", "BASIC")));

            NormalizedSignup signup = signupsNormalizedTopic.readValue();

            assertThat(signup.getUserId()).isEqualTo("user-1");
            assertThat(signup.getEventType()).isEqualTo("AccountDeactivationRequestedEvent");
        }

        @Test
        @DisplayName("Unknown tier string defaults to FREE")
        void unknownTierDefaultsToFree() {
            worldpulseTopic.pipeInput("k1",
                    cloudEvent("SignupRequestedEvent",
                            signupData("user-1", "Alice", "alice@test.com", "GOLD")));

            assertThat(signupsNormalizedTopic.readValue().getTier()).isEqualTo(Tier.FREE);
        }

        @Test
        @DisplayName("Signup event without userId is dropped")
        void missingUserIdDropped() {
            ObjectNode data = MAPPER.createObjectNode();
            data.put("name", "NoId");
            data.put("email", "noid@test.com");
            data.put("tier", "FREE");
            // no userId field

            worldpulseTopic.pipeInput("k1", cloudEvent("SignupRequestedEvent", data));

            assertThat(signupsNormalizedTopic.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("All 4 tier values parse correctly")
        void allTierValuesParse() {
            List.of("FREE", "BASIC", "PREMIUM", "ENTERPRISE").forEach(tier -> {
                worldpulseTopic.pipeInput("k",
                        cloudEvent("SignupRequestedEvent",
                                signupData("u-" + tier, "User", "u@t.com", tier)));
            });

            assertThat(signupsNormalizedTopic.readValue().getTier()).isEqualTo(Tier.FREE);
            assertThat(signupsNormalizedTopic.readValue().getTier()).isEqualTo(Tier.BASIC);
            assertThat(signupsNormalizedTopic.readValue().getTier()).isEqualTo(Tier.PREMIUM);
            assertThat(signupsNormalizedTopic.readValue().getTier()).isEqualTo(Tier.ENTERPRISE);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Event Stream Merger — market + social both land on alerts-enriched
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Event Stream Merger: market + social → single alerts-enriched topic")
    class MergerTest {

        @Test
        @DisplayName("Both MarketAlertEvent and SocialTrendEvent produce records on alerts-enriched")
        void bothSourcesMergeToAlertsEnriched() {
            worldpulseTopic.pipeInput("k1",
                    cloudEvent("MarketAlertEvent", marketData("BTC", "SPIKE", 5.0)));
            worldpulseTopic.pipeInput("k2",
                    cloudEvent("SocialTrendEvent", socialData("bitcoin", "MATCH", 60)));

            List<EnrichedAlert> alerts = alertsEnrichedTopic.readValuesToList();

            assertThat(alerts).hasSize(2);
            assertThat(alerts).extracting(a -> a.getSource())
                    .containsExactlyInAnyOrder(
                            "WorldPulse-MarketScanner",
                            "WorldPulse-BlueSkyScanner");
        }

        @Test
        @DisplayName("Mixed event types: alerts and signups land on separate topics")
        void alertsAndSignupsSeparated() {
            worldpulseTopic.pipeInput("k1",
                    cloudEvent("MarketAlertEvent", marketData("ETH", "DROP", 3.5)));
            worldpulseTopic.pipeInput("k2",
                    cloudEvent("SignupRequestedEvent",
                            signupData("u1", "Bob", "bob@test.com", "BASIC")));
            worldpulseTopic.pipeInput("k3",
                    cloudEvent("SocialTrendEvent", socialData("ethereum", "MATCH", 120)));

            assertThat(alertsEnrichedTopic.readValuesToList()).hasSize(2);
            assertThat(signupsNormalizedTopic.readValuesToList()).hasSize(1);
        }

        @Test
        @DisplayName("Each merged alert gets a unique alertId")
        void eachAlertHasUniqueId() {
            worldpulseTopic.pipeInput("k1",
                    cloudEvent("MarketAlertEvent", marketData("BTC", "SPIKE", 5.0)));
            worldpulseTopic.pipeInput("k2",
                    cloudEvent("SocialTrendEvent", socialData("bitcoin", "MATCH", 60)));

            List<EnrichedAlert> alerts = alertsEnrichedTopic.readValuesToList();

            assertThat(alerts.get(0).getAlertId())
                    .isNotEqualTo(alerts.get(1).getAlertId());
        }
    }
}
